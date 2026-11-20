package com.yuminaga.motonav

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.model.AMapNaviPath
import com.amap.api.navi.model.NaviLatLng
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 圆屏端独立算路与选路控制器：
 * 支持锁屏/后台状态下，用户在圆屏收藏列表或沿途/周边搜索结果中选择目的地后，
 * 自动按当前交通工具（驾车/骑行/步行）规划候选路线，同步给圆屏选择，并在圆屏一键开始导航。
 */
object ScreenRoutePlanner {
    private val handler = Handler(Looper.getMainLooper())

    private var activeNavi: AMapNavi? = null
    private var currentMode: Int = LocalRouting.DRIVE
    private var destName: String = ""
    private var destAddr: String = ""
    private var destLat: Double = Double.NaN
    private var destLng: Double = Double.NaN
    private var routeIds: IntArray = IntArray(0)
    private var calcSeq: Int = 0

    private fun clean(s: String, maxLen: Int = 20): String =
        s.trim().replace(",", " ").replace("\n", " ").replace("\r", " ").take(maxLen)

    private fun formatDist(meters: Int): String {
        return if (meters >= 1000) String.format("%.1fkm", meters / 1000.0) else "${meters}m"
    }

    /**
     * 后台按当前交通工具为圆屏规划路线
     */
    fun planForScreen(context: Context, name: String, addr: String, lat: Double, lng: Double) {
        val c = context.applicationContext
        if (!lat.isFinite() || !lng.isFinite() || (lat == 0.0 && lng == 0.0)) {
            BleLink.send("#RP,ERR,坐标无效")
            return
        }
        val prefs = c.getSharedPreferences("motonav", Context.MODE_PRIVATE)
        val mode = LocalRouting.normalize(prefs.getInt("direct_route_mode", LocalRouting.DRIVE))
        val avoidHw = prefs.getBoolean("avoid_hw", true)

        currentMode = mode
        destName = name.ifBlank { "目的地" }
        destAddr = addr
        destLat = lat
        destLng = lng
        routeIds = IntArray(0)
        val seq = ++calcSeq

        BleLink.send("#RP,CLR,${clean(destName, 22)},$mode")

        if (!NavService.running) {
            try { NavService.start(c) } catch (_: Throwable) {}
        }

        val n = NaviBridge.attach(c)
        if (n == null) {
            BleLink.send("#RP,ERR,导航引擎未就绪")
            return
        }
        activeNavi = n
        NaviBridge.directRouteMode = mode

        NaviBridge.listenForRoute(this) { ok, res ->
            handler.post {
                if (seq != calcSeq) return@post
                if (!ok) {
                    val code = (Refl.call(res, "getErrorCode").second as? Int) ?: -1
                    BleLink.send("#RP,ERR,算路失败($code)")
                    return@post
                }
                handleRouteResult(n, res)
            }
        }

        val pos = NaviBridge.currentPosition()
        val from = if (!pos[0].isNaN() && !pos[1].isNaN() && (pos[0] != 0.0 || pos[1] != 0.0)) {
            NaviLatLng(pos[0], pos[1])
        } else null
        val to = NaviLatLng(lat, lng)

        val strategy = try {
            n.strategyConvert(true, avoidHw, false, false, true)
        } catch (_: Throwable) {
            12
        }

        val started = try {
            LocalRouting.calculate(n, mode, from, to, ArrayList(), strategy)
        } catch (e: Throwable) {
            android.util.Log.w("ScreenRoutePlanner", "calculate failed: ${e.message}")
            false
        }

        if (!started) {
            NaviBridge.releaseRoute(this)
            BleLink.send("#RP,ERR,无法启动算路")
        }
    }

