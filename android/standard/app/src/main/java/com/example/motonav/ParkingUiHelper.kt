package com.example.motonav

import android.app.Activity
import android.content.Context
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阶段二：手机端「主动记住当前停车位」与「历史停车记录」弹窗工具
 */
object ParkingUiHelper {

    fun showMarkParkingDialog(activity: Activity, onSaved: () -> Unit = {}) {
        val pad = (18 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 3)
        }

        val etFloor = EditText(activity).apply {
            hint = "楼层（留空自动室内定位，如 B2 / B1）"
            setSingleLine()
        }
        val etSpot = EditText(activity).apply {
            hint = "车位编号/区域（可选，如 D-108、近3号梯）"
            setSingleLine()
        }
        val cbBeacon = CheckBox(activity).apply {
            text = "离车后保持圆屏超低功耗蓝牙开启（辅助寻车）"
            isChecked = BleLink.isConnected
            isEnabled = BleLink.isConnected
        }
        val tvBeaconHint = TextView(activity).apply {
            text = if (BleLink.isConnected) "断连后圆屏将自动息屏降频、低频广播，超时或电量 ≤15% 自动关机"
            else "当前未连接圆屏蓝牙，仅在手机端记录停车位"
            textSize = 12f
        }

        val rgDuration = RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            val opts = listOf(120 to "2小时", 360 to "6小时(推荐)", 720 to "12小时")
            opts.forEach { (mins, lbl) ->
                val rb = RadioButton(activity).apply {
                    id = mins
                    text = lbl
                    isChecked = mins == 360
                    isEnabled = BleLink.isConnected
                }
                addView(rb)
            }
        }
        cbBeacon.setOnCheckedChangeListener { _, checked ->
            for (i in 0 until rgDuration.childCount) rgDuration.getChildAt(i).isEnabled = checked
        }

        box.addView(etFloor)
        box.addView(etSpot)
        box.addView(cbBeacon)
        box.addView(tvBeaconHint)
        box.addView(rgDuration)

        AlertDialog.Builder(activity)
            .setTitle("记住当前停车位")
            .setView(box)
            .setPositiveButton("立即记住") { _, _ ->
                val enableBeacon = cbBeacon.isChecked && BleLink.isConnected
                val maxMin = rgDuration.checkedRadioButtonId.takeIf { it > 0 } ?: 360
                Toast.makeText(activity, "正在采样室内定位并记录停车位…", Toast.LENGTH_SHORT).show()
                ParkingDetector.markParkingManually(
                    context = activity,
                    enableBeacon = enableBeacon,
                    beaconMaxMinutes = maxMin,
                    customFloor = etFloor.text.toString(),
                    customSpotCode = etSpot.text.toString()
                ) { spot ->
                    if (spot != null) {
                        val tip = if (enableBeacon) "已记住：${spot.shortTitle()}（圆屏蓝牙信标已就绪）"
                        else "已记住：${spot.shortTitle()}"
                        Toast.makeText(activity, tip, Toast.LENGTH_LONG).show()
                        onSaved()
                    } else {
                        Toast.makeText(activity, "记录停车位失败，请检查定位权限", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showParkingHistoryDialog(activity: Activity, onChanged: () -> Unit = {}) {
        val hist = ParkingStore.history(activity)
        if (hist.isEmpty()) {
            Toast.makeText(activity, "暂无历史停车记录", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val labels = hist.map { "${fmt.format(Date(it.timestampMs))}  ${it.shortTitle()}" }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("历史停车记录（${hist.size}/20）")
            .setItems(labels) { _, idx ->
                val item = hist[idx]
                AlertDialog.Builder(activity)
                    .setTitle(item.shortTitle())
                    .setItems(arrayOf("设为当前车位并去寻车", "删除此记录")) { _, act ->
                        if (act == 0) {
                            ParkingStore.saveCurrent(activity, item)
                            onChanged()
                            FindMyCarActivity.open(activity)
                        } else {
                            ParkingStore.removeHistory(activity, item.id)
                            onChanged()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
