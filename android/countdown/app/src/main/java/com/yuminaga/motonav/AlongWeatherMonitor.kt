package com.yuminaga.motonav

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.navi.model.NaviLatLng
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.weather.LocalWeatherForecastResult
import com.amap.api.services.weather.LocalWeatherLiveResult
import com.amap.api.services.weather.WeatherSearch
import com.amap.api.services.weather.WeatherSearchQuery
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 阶段一特性：沿途极端天气智能预警 (在已规划路线沿线标绘天气图标，不弹多余卡片)
 * 纯端侧闭环、零自建云端，利用高德官方 GeocodeSearch 与 WeatherSearch API
 */
object AlongWeatherMonitor {
    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()

    data class WeatherPoint(
        val meterOnRoute: Int,
        val type: Int,       // 1=雨, 2=雷电/强对流, 3=大雾, 4=降雪/结冰, 5=大风
        val desc: String,    // 天气描述（如“暴雨”、“雷阵雨”）
        val adcode: String
    )

    private val cachedPoints = ArrayList<WeatherPoint>()
    @Volatile private var isChecking = false
    private var lastCheckedHash = 0

    /**
     * 路线规划成功后触发沿线天气检索
     * @param coords 路线经纬度折线串
     * @param allLength 路线总长度（米）
     */
    fun checkRouteWeather(context: Context, coords: List<NaviLatLng>?, allLength: Int) {
        if (coords == null || coords.size < 2 || allLength < 15000) {
            // 短途路线 (<15km) 或无路线，清空沿线天气标记
            clearWeather()
            return
        }

        val routeHash = coords.first().hashCode() xor coords.last().hashCode() xor allLength
        if (routeHash == lastCheckedHash) return
        lastCheckedHash = routeHash

        if (isChecking) return
        isChecking = true

        pool.execute {
            try {
                inspectRouteSync(context.applicationContext, coords, allLength)
            } catch (t: Throwable) {
                android.util.Log.w("AlongWeather", "inspectRouteSync failed: ${t.message}")
            } finally {
                isChecking = false
            }
        }
    }

    private fun inspectRouteSync(context: Context, coords: List<NaviLatLng>, allLength: Int) {
        val geocoder = try { GeocodeSearch(context) } catch (_: Throwable) { return }

        // 1. 沿路线每隔约 25km 采集一个经纬度坐标
        val sampleIntervalM = 25000
        val sampleCount = (allLength / sampleIntervalM).coerceIn(1, 6) // 一条路线最多抽样 6 个区县点
        val step = (coords.size / (sampleCount + 1)).coerceAtLeast(1)

        val samples = ArrayList<Pair<Int, NaviLatLng>>()
        for (i in 1..sampleCount) {
            val idx = (i * step).coerceIn(0, coords.size - 1)
            val meter = (allLength * (i.toDouble() / (sampleCount + 1))).roundToInt()
            samples.add(Pair(meter, coords[idx]))
        }

        // 2. 逆地理编码提取区县 adcode
        val targetAdcodes = LinkedHashMap<String, Int>() // adcode -> meterOnRoute
        for ((meter, pt) in samples) {
            try {
                val query = RegeocodeQuery(LatLonPoint(pt.latitude, pt.longitude), 500f, GeocodeSearch.AMAP)
                val addr = geocoder.getFromLocation(query)
                val code = addr?.adCode
                if (!code.isNullOrBlank() && !targetAdcodes.containsKey(code)) {
                    targetAdcodes[code] = meter
                }
            } catch (_: Throwable) {}
        }

        if (targetAdcodes.isEmpty()) {
            clearWeather()
            return
        }

        val foundWarnings = ArrayList<WeatherPoint>()
        val latch = java.util.concurrent.CountDownLatch(targetAdcodes.size)

        // 3. 异步查询各区县的实时天气
        for ((adcode, meter) in targetAdcodes) {
            try {
                val search = WeatherSearch(context)
                search.query = WeatherSearchQuery(adcode, WeatherSearchQuery.WEATHER_TYPE_LIVE)
                search.setOnWeatherSearchListener(object : WeatherSearch.OnWeatherSearchListener {
                    override fun onWeatherLiveSearched(res: LocalWeatherLiveResult?, errCode: Int) {
                        try {
                            val live = res?.liveResult
                            if (errCode == 1000 && live != null) {
                                val wStr = live.weather.orEmpty()
                                val windPowerStr = live.windPower.orEmpty()
                                val type = parseExtremeWeatherType(wStr, windPowerStr)
                                if (type > 0) {
                                    synchronized(foundWarnings) {
                                        foundWarnings.add(WeatherPoint(meter, type, wStr, adcode))
                                    }
                                }
                            }
                        } finally {
                            latch.countDown()
                        }
                    }
                    override fun onWeatherForecastSearched(res: LocalWeatherForecastResult?, errCode: Int) {
                        latch.countDown()
                    }
                })
                search.searchWeatherAsyn()
            } catch (_: Throwable) {
                latch.countDown()
            }
        }

        try {
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) {}

        // 4. 将天气预警点推送给圆屏固件
        handler.post {
            pushWeatherPoints(foundWarnings)
        }
    }

