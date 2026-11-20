package com.example.motonav

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.ArrayDeque

/**
 * 阶段二：蓝牙 RSSI 近场寻车雷达（解决地库最后 30 米精准定位）
 *
 * 双模式采集：
 * - 未连接阶段（8~35m）：BluetoothLeScanner 按圆屏 MAC 或 SERVICE_UUID 过滤扫描 RSSI
 * - 已连接阶段（0~15m）：定时调用 BleLink.requestRemoteRssi() 读取实时 RSSI，并支持 #PKB,PING 呼叫爱车
 *
 * 滤波策略：5 帧滑动窗口中值滤波 + EMA(α=0.25) 平滑 + 3 秒冷热趋势判定
 */
class BleCarFinder(
    private val context: Context,
    private val targetMac: String,
    private val onUpdate: (State) -> Unit
) {
    enum class Proximity { NONE, FAR, MEDIUM, NEAR, VERY_NEAR }
    enum class Trend { STEADY, CLOSER, FARTHER }

    data class State(
        val detected: Boolean = false,
        val connected: Boolean = false,
        val smoothRssi: Int = -100,
        val proximity: Proximity = Proximity.NONE,
        val trend: Trend = Trend.STEADY,
        val label: String = "正在搜索爱车蓝牙信号…"
    )

    private val main = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var running = false

    private val medianWindow = ArrayDeque<Int>(5)
    private var smoothRssi = Float.NaN
    private var lastSampleAtMs = 0L

    private data class RssiPoint(val atMs: Long, val rssi: Float)
    private val trendHistory = ArrayDeque<RssiPoint>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = runCatching { result.device?.address }.getOrNull().orEmpty()
            if (targetMac.isNotBlank() && !addr.equals(targetMac, ignoreCase = true)) return
            feedRawRssi(result.rssi, connected = false)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (BleLink.isConnected) {
                BleLink.requestRemoteRssi()
            }
            val now = SystemClock.elapsedRealtime()
            if (lastSampleAtMs > 0L && now - lastSampleAtMs > 4000L && !BleLink.isConnected) {
                smoothRssi = Float.NaN
                medianWindow.clear()
                trendHistory.clear()
                onUpdate(State(detected = false, connected = false, label = "暂未检测到爱车蓝牙，请靠近目标楼层"))
            }
            main.postDelayed(this, 600L)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        smoothRssi = Float.NaN
        medianWindow.clear()
        trendHistory.clear()
        lastSampleAtMs = 0L

        BleLink.onRssiRead = { rssi ->
            if (running) feedRawRssi(rssi, connected = true)
        }

        val adapter = (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter != null && adapter.isEnabled) {
            runCatching {
                scanner = adapter.bluetoothLeScanner
                val filters = if (targetMac.isNotBlank()) {
                    listOf(ScanFilter.Builder().setDeviceAddress(targetMac).build())
                } else {
                    listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleLink.serviceUuid())).build())
                }
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner?.startScan(filters, settings, scanCallback)
            }
        }
        main.post(tick)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        BleLink.onRssiRead = null
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
    }

    /** 呼叫爱车：向已连接的圆屏发送 #PKB,PING 触发最高亮度闪烁 + 马达震动 */
    fun pingVehicle(): Boolean {
        if (!BleLink.isConnected) return false
        BleLink.send("#PKB,PING")
        return true
    }

    private fun feedRawRssi(raw: Int, connected: Boolean) {
        if (raw >= 0 || raw < -115) return
        val now = SystemClock.elapsedRealtime()
        lastSampleAtMs = now

        if (medianWindow.size >= 5) medianWindow.removeFirst()
        medianWindow.addLast(raw)
        val sorted = medianWindow.sorted()
        val median = sorted[sorted.size / 2].toFloat()

        smoothRssi = if (smoothRssi.isNaN()) median else (smoothRssi * 0.75f + median * 0.25f)
        val rssiInt = smoothRssi.toInt()

        trendHistory.addLast(RssiPoint(now, smoothRssi))
        while (trendHistory.isNotEmpty() && now - trendHistory.first().atMs > 3500L) {
            trendHistory.removeFirst()
        }
        val old = trendHistory.firstOrNull()
        val trend = if (old != null && now - old.atMs >= 1500L) {
            val delta = smoothRssi - old.rssi
            when {
                delta >= 2.8f -> Trend.CLOSER
                delta <= -2.8f -> Trend.FARTHER
                else -> Trend.STEADY
            }
        } else Trend.STEADY

        val (prox, label) = when {
            rssiInt >= -58 -> Proximity.VERY_NEAR to "极近（< 2m · 就在身边！）"
            rssiInt >= -70 -> Proximity.NEAR to "很近（约 2~6m · 就在附近）"
            rssiInt >= -82 -> Proximity.MEDIUM to "中距（约 6~15m · 沿信号增强方向走）"
            else -> Proximity.FAR to "已发现爱车信号（> 15m）"
        }

        main.post {
            if (running) {
                onUpdate(
                    State(
                        detected = true,
                        connected = connected || BleLink.isConnected,
                        smoothRssi = rssiInt,
                        proximity = prox,
                        trend = trend,
                        label = label
                    )
                )
            }
        }
    }
}
