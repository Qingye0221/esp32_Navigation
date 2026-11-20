package com.example.motonav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 气压计辅助楼层估算器（无室内图地库兜底）
 *
 * 原理：在室外 GPS 有效时持续记录平滑参考气压 P0；
 * 进入地下车库后读取当前气压 P1，按国际标准气压高度公式计算相对海拔差 Δh，
 * 当 Δh <= -2.2m 时按标准地库层高 3.6m 推算地下楼层（约 B1 / B2 / B3）。
 */
object BaroFloorEstimator {
    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null

    @Volatile private var currentPressureHpa = Float.NaN
    @Volatile private var outdoorRefPressureHpa = Float.NaN
    @Volatile private var outdoorRefAtMs = 0L

    private const val OUTDOOR_REF_MAX_AGE_MS = 15 * 60 * 1000L // 15 分钟内有效
    private const val FLOOR_HEIGHT_M = 3.6

    fun start(context: Context) {
        if (listener != null) return
        val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val pressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val p = event.values.firstOrNull() ?: return
                if (p < 300f || p > 1200f) return
                currentPressureHpa = if (currentPressureHpa.isNaN()) p else (currentPressureHpa * 0.8f + p * 0.2f)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sm.registerListener(l, pressure, SensorManager.SENSOR_DELAY_NORMAL)) {
            sensorManager = sm
            listener = l
        }
    }

    fun stop() {
        listener?.let { runCatching { sensorManager?.unregisterListener(it) } }
        listener = null
        sensorManager = null
    }

    /** 室外高精度 GPS 有效时调用，刷新地面参考气压 P0 */
    fun onOutdoorGpsFix(accuracyM: Float) {
        if (accuracyM <= 0f || accuracyM > 20f) return
        val p = currentPressureHpa
        if (p.isNaN()) return
        outdoorRefPressureHpa = if (outdoorRefPressureHpa.isNaN()) p else (outdoorRefPressureHpa * 0.85f + p * 0.15f)
        outdoorRefAtMs = SystemClock.elapsedRealtime()
    }

    data class FloorEstimate(val floorName: String, val floorIndex: Int, val deltaMeters: Double)

    /** 估算当前相对地面的地下楼层；无气压计或参考气压过期时返回 null */
    fun estimateBasementFloor(): FloorEstimate? {
        val p0 = outdoorRefPressureHpa
        val p1 = currentPressureHpa
        if (p0.isNaN() || p1.isNaN()) return null
        if (SystemClock.elapsedRealtime() - outdoorRefAtMs > OUTDOOR_REF_MAX_AGE_MS) return null
        return computeFromPressures(p0.toDouble(), p1.toDouble())
    }

    /** 纯函数便于单元测试 */
    fun computeFromPressures(p0Hpa: Double, p1Hpa: Double): FloorEstimate? {
        if (p0Hpa <= 0.0 || p1Hpa <= 0.0) return null
        val deltaH = 44330.0 * (1.0 - (p1Hpa / p0Hpa).pow(1.0 / 5.255))
        if (deltaH > -2.2) return null
        val depth = (-deltaH / FLOOR_HEIGHT_M).roundToInt().coerceIn(1, 6)
        return FloorEstimate("约B$depth", -depth, deltaH)
    }
}
