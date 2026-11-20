package com.yuminaga.motonav

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
 * 阶段一特性：一键沿途顺路搜 (加油站 / 服务区 / 维修点 / 洗手间)
 * 纯端侧闭环、零云端服务器依赖，利用高德官方 RoutePOISearch 接口；
 * 导航中确认即作为途经点改线，巡航/待机中确认即通过圆屏选路规划直接导航。
 */
object AlongRouteSearcher {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile var candidateName: String = ""
    @Volatile var candidatePoint: NaviLatLng? = null
    @Volatile var candidateDetourM: Int = 0
    @Volatile var candidateDistM: Int = 0
    @Volatile var candidateCat: String = "GAS"
    @Volatile var isSearching: Boolean = false

    private data class CatSpec(
        val code: String,
        val label: String,
        val routeType: RoutePOISearch.RoutePOISearchType,
        val keyword: String,
        val typeCode: String
    )

    private fun specOf(cat: String): CatSpec = when (cat.uppercase()) {
        "SVC" -> CatSpec("SVC", "服务区", RoutePOISearch.RoutePOISearchType.TypeServiceArea, "服务区", "180300")
        "REP" -> CatSpec("REP", "维修点", RoutePOISearch.RoutePOISearchType.TypeMaintenanceStation, "汽车维修", "030000")
        "WC"  -> CatSpec("WC",  "洗手间", RoutePOISearch.RoutePOISearchType.TypeToilet, "公共厕所", "200300")
        else  -> CatSpec("GAS", "加油站", RoutePOISearch.RoutePOISearchType.TypeGasStation, "加油站", "010100")
    }

    fun searchGas(context: Context) = search(context, "GAS")

    /**
     * 发起顺路/周边设施搜索（GAS / SVC / REP / WC）
     */
    fun search(context: Context, category: String) {
        if (isSearching) return
        isSearching = true
        val spec = specOf(category)
        candidateCat = spec.code

        val navi = try { AMapNavi.getInstance(context.applicationContext) } catch (_: Throwable) { null }
        val path = if (!NaviBridge.cruising) navi?.naviPath else null
        val coords = path?.coordList

        if (coords != null && coords.size >= 2) {
            searchAlongRoute(context, coords, spec)
        } else {
            searchNearby(context, spec)
        }
    }

    private fun searchAlongRoute(context: Context, coords: List<NaviLatLng>, spec: CatSpec) {
        val pos = NaviBridge.currentPosition()
        val myLat = pos[0]
        val myLng = pos[1]

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
            val rangeM = if (spec.code == "SVC") 1000 else 500
            val query = RoutePOISearchQuery(polyline, spec.routeType, rangeM)
            val search = RoutePOISearch(context.applicationContext, query)
            search.setPoiSearchListener(object : RoutePOISearch.OnRoutePOISearchListener {
                override fun onRoutePoiSearched(res: RoutePOISearchResult?, errCode: Int) {
                    handler.post {
                        isSearching = false
                        val items = res?.routePois
                        if (errCode == 1000 && !items.isNullOrEmpty()) {
                            val best = items.minByOrNull { it.distance } ?: items.first()
                            onCandidateFound(
                                spec = spec,
                                name = best.title ?: "顺路${spec.label}",
                                lat = best.point.latitude,
                                lng = best.point.longitude,
                                detourM = best.distance.roundToInt().coerceAtLeast(0),
                                distM = best.duration.roundToInt().coerceAtLeast(100)
                            )
                        } else {
                            onCandidateNotFound(spec)
                        }
                    }
                }
            })
            search.searchRoutePOIAsyn()
        } catch (_: Throwable) {
            isSearching = false
            onCandidateNotFound(spec)
        }
    }

    private fun searchNearby(context: Context, spec: CatSpec) {
        val pos = NaviBridge.currentPosition()
        val myLat = pos[0]
        val myLng = pos[1]
        if (myLat.isNaN() || myLng.isNaN()) {
            isSearching = false
            onCandidateNotFound(spec)
            return
        }
        try {
            val query = PoiSearch.Query(spec.keyword, spec.typeCode, "")
            query.pageSize = 5
            val search = PoiSearch(context.applicationContext, query)
            val radius = if (spec.code == "SVC") 15000 else 5000
            search.bound = PoiSearch.SearchBound(LatLonPoint(myLat, myLng), radius)
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
                                spec = spec,
                                name = best.title ?: spec.label,
                                lat = best.latLonPoint.latitude,
                                lng = best.latLonPoint.longitude,
                                detourM = 0,
                                distM = dist
                            )
                        } else {
                            onCandidateNotFound(spec)
                        }
                    }
                }
                override fun onPoiItemSearched(item: PoiItem?, errCode: Int) {}
            })
            search.searchPOIAsyn()
        } catch (_: Throwable) {
            isSearching = false
            onCandidateNotFound(spec)
        }
    }

    private fun onCandidateFound(spec: CatSpec, name: String, lat: Double, lng: Double, detourM: Int, distM: Int) {
        candidateName = name
        candidatePoint = NaviLatLng(lat, lng)
        candidateDetourM = detourM
        candidateDistM = distM

        val cleanName = name.replace(",", " ").trim()
        BleLink.send("#QA,1,$cleanName,$detourM,$distM")

        val speak = if (detourM > 0) "已找到顺路${spec.label}：$name，偏离 $detourM 米"
        else "已找到附近${spec.label}：$name，距离 $distM 米"
        NaviVoice.onNaviText(speak, false)
    }

    private fun onCandidateNotFound(spec: CatSpec) {
        candidateName = ""
        candidatePoint = null
        candidateDetourM = 0
        candidateDistM = 0
        BleLink.send("#QA,0,未找到顺路${spec.label},0,0")
        NaviVoice.onNaviText("附近暂未发现可用的${spec.label}", false)
    }

    /**
     * 用户在圆屏端确认候选点 (#QC,1)：
     * 导航中 -> 作为途经点插入重算路线；
     * 巡航/待机中 -> 直接作为目的地进入圆屏选路规划。
     */
    fun applyCandidateWayPoint(context: Context): Boolean {
        val pt = candidatePoint ?: return false
        val name = candidateName
        val navi = try { AMapNavi.getInstance(context.applicationContext) } catch (_: Throwable) { null }
        val path = if (!NaviBridge.cruising) navi?.naviPath else null
        val endPt = path?.endPoint

        if (navi != null && endPt != null && NaviBridge.directRouteMode == LocalRouting.DRIVE) {
            val vias = ArrayList<NaviLatLng>()
            path.wayPoint?.let { vias.addAll(it) }
            vias.add(pt)

            val ok = try {
                val ends = ArrayList<NaviLatLng>()
                ends.add(endPt)
                navi.calculateDriveRoute(ends, vias, 12)
            } catch (_: Throwable) {
                false
            }

            if (ok) {
                NaviVoice.onNaviText("已将 $name 添加为途经点，正在重新规划路线", false)
                candidatePoint = null
                candidateName = ""
            }
            return ok
        } else {
            candidatePoint = null
            candidateName = ""
            ScreenRoutePlanner.planForScreen(context, name, "", pt.latitude, pt.longitude)
            return true
        }
    }
}
