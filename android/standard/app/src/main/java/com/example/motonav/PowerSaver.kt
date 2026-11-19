package com.example.motonav

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * 后台休眠省电管理器：
 * 1. 当手机锁屏（屏幕熄灭）且未连接圆屏蓝牙时，立刻进入深度休眠省电；
 * 2. 当 App 退到后台（屏幕仍亮但切出）且未连接圆屏蓝牙时，启动 30 秒防抖倒计时；倒计时结束若未连接且仍在后台，进入深度休眠；
 * 3. 深度休眠时彻底停止后台常驻服务、停止高德后台定位、停止蓝牙循环扫描，将后台 CPU 与硬件功耗降为 0；
 * 4. 一旦与圆屏建立蓝牙连接（如骑行出发），绝不休眠，持续保持导航与音乐连接；
 * 5. 用户再次点开 App 回到前台时，秒级自动唤醒并恢复后台服务与蓝牙扫描。
 */
object PowerSaver : Application.ActivityLifecycleCallbacks {
    private const val SLEEP_DELAY_MS = 30 * 1000L // 30 秒后台未连接防抖
    private val handler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    private var startedCount = 0
    private var appContext: Context? = null

    @Volatile var isForeground = false
        private set
    @Volatile var isScreenOff = false
        private set
    @Volatile var isSleeping = false
        private set

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOff = true
                    checkPowerState("Screen turned off")
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOff = false
                    checkPowerState("Screen turned on")
                }
            }
        }
    }

    fun init(app: Application) {
        if (appContext != null) return
        appContext = app
        app.registerActivityLifecycleCallbacks(this)

        // 注册屏幕开关广播监听
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        app.registerReceiver(screenReceiver, filter)

        // 检查当前屏幕初始状态
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenOff = pm?.isInteractive == false

        BleLink.onConnectionChanged = { connected ->
            NavService.updateBackgroundLocation(connected)
            checkPowerState("BLE connection changed: connected=$connected")
        }
    }

    override fun onActivityStarted(activity: Activity) {
        startedCount++
        if (startedCount == 1) {
            isForeground = true
            checkPowerState("App entered foreground")
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount--
        if (startedCount <= 0) {
            startedCount = 0
            isForeground = false
            checkPowerState("App entered background")
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    fun checkPowerState(reason: String) {
        val ctx = appContext ?: return
        val connected = BleLink.isConnected

        android.util.Log.d("esp32NavPower", "checkPowerState: fg=$isForeground, screenOff=$isScreenOff, connected=$connected, sleeping=$isSleeping, reason=$reason")

        if (connected || isForeground) {
            // 只要蓝牙连接着（保持音乐与导航），或处于前台操作，绝不休眠！
            cancelSleep()
            if (isSleeping && isForeground) {
                // 用户打开 App 回到前台，立即自动唤醒！
                wakeUp(ctx)
            }
        } else {
            // 在后台且未连接蓝牙：
            if (isScreenOff) {
                // 屏幕已熄灭（用户锁屏）：立即进入深度休眠，无需等待
                cancelSleep()
                if (!isSleeping) {
                    enterSleep(ctx)
                }
            } else {
                // 屏幕还亮着（切到其他应用）：开始 30 秒防抖倒计时
                scheduleSleep(ctx)
            }
        }
    }

    private fun scheduleSleep(ctx: Context) {
        if (sleepRunnable != null || isSleeping) return
        android.util.Log.i("esp32NavPower", "App backgrounded without BLE: scheduled to sleep in 30s")
        val r = Runnable {
            if (!isForeground && !BleLink.isConnected) {
                enterSleep(ctx)
            }
            sleepRunnable = null
        }
        sleepRunnable = r
        handler.postDelayed(r, SLEEP_DELAY_MS)
    }

    private fun cancelSleep() {
        sleepRunnable?.let {
            handler.removeCallbacks(it)
            sleepRunnable = null
            android.util.Log.i("esp32NavPower", "Sleep timer cancelled")
        }
    }

    private fun enterSleep(ctx: Context) {
        isSleeping = true
        android.util.Log.i("esp32NavPower", "Entering sleep mode: stopping background service & BLE auto-scan")
        NavService.stop(ctx)
    }

    private fun wakeUp(ctx: Context) {
        isSleeping = false
        android.util.Log.i("esp32NavPower", "Waking up from sleep mode: restarting NavService & BLE auto-scan")
        NavService.start(ctx)
    }
}