    /**
     * 判断是否属于影响骑行安全的恶劣天气
     */
    private fun parseExtremeWeatherType(weather: String, windPower: String): Int {
        // 雷暴 / 强对流 / 冰雹
        if (weather.contains("雷") || weather.contains("冰雹") || weather.contains("强对流")) {
            return 2
        }
        // 降雪 / 道路结冰
        if (weather.contains("雪") || weather.contains("雨夹雪") || weather.contains("冻雨")) {
            return 4
        }
        // 大雨 / 暴雨 / 强降雨
        if (weather.contains("暴雨") || weather.contains("大雨") || weather.contains("中雨")) {
            return 1
        }
        // 大雾 / 浓雾 / 特强浓雾
        if (weather.contains("雾") || weather.contains("霾") || weather.contains("沙尘")) {
            return 3
        }
        // 强风 (风力 >= 7 级或大风预警)
        val windNum = windPower.filter { it.isDigit() }.toIntOrNull() ?: 0
        if (windNum >= 7 || weather.contains("大风") || weather.contains("烈风") || weather.contains("狂风")) {
            return 5
        }
        return 0 // 普通天气，不在路线上打扰
    }

    private fun pushWeatherPoints(points: List<WeatherPoint>) {
        cachedPoints.clear()
        cachedPoints.addAll(points)

        if (points.isEmpty()) {
            BleLink.send("#WT") // 清空沿线天气标记
            return
        }

        // 协议格式：#WT,桩号1,类型1,桩号2,类型2,...
        val sb = StringBuilder("#WT")
        for (p in points) {
            sb.append(",${p.meterOnRoute},${p.type}")
        }
        BleLink.send(sb.toString())

        // 语音友好提示一次（不弹窗，仅在检测到恶劣天气时简短通知骑手）
        val first = points.first()
        val km = (first.meterOnRoute / 1000).coerceAtLeast(1)
        val tip = when (first.type) {
            1 -> "前方约 $km 公里有${first.desc}天气，路线已为您标绘天气提示"
            2 -> "前方约 $km 公里有雷暴强对流天气，请注意防雷避险"
            3 -> "前方约 $km 公里有大雾，能见度低，请保持车距减速慢行"
            4 -> "前方约 $km 公里有降雪天气，路面湿滑，请小心驾驶"
            5 -> "前方约 $km 公里有大风，请抓稳车把防止侧风"
            else -> ""
        }
        if (tip.isNotBlank()) {
            NaviVoice.onNaviText(tip, false)
        }
    }

    fun clearWeather() {
        cachedPoints.clear()
        BleLink.send("#WT")
    }
}