    private fun handleRouteResult(n: AMapNavi, res: Any?) {
        val isTravel = (currentMode == LocalRouting.BICYCLE || currentMode == LocalRouting.WALK)
        val pathsMap = LinkedHashMap<Int, AMapNaviPath>()
        if (isTravel) {
            n.naviPath?.let { pathsMap[0] = it }
        } else {
            val map = try { n.naviPaths } catch (_: Throwable) { null }
            if (map != null && map.isNotEmpty()) {
                val fromRes = (Refl.call(res, "getRouteid").second as? IntArray)?.filter { map.containsKey(it) }
                val ids = (if (!fromRes.isNullOrEmpty()) fromRes else map.keys.sorted()).take(3)
                for (id in ids) {
                    map[id]?.let { pathsMap[id] = it }
                }
            } else {
                n.naviPath?.let { pathsMap[0] = it }
            }
        }

        if (pathsMap.isEmpty()) {
            BleLink.send("#RP,ERR,未找到可用路线")
            return
        }

        val ids = pathsMap.keys.toList()
        routeIds = ids.toIntArray()

        val minTimeId = ids.minByOrNull { pathsMap[it]?.allTime ?: Int.MAX_VALUE }
        val minDistId = ids.minByOrNull { pathsMap[it]?.allLength ?: Int.MAX_VALUE }

        for ((idx, id) in ids.withIndex()) {
            val p = pathsMap[id] ?: continue
            val mins = max(1, (p.allTime / 60.0).roundToInt())
            val distStr = formatDist(p.allLength)
            val lights = try { p.lightList?.size ?: 0 } catch (_: Throwable) { 0 }

            val rawLabel = try { p.labels?.split(",")?.firstOrNull()?.trim().orEmpty() } catch (_: Throwable) { "" }
            val tag = when {
                rawLabel.isNotBlank() -> clean(rawLabel, 10)
                isTravel && currentMode == LocalRouting.BICYCLE -> "骑行路线"
                isTravel && currentMode == LocalRouting.WALK -> "步行路线"
                id == minTimeId -> "时间最快"
                id == minDistId -> "距离最短"
                else -> "备选${idx + 1}"
            }

            val trafficStatus = evalTrafficStatus(p)
            BleLink.send("#RP,ADD,$idx,$tag,$mins,$distStr,$lights,$trafficStatus")
        }
        BleLink.send("#RP,SHOW")
    }

    private fun evalTrafficStatus(p: AMapNaviPath): Int {
        return try {
            val totalLen = max(1, p.allLength)
            var jamLen = 0
            var slowLen = 0
            val steps = p.steps ?: return 0
            for (step in steps) {
                val links = step.links ?: continue
                for (link in links) {
                    val st = link.trafficStatus
                    val len = link.length
                    if (st >= 3) jamLen += len
                    else if (st == 2) slowLen += len
                }
            }
            val jamRatio = jamLen.toFloat() / totalLen
            val slowRatio = (jamLen + slowLen).toFloat() / totalLen
            when {
                jamRatio >= 0.15f -> 2
                slowRatio >= 0.28f -> 1
                else -> 0
            }
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * 圆屏确认开始导航指定索引的路线
     */
    fun startSelectedRoute(context: Context, idx: Int) {
        val c = context.applicationContext
        val n = activeNavi ?: try { AMapNavi.getInstance(c) } catch (_: Throwable) { null } ?: return
        val routeId = routeIds.getOrNull(idx) ?: routeIds.firstOrNull() ?: 0
        val isTravel = (currentMode == LocalRouting.BICYCLE || currentMode == LocalRouting.WALK)

        try {
            if (!isTravel && routeIds.isNotEmpty()) {
                n.selectRouteId(routeId)
            }
            Refl.call(n, "setMultipleRouteNaviMode", !isTravel)
            NaviBridge.directRouteMode = currentMode
            NaviBridge.releaseRoute(this)

            if (!NavService.running) {
                try { NavService.start(c) } catch (_: Throwable) {}
            }

            if (NaviBridge.beginNavigation(false)) {
                if (n.startNavi(1)) {
                    try { RideRecorder.start("导航", destName, false) } catch (_: Throwable) {}
                } else {
                    NaviBridge.navigationStartFailed()
                    BleLink.send("#RP,ERR,启动导航失败")
                }
            } else {
                BleLink.send("#RP,ERR,导航会话未就绪")
            }
        } catch (e: Throwable) {
            android.util.Log.e("ScreenRoutePlanner", "startSelectedRoute failed: ${e.message}")
            BleLink.send("#RP,ERR,启动异常")
        }
    }

    fun cancel() {
        calcSeq++
        NaviBridge.releaseRoute(this)
        routeIds = IntArray(0)
    }
}
