package com.example.motonav

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.model.NaviLatLng
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.amap.api.services.routepoisearch.RoutePOIItem
import com.amap.api.services.routepoisearch.RoutePOISearch
import com.amap.api.services.routepoisearch.RoutePOISearchQuery
import com.amap.api.services.routepoisearch.RoutePOISearchResult
import kotlin.math.roundToInt

/**
 * 阶段一特性：一键沿途顺路搜 (加油站 / 维修点 / 服务区)
 * 纯端侧闭环、零云端服务器依赖，利用高德官方 RoutePOISearch 接口
 */
object AlongRouteSearcher {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile var candidateName: String = ""
    @Volatile var candidatePoint: NaviLatLng? = null
    @Volatile var candidateDetourM: Int = 0
    @Volatile var candidateDistM: Int = 0
    @Volatile var isSearching: Boolean = false

    /**
     * 发起顺路加油站搜索
     */
    fun searchGas(context: Context) {
        if (isSearching) return
        isSearching = true

        val navi = try { AMapNavi.getInstance(context.applicationContext) } catch (_: Throwable) { null }
        val path = navi?.naviPath
        val coords = path?.coordList

        // 1. 如果在导航中且拥有当前路线坐标串：使用沿途搜索 RoutePOISearch
        if (coords != null && coords.size >= 2) {
            searchAlongRoute(context, coords, navi)
        } else {
            // 2. 如果未在导航中：按当前 GPS 定位周边 3000 米普通 POI 搜索
            searchNearbyGas(context)
        }
    }

    /**
     * 基于路线折线点的前方沿途检索 (偏离 <= 500m)
     */
    private fun searchAlongRoute(context: Context, coords: List<NaviLatLng>, navi: AMapNavi?) {
        val pos = NaviBridge.currentPosition()
        val myLat = pos[0]
        val myLng = pos[1]

        // 找到距离当前车头最近的路线点索引
        var nearestIdx = 0
        var minDistSq = Double.MAX_VALUE
        if (!myLat.isNaN() && !myLng.isNaN()) {
            for (i in coords.indices) {
                val p = coords[i]
                val dLat = p.latitude - myLat
                val dLng = p.longitude - myLng
                val dSq = dLat * dLat + dLng * dLng
                if (dSq < minDistSq) {
                    minDistSq = dSq
                    nearestIdx = i
                }
            }
        }

        // 从当前车头前方截取接下来约 25km 的路段坐标 (最多 60 个点以满足高德推荐 <= 100 点限制)
        val polyline = ArrayList<LatLonPoint>()
        val total = coords.size
        val start = nearestIdx.coerceIn(0, total - 1)
        val step = kotlin.math.max(1, (total - start) / 50)
        var idx = start
        while (idx < total) {
            val c = coords[idx]
            polyline.add(LatLonPoint(c.latitude, c.longitude))
            if (polyline.size >= 60) break
            idx += step
        }
        if (polyline.size < 2 && coords.isNotEmpty()) {
            polyline.clear()
            polyline.add(LatLonPoint(coords.first().latitude, coords.first().longitude))
            polyline.add(LatLonPoint(coords.last().latitude, coords.last().longitude))
        }

        try {
            val query = RoutePOISearchQuery(
                polyline,
                RoutePOISearch.RoutePOISearchType.TypeGasStation,
                500 // 偏离范围 <= 500 米
            )
            val search = RoutePOISearch(context.applicationContext, query)
            search.setPoiSearchListener(object : RoutePOISearch.OnRoutePOISearchListener {
                override fun onRoutePoiSearched(res: RoutePOISearchResult?, errCode: Int) {
                    handler.post {
                        isSearching = false
                        val items = res?.routePois
                        if (errCode == 1000 && !items.isNullOrEmpty()) {
                            // 挑选顺路偏离最小、距离适中的加油站
                            val best = items.minByOrNull { it.distance } ?: items.first()
                            onCandidateFound(
                                name = best.title ?: "顺路加油站",
                                lat = best.point.latitude,
                                lng = best.point.longitude,
                                detourM = best.distance.roundToInt().coerceAtLeast(0),
                                distM = best.duration.roundToInt().coerceAtLeast(100)
                            )
                        } else {
                            onCandidateNotFound()
                        }
                    }
                }
            })
            search.searchRoutePOIAsyn()
        } catch (t: Throwable) {
            isSearching = false
            onCandidateNotFound()
        }
    }

