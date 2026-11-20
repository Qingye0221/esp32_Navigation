package com.example.motonav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import java.util.UUID

/**
 * 阶段二：三级渐进室内定位采集器
 *
 * - Level 1: 高德室内定位（buildingId + floor）+ 35m 近场室内 POI 提取区位/柱号
 * - Level 2: 无室内图地库兜底（气压计推算楼层 + Wi-Fi/入库前最后有效 GPS）
 * - Level 3: 非高德引擎/无 Key 时安全回退至 Android 系统 LocationManager + 气压计，零崩溃
 */
object IndoorParkingLocator {
    private const val TAG = "IndoorParking"
    private const val SAMPLE_TIMEOUT_MS = 14_000L
    private const val MIN_CONVERGE_MS = 3_000L

    private val main = Handler(Looper.getMainLooper())
    private val spotCodeRegex = Regex("([A-Z]-?\\d{2,4}|[A-Z]区\\d*|[A-Z]\\d{2,4}|\\d+号梯|电梯厅)")

    private var activeClient: AMapLocationClient? = null
    private var timeoutRunnable: Runnable? = null

    data class FallbackEntrance(val lat: Double, val lng: Double, val mallHint: String = "")

    fun cancel() {
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = null
        runCatching {
            activeClient?.stopLocation()
            activeClient?.onDestroy()
        }
        activeClient = null
    }

