package com.example.motonav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 阶段二：停车自动判定状态机 & 主动设置协调器
 *
 * 职责：
 * 1. 监控行车状态（有效行程门槛 >= 200m 或 >= 15km/h），避免室内通电调试误触发；
 * 2. 静止 >= 120s（过滤室外等红灯）或 BLE 断开 >= 8s 时自动触发三级室内定位记车位；
 * 3. 支持手机主动设置车位与圆屏 Arrived 一键记车位（5 分钟内防断开重复覆盖）；
 * 4. 下次发车速度 > 15km/h 且驶离当前车位 > 100m 持续 10s，自动归档旧车位并发 #PKB,0。
 */
object ParkingDetector {
    private const val TAG = "ParkingDetector"

    private const val ARMED_DIST_M = 200.0
    private const val ARMED_SPEED_KMH = 15
    private const val STOP_SPEED_KMH = 2
    private const val RESUME_SPEED_KMH = 5
    private const val STATIONARY_TRIGGER_MS = 120_000L
    private const val DISCONNECT_DEBOUNCE_MS = 8_000L
    private const val MANUAL_GUARD_MS = 5 * 60 * 1000L
    private const val LEAVE_DIST_M = 100.0
    private const val LEAVE_HOLD_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null

    @Volatile var autoMemoryEnabled = true
        private set

    private var tripArmed = false
    private var tripDistM = 0.0
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    private var lastOutdoorEntrance: IndoorParkingLocator.FallbackEntrance? = null

    private var stoppedSinceMs = 0L
    private var autoParkedInCurrentStop = false
    private var lastManualSetAtMs = 0L
    private var leaveStartAtMs = 0L
    private var sampling = false

    private var disconnectRunnable: Runnable? = null

    fun init(context: Context) {
        val c = context.applicationContext
        appCtx = c
        autoMemoryEnabled = c.getSharedPreferences("motonav", Context.MODE_PRIVATE)
            .getBoolean("parking_auto_memory", true)
        BaroFloorEstimator.start(c)
    }

    fun stop() {
        disconnectRunnable?.let { main.removeCallbacks(it) }
        disconnectRunnable = null
        IndoorParkingLocator.cancel()
        BaroFloorEstimator.stop()
        sampling = false
    }

    fun setAutoMemoryEnabled(context: Context, on: Boolean) {
        autoMemoryEnabled = on
        context.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
            .edit().putBoolean("parking_auto_memory", on).apply()
    }

    /** 每次收到定位与车速更新时调用（由 NaviBridge 喂入） */
    fun onMotionUpdate(
        lat: Double,
        lng: Double,
        speedKmh: Int,
        accuracyM: Float,
        isGpsOutdoor: Boolean,
        navActive: Boolean,
        remainMeters: Int
    ) {
        val c = appCtx ?: return
        val now = SystemClock.elapsedRealtime()

        if (RideTrackMath.valid(lat, lng)) {
            if (RideTrackMath.valid(lastLat, lastLng)) {
                val step = RideTrackMath.distance(lastLat, lastLng, lat, lng)
                if (step in 1.0..500.0) tripDistM += step
            }
            lastLat = lat
            lastLng = lng
            if (isGpsOutdoor && accuracyM in 1f..20f) {
                lastOutdoorEntrance = IndoorParkingLocator.FallbackEntrance(lat, lng)
                BaroFloorEstimator.onOutdoorGpsFix(accuracyM)
            }
        }

        if (speedKmh >= ARMED_SPEED_KMH || tripDistM >= ARMED_DIST_M) {
            tripArmed = true
        }

        // 检查是否已驶离上次停放的车位 -> 自动归档
        checkAutoArchiveOnLeave(c, lat, lng, speedKmh, now)

        if (!autoMemoryEnabled || !tripArmed || sampling) return

        // 速度检测：静止 2 分钟判定
        if (speedKmh >= RESUME_SPEED_KMH) {
            stoppedSinceMs = 0L
            autoParkedInCurrentStop = false
        } else if (speedKmh <= STOP_SPEED_KMH) {
            if (stoppedSinceMs == 0L) stoppedSinceMs = now
            val stoppedDur = now - stoppedSinceMs
            val inManualGuard = lastManualSetAtMs > 0L && (now - lastManualSetAtMs < MANUAL_GUARD_MS)
            // 等红灯过滤：主动导航中、距终点 >300m 且室外 GPS 信号良好时不误触发
            val likelyRedLight = navActive && remainMeters > 300 && isGpsOutdoor && accuracyM in 1f..15f
            if (stoppedDur >= STATIONARY_TRIGGER_MS && !autoParkedInCurrentStop && !inManualGuard && !likelyRedLight) {
                autoParkedInCurrentStop = true
                Log.i(TAG, "Stationary >= 120s detected, sampling parking spot...")
                triggerAutoSample(c, releaseGraceOnDone = false)
            }
        }
    }

    /** BLE 连接状态变化回调：返回 true 表示需要 NavService 保持 20 秒后台定位宽限窗口 */
    fun onBleConnectionChanged(connected: Boolean): Boolean {
        val c = appCtx ?: return false
        disconnectRunnable?.let { main.removeCallbacks(it) }
        disconnectRunnable = null

        if (connected) {
            return false
        }

        val now = SystemClock.elapsedRealtime()
        val inManualGuard = lastManualSetAtMs > 0L && (now - lastManualSetAtMs < MANUAL_GUARD_MS)
        if (!autoMemoryEnabled || !tripArmed || inManualGuard || sampling) {
            return false
        }

        // 断开防抖 8 秒后再采样，避免蓝牙瞬断误触
        val r = Runnable {
            disconnectRunnable = null
            if (!BleLink.isConnected) {
                Log.i(TAG, "BLE disconnected >= 8s after armed trip, sampling parking spot...")
                triggerAutoSample(c, releaseGraceOnDone = true)
            } else {
                NavService.releaseParkingGraceWindow()
            }
        }
        disconnectRunnable = r
        main.postDelayed(r, DISCONNECT_DEBOUNCE_MS)
        return true
    }