    /**
     * 周边普通 POI 加油站搜索 (备用方案)
     */
    private fun searchNearbyGas(context: Context) {
        val pos = NaviBridge.currentPosition()
        val myLat = pos[0]
        val myLng = pos[1]
        if (myLat.isNaN() || myLng.isNaN()) {
            isSearching = false
            onCandidateNotFound()
            return
        }
        try {
            val query = PoiSearch.Query("加油站", "010100", "")
            query.pageSize = 5
            val search = PoiSearch(context.applicationContext, query)
            search.bound = PoiSearch.SearchBound(LatLonPoint(myLat, myLng), 4000)
            search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(res: PoiResult?, errCode: Int) {
                    handler.post {
                        isSearching = false
                        val items = res?.pois
                        if (errCode == 1000 && !items.isNullOrEmpty()) {
                            val best = items.first()
                            val resDist = FloatArray(1)
                            android.location.Location.distanceBetween(
                                myLat, myLng, best.latLonPoint.latitude, best.latLonPoint.longitude, resDist
                            )
                            val dist = resDist[0].roundToInt()
                            onCandidateFound(
                                name = best.title ?: "加油站",
                                lat = best.latLonPoint.latitude,
                                lng = best.latLonPoint.longitude,
                                detourM = 0,
                                distM = dist
                            )
                        } else {
                            onCandidateNotFound()
                        }
                    }
                }
                override fun onPoiItemSearched(item: PoiItem?, errCode: Int) {}
            })
            search.searchPOIAsyn()
        } catch (_: Throwable) {
            isSearching = false
            onCandidateNotFound()
        }
    }

    private fun onCandidateFound(name: String, lat: Double, lng: Double, detourM: Int, distM: Int) {
        candidateName = name
        candidatePoint = NaviLatLng(lat, lng)
        candidateDetourM = detourM
        candidateDistM = distM

        // 下发给圆屏：#QA,1,名称,偏离米数,沿途米数
        val cleanName = name.replace(",", " ").trim()
        val msg = "#QA,1,$cleanName,$detourM,$distM"
        BleLink.send(msg)

        NaviVoice.onNaviText("已找到顺路加油站：$name，顺路偏离 $detourM 米", false)
    }

    private fun onCandidateNotFound() {
        candidateName = ""
        candidatePoint = null
        candidateDetourM = 0
        candidateDistM = 0
        BleLink.send("#QA,0,未找到顺路加油站,0,0")
        NaviVoice.onNaviText("前方沿途暂未发现偏离 500 米以内的顺路加油站", false)
    }

    /**
     * 用户在圆屏端确认添加途径点改线 (#QC,1)
     */
    fun applyCandidateWayPoint(context: Context): Boolean {
        val pt = candidatePoint ?: return false
        val name = candidateName
        val navi = try { AMapNavi.getInstance(context.applicationContext) } catch (_: Throwable) { null }
            ?: return false

        val path = navi.naviPath
        val endPt = path?.endPoint ?: return false

        // 现有的途径点列表加上新加油站
        val vias = ArrayList<NaviLatLng>()
        path.wayPoint?.let { vias.addAll(it) }
        vias.add(pt)

        // 重新发起路线规划 (以当前位置或原起点规划，包含途经点，策略 12 默认速度优先)
        val ok = try {
            val ends = ArrayList<NaviLatLng>()
            ends.add(endPt)
            navi.calculateDriveRoute(ends, vias, 12)
        } catch (_: Throwable) {
            false
        }

        if (ok) {
            NaviVoice.onNaviText("已将 $name 添加为途径点，正在重新规划路线", false)
            candidatePoint = null
            candidateName = ""
        }
        return ok
    }
}