    /**
     * 启动一次室内停车位采样（最长 14 秒，收敛即提前回调）
     */
    fun locateOnce(
        context: Context,
        entranceFallback: FallbackEntrance?,
        beaconEnabled: Boolean = false,
        beaconMac: String = "",
        beaconExpireAt: Long = 0L,
        onResult: (ParkingSpot?) -> Unit
    ) {
        val appCtx = context.applicationContext
        cancel()

        val useAmap = MapProviderManager.getActiveType() == MapProviderType.AMAP && Keys.hasAmap(appCtx)
        if (!useAmap) {
            locateWithSystemFallback(appCtx, entranceFallback, beaconEnabled, beaconMac, beaconExpireAt, onResult)
            return
        }

        try {
            val client = AMapLocationClient(appCtx)
            val opt = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isWifiScan = true
                isNeedAddress = true
                isLocationCacheEnable = false
                isSensorEnable = true
                interval = 1000L
            }
            client.setLocationOption(opt)

            val startedAt = System.currentTimeMillis()
            var bestIndoor: AMapLocation? = null
            var bestAny: AMapLocation? = null
            var finished = false

            fun finishNow() {
                if (finished) return
                finished = true
                cancel()
                val chosen = bestIndoor ?: bestAny
                buildSpotFromAmap(
                    appCtx, chosen, entranceFallback,
                    beaconEnabled, beaconMac, beaconExpireAt,
                    onResult
                )
            }

            client.setLocationListener { loc ->
                if (finished || loc == null || loc.errorCode != 0) return@setLocationListener
                if (!RideTrackMath.valid(loc.latitude, loc.longitude)) return@setLocationListener

                val hasIndoor = !loc.buildingId.isNullOrBlank() && !loc.floor.isNullOrBlank()
                if (hasIndoor) {
                    if (bestIndoor == null || loc.accuracy < bestIndoor!!.accuracy) {
                        bestIndoor = loc
                    }
                    // 拿到高精度室内定位且已采集满 3 秒，提前收敛
                    if (loc.accuracy <= 25f && System.currentTimeMillis() - startedAt >= MIN_CONVERGE_MS) {
                        finishNow()
                    }
                } else {
                    if (bestAny == null || loc.accuracy < bestAny!!.accuracy) {
                        bestAny = loc
                    }
                }
            }

            activeClient = client
            val tr = Runnable { finishNow() }
            timeoutRunnable = tr
            main.postDelayed(tr, SAMPLE_TIMEOUT_MS)
            client.startLocation()
        } catch (t: Throwable) {
            Log.w(TAG, "AMap indoor locate failed, fallback to system: ${t.message}")
            cancel()
            locateWithSystemFallback(appCtx, entranceFallback, beaconEnabled, beaconMac, beaconExpireAt, onResult)
        }
    }

    private fun buildSpotFromAmap(
        context: Context,
        loc: AMapLocation?,
        entranceFallback: FallbackEntrance?,
        beaconEnabled: Boolean,
        beaconMac: String,
        beaconExpireAt: Long,
        onResult: (ParkingSpot?) -> Unit
    ) {
        val useEntrance = (loc == null || loc.accuracy > 80f) &&
            entranceFallback != null && RideTrackMath.valid(entranceFallback.lat, entranceFallback.lng)

        val lat = if (useEntrance) entranceFallback!!.lat else loc?.latitude ?: Double.NaN
        val lng = if (useEntrance) entranceFallback!!.lng else loc?.longitude ?: Double.NaN
        if (!RideTrackMath.valid(lat, lng)) {
            main.post { onResult(null) }
            return
        }

        val buildingId = loc?.buildingId?.trim().orEmpty()
        val rawFloor = loc?.floor?.trim().orEmpty()
        val baro = BaroFloorEstimator.estimateBasementFloor()

        val floorName: String
        val floorIndex: Int
        val sourceLevel: Int
        if (buildingId.isNotEmpty() && rawFloor.isNotEmpty()) {
            floorName = rawFloor
            floorIndex = parseFloorIndex(rawFloor)
            sourceLevel = 1
        } else if (baro != null) {
            floorName = baro.floorName
            floorIndex = baro.floorIndex
            sourceLevel = 2
        } else {
            floorName = ""
            floorIndex = 0
            sourceLevel = 2
        }

        val mallName = listOfNotNull(
            loc?.aoiName?.takeIf { it.isNotBlank() },
            loc?.poiName?.takeIf { it.isNotBlank() },
            entranceFallback?.mallHint?.takeIf { it.isNotBlank() },
            loc?.street?.takeIf { it.isNotBlank() }
        ).firstOrNull().orEmpty()

        val baseSpot = ParkingSpot(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            lat = lat,
            lng = lng,
            mallName = mallName,
            buildingId = buildingId,
            floor = floorName,
            floorIndex = floorIndex,
            spotCode = "",
            accuracyM = loc?.accuracy ?: -1f,
            sourceLevel = sourceLevel,
            isEntranceFallback = useEntrance,
            beaconEnabled = beaconEnabled,
            beaconMac = beaconMac,
            beaconExpireAt = beaconExpireAt
        )

        // L1 室内定位或有高德 Key 时，尝试 35m 近场 POI 提取车位区位/柱号（最多等待 3 秒）
        queryNearbySpotCode(context, lat, lng, loc?.cityCode.orEmpty(), floorName) { code, poiMall ->
            val finalSpot = baseSpot.copy(
                mallName = baseSpot.mallName.ifBlank { poiMall },
                spotCode = code
            )
            main.post { onResult(finalSpot) }
        }
    }

    private fun queryNearbySpotCode(
        context: Context,
        lat: Double,
        lng: Double,
        cityCode: String,
        floor: String,
        done: (spotCode: String, fallbackMall: String) -> Unit
    ) {
        var replied = false
        val guard = Runnable {
            if (!replied) {
                replied = true
                done("", "")
            }
        }
        main.postDelayed(guard, 3000L)

        try {
            val query = PoiSearch.Query("", "停车场|室内设施|出入口", cityCode).apply {
                pageSize = 10
                pageNum = 1
            }
            val search = PoiSearch(context, query)
            search.bound = PoiSearch.SearchBound(LatLonPoint(lat, lng), 35, true)
            search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResult?, rCode: Int) {
                    if (replied) return
                    replied = true
                    main.removeCallbacks(guard)
                    val pois = if (rCode == 1000) result?.pois.orEmpty() else emptyList()
                    var extractedCode = ""
                    var mall = ""
                    for (p in pois) {
                        val title = p.title.orEmpty()
                        if (mall.isEmpty() && title.contains("停车场")) mall = title
                        val m = spotCodeRegex.find(title)
                        if (m != null) {
                            extractedCode = m.value
                            break
                        }
                    }
                    if (extractedCode.isEmpty() && floor.isNotEmpty()) {
                        val firstIndoor = pois.firstOrNull { it.indoorData?.floorName.equals(floor, ignoreCase = true) }
                        if (firstIndoor != null) {
                            extractedCode = firstIndoor.title.orEmpty().take(16)
                        }
                    }
                    done(extractedCode, mall)
                }

                override fun onPoiItemSearched(p0: com.amap.api.services.core.PoiItem?, p1: Int) {}
            })
            search.searchPOIAsyn()
        } catch (_: Throwable) {
            if (!replied) {
                replied = true
                main.removeCallbacks(guard)
                done("", "")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun locateWithSystemFallback(
        context: Context,
        entranceFallback: FallbackEntrance?,
        beaconEnabled: Boolean,
        beaconMac: String,
        beaconExpireAt: Long,
        onResult: (ParkingSpot?) -> Unit
    ) {
        val bestLoc: Location? = runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@runCatching null
            val providers = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .minByOrNull { it.accuracy }
        }.getOrNull()

        val useEntrance = bestLoc == null && entranceFallback != null && RideTrackMath.valid(entranceFallback.lat, entranceFallback.lng)
        val lat = if (useEntrance) entranceFallback!!.lat else bestLoc?.latitude ?: Double.NaN
        val lng = if (useEntrance) entranceFallback!!.lng else bestLoc?.longitude ?: Double.NaN
        if (!RideTrackMath.valid(lat, lng)) {
            main.post { onResult(null) }
            return
        }

        val baro = BaroFloorEstimator.estimateBasementFloor()
        val spot = ParkingSpot(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            lat = lat,
            lng = lng,
            mallName = entranceFallback?.mallHint.orEmpty(),
            buildingId = "",
            floor = baro?.floorName.orEmpty(),
            floorIndex = baro?.floorIndex ?: 0,
            spotCode = "",
            accuracyM = bestLoc?.accuracy ?: -1f,
            sourceLevel = 3,
            isEntranceFallback = useEntrance,
            beaconEnabled = beaconEnabled,
            beaconMac = beaconMac,
            beaconExpireAt = beaconExpireAt
        )
        main.post { onResult(spot) }
    }

    fun parseFloorIndex(floor: String): Int {
        val s = floor.trim().uppercase()
        if (s.isEmpty()) return 0
        val num = Regex("\\d+").find(s)?.value?.toIntOrNull() ?: return 0
        return if (s.startsWith("B") || s.startsWith("约B") || s.startsWith("-")) -num else num
    }
}
