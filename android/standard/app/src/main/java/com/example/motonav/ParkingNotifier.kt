package com.example.motonav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/**
 * 阶段二：停车成功通知卡片
 * 支持：
 * 1. 点击直接进入 FindMyCarActivity 反向寻车
 * 2. 通知栏 RemoteInput 免打开 App 直接填写车位号（如 "B2-D108" 或 "D-108"）
 */
object ParkingNotifier {
    private const val CHANNEL_ID = "motonav_parking"
    private const val NOTIFY_ID = 2042
    const val ACTION_QUICK_REPLY = "com.example.motonav.ACTION_PARKING_REPLY"
    const val KEY_SPOT_TEXT = "key_parking_spot_text"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "停车位记忆提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "自动或手动记住地下车库停车位后的提醒与车位号补录"
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun showParkedNotification(context: Context, spot: ParkingSpot) {
        val c = context.applicationContext
        ensureChannel(c)

        val openIntent = Intent(c, FindMyCarActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            c, 201, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remoteInput = RemoteInput.Builder(KEY_SPOT_TEXT)
            .setLabel("输入车位号（如 D-108 或 B2-D108）")
            .build()

        val replyIntent = Intent(c, ParkingReplyReceiver::class.java).apply {
            action = ACTION_QUICK_REPLY
        }
        val replyFlags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPi = PendingIntent.getBroadcast(c, 202, replyIntent, replyFlags)
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit,
            if (spot.spotCode.isBlank()) "补充车位号" else "修改车位号",
            replyPi
        ).addRemoteInput(remoteInput).build()

        val title = "已记住停车位 · ${spot.shortTitle()}"
        val text = buildString {
            if (spot.isBeaconActiveNow()) append("圆屏蓝牙寻车信标已开启 · ")
            if (spot.spotCode.isBlank()) append("下拉可直接填写车位号，点此打开寻车")
            else append("点击查看室内地图与蓝牙寻车雷达")
        }

        val notif = NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .addAction(replyAction)
            .setAutoCancel(false)
            .build()

        runCatching {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFY_ID, notif)
        }
    }

    fun cancel(context: Context) {
        runCatching {
            val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFY_ID)
        }
    }
}

class ParkingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ParkingNotifier.ACTION_QUICK_REPLY) return
        val input = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(ParkingNotifier.KEY_SPOT_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (input.isEmpty()) return

        val updated = ParkingStore.updateCurrent(context) { cur ->
            // 若用户输入了 "B2-D108"，智能拆分楼层与车位号
            val m = Regex("^(B\\d+|F\\d+|L\\d+)[-\\s]+(.+)$", RegexOption.IGNORE_CASE).matchEntire(input)
            if (m != null) {
                val fl = m.groupValues[1].uppercase()
                val sp = m.groupValues[2].trim()
                cur.copy(floor = fl, floorIndex = IndoorParkingLocator.parseFloorIndex(fl), spotCode = sp)
            } else {
                cur.copy(spotCode = input)
            }
        }
        if (updated != null) {
            ParkingNotifier.showParkedNotification(context, updated)
        }
    }
}
