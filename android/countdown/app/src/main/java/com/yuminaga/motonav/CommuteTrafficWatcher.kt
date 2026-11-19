package com.yuminaga.motonav

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.BusRouteResult
import com.amap.api.services.route.DriveRouteResult
import com.amap.api.services.route.RideRouteResult
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.WalkRouteResult
import kotlin.math.roundToInt

/**
 * 阶段一特性：每日通勤路况实时看板 (去公司 / 回家 一键路况看板)
 * 纯端侧闭环，利用 RouteSearch 独立后台算路，完全不打断前台导航
 */
object CommuteTrafficWatcher {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var isQuerying = false

    /**
     * 查询去家/去公司的实时通勤耗时与路况
     * @param targetId 0 = 家, 1 = 公司
     * @param destLat 目标纬度
     * @param destLng 目标经度
     */
    fun queryCommute(context: Context, targetId: Int, destLat: Double, destLng: Double) {
        val pos = NaviBridge.currentPosition()
        val curLat = pos[0]
        val curLng = pos[1]
        if (curLat.isNaN() || curLng.isNaN() || destLat.isNaN() || destLng.isNaN()) {
            return
        }

        if (isQuerying) return
        isQuerying = true

        try {
            val routeSearch = RouteSearch(context.applicationContext)
            val fromAndTo = RouteSearch.FromAndTo(
                LatLonPoint(curLat, curLng),
                LatLonPoint(destLat, destLng)
            )
            // 采用速度优先 + 避让拥堵策略
            val query = RouteSearch.DriveRouteQuery(
                fromAndTo,
                RouteSearch.DrivingDefault,
                null,
                null,
                ""
            )

            routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                override fun onDriveRouteSearched(result: DriveRouteResult?, errCode: Int) {
                    isQuerying = false
                    val path = result?.paths?.firstOrNull()
                    if (errCode == 1000 && path != null) {
                        val durationMin = (path.duration / 60.0).roundToInt().coerceAtLeast(1)
                        var jamM = 0
                        var slowM = 0

                        val steps = path.steps
                        if (steps != null) {
                            for (st in steps) {
                                val tmcs = st.tmCs
                                if (tmcs != null) {
                                    for (t in tmcs) {
                                        val s = t.status.orEmpty()
                                        if (s.contains("拥堵")) {
                                            jamM += t.distance
                                        } else if (s.contains("缓行")) {
                                            slowM += t.distance
                                        }
                                    }
                                }
                            }
                        }

                        // 综合判定整体路况等级：0 畅通, 1 缓行, 2 拥堵
                        val status = when {
                            jamM >= 500 -> 2
                            slowM >= 800 -> 1
                            else -> 0
                        }

                        // 下发给圆屏：#TC,<targetId>,<durationMin>,<status>
                        val msg = "#TC,$targetId,$durationMin,$status"
                        BleLink.send(msg)
                    }
                }

                override fun onBusRouteSearched(p0: BusRouteResult?, p1: Int) {}
                override fun onWalkRouteSearched(p0: WalkRouteResult?, p1: Int) {}
                override fun onRideRouteSearched(p0: RideRouteResult?, p1: Int) {}
            })

            routeSearch.calculateDriveRouteAsyn(query)
        } catch (_: Throwable) {
            isQuerying = false
        }
    }
}