    /**
     * 主动设置停车位（来自手机首页一键记车位，或圆屏 Arrived 界面 T,PK_MARK）
     */
    fun markParkingManually(
        context: Context,
        enableBeacon: Boolean,
        beaconMaxMinutes: Int = 360,
        customFloor: String = "",
        customSpotCode: String = "",
        photoPath: String = "",
        note: String = "",
        fromScreenArrived: Boolean = false,
        onSaved: ((ParkingSpot?) -> Unit)? = null
    ) {
        val c = context.applicationContext
        if (appCtx == null) init(c)
        lastManualSetAtMs = SystemClock.elapsedRealtime()
        sampling = true

        val mac = BleLink.connectedAddress()
        val maxMin = beaconMaxMinutes.coerceIn(30, 1440)
        val expireAt = if (enableBeacon) System.currentTimeMillis() + maxMin * 60_000L else 0L

        // 手机端主动发起且开启信标时，通知圆屏就绪（圆屏 Arrived 自己已经进入倒计时则不重复弹窗）
        if (enableBeacon && !fromScreenArrived && BleLink.isConnected) {
            BleLink.send("#PKB,1,$maxMin")
        }

        IndoorParkingLocator.locateOnce(
            context = c,
            entranceFallback = lastOutdoorEntrance,
            beaconEnabled = enableBeacon,
            beaconMac = mac,
            beaconExpireAt = expireAt
        ) { located ->
            sampling = false
            val base = located ?: run {
                val pos = NaviBridge.currentPosition()
                val lat = if (RideTrackMath.valid(pos[0], pos[1])) pos[0] else lastOutdoorEntrance?.lat ?: Double.NaN
                val lng = if (RideTrackMath.valid(pos[0], pos[1])) pos[1] else lastOutdoorEntrance?.lng ?: Double.NaN
                ParkingSpot(
                    id = java.util.UUID.randomUUID().toString(),
                    timestampMs = System.currentTimeMillis(),
                    lat = lat,
                    lng = lng,
                    mallName = lastOutdoorEntrance?.mallHint.orEmpty(),
                    buildingId = "",
                    floor = "",
                    floorIndex = 0,
                    spotCode = "",
                    accuracyM = -1f,
                    sourceLevel = 3,
                    beaconEnabled = enableBeacon,
                    beaconMac = mac,
                    beaconExpireAt = expireAt
                )
            }

            val finalFloor = customFloor.trim().ifBlank { base.floor }
            val finalSpot = base.copy(
                floor = finalFloor,
                floorIndex = if (customFloor.isNotBlank()) IndoorParkingLocator.parseFloorIndex(customFloor) else base.floorIndex,
                spotCode = customSpotCode.trim().ifBlank { base.spotCode },
                photoPath = photoPath.ifBlank { base.photoPath },
                note = note.trim().ifBlank { base.note }
            )
            ParkingStore.saveCurrent(c, finalSpot)
            ParkingNotifier.showParkedNotification(c, finalSpot)
            resetTripSession()
            onSaved?.invoke(finalSpot)
        }
    }

    /** 圆屏在 5 秒倒计时内取消信标时调用 */
    fun cancelScreenBeaconMark(context: Context) {
        val c = context.applicationContext
        ParkingStore.updateCurrent(c) { it.copy(beaconEnabled = false, beaconExpireAt = 0L) }
    }

    private fun triggerAutoSample(context: Context, releaseGraceOnDone: Boolean) {
        sampling = true
        val mac = BleLink.connectedAddress()
        IndoorParkingLocator.locateOnce(
            context = context,
            entranceFallback = lastOutdoorEntrance,
            beaconEnabled = false,
            beaconMac = mac,
            beaconExpireAt = 0L
        ) { spot ->
            sampling = false
            if (spot != null) {
                ParkingStore.saveCurrent(context, spot)
                ParkingNotifier.showParkedNotification(context, spot)
                resetTripSession()
            }
            if (releaseGraceOnDone) {
                NavService.releaseParkingGraceWindow()
            }
        }
    }

    private fun checkAutoArchiveOnLeave(c: Context, lat: Double, lng: Double, speedKmh: Int, now: Long) {
        val cur = ParkingStore.current(c) ?: run {
            leaveStartAtMs = 0L
            return
        }
        if (!RideTrackMath.valid(lat, lng) || !RideTrackMath.valid(cur.lat, cur.lng)) return
        val dist = RideTrackMath.distance(lat, lng, cur.lat, cur.lng)
        if (speedKmh >= ARMED_SPEED_KMH && dist >= LEAVE_DIST_M) {
            if (leaveStartAtMs == 0L) leaveStartAtMs = now
            if (now - leaveStartAtMs >= LEAVE_HOLD_MS) {
                leaveStartAtMs = 0L
                ParkingStore.archiveCurrent(c)
                ParkingNotifier.cancel(c)
                if (BleLink.isConnected) BleLink.send("#PKB,0")
                Log.i(TAG, "Vehicle left parking spot (>100m), archived automatically.")
            }
        } else {
            leaveStartAtMs = 0L
        }
    }

    private fun resetTripSession() {
        tripArmed = false
        tripDistM = 0.0
        stoppedSinceMs = 0L
    }
}
