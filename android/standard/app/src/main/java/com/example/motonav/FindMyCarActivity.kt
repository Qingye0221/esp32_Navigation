package com.example.motonav

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.BusRouteResult
import com.amap.api.services.route.DriveRouteResult
import com.amap.api.services.route.RideRouteResult
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.WalkRouteResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 阶段二：地库反向寻车页
 * - 上半区：高德 3D 室内地图（自动切换停车楼层 + 步行路径规划 + 跨层电梯提示），非高德引擎安全回退为指南针雷达
 * - 下半区：最后 30 米蓝牙 RSSI 近场寻车雷达 + 一键呼叫爱车（#PKB,PING 圆屏亮屏震动）+ 车位号编辑/柱子照片查看
 */
class FindMyCarActivity : AppCompatActivity() {

    private var spot: ParkingSpot? = null
    private var mapView: MapView? = null
    private var aMap: AMap? = null
    private var walkPolyline: Polyline? = null
    private var locClient: AMapLocationClient? = null
    private var hasAutoSwitchedFloor = false
    private var lastWalkPlanAt = 0L

    private var curLat = Double.NaN
    private var curLng = Double.NaN
    private var curFloor = ""
    private var phoneAzimuthDeg = 0f

    private var bleFinder: BleCarFinder? = null
    private var sensorManager: SensorManager? = null
    private var rotListener: SensorEventListener? = null

    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvFloorTip: TextView
    private lateinit var tvRadarStatus: TextView
    private lateinit var tvRadarTrend: TextView
    private lateinit var tvRadarRssi: TextView
    private lateinit var tvCompassFallback: TextView
    private lateinit var btnPingCar: Button
    private lateinit var ivPhotoPreview: ImageView

    private var pendingPhotoFile: File? = null
    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhotoFile
        if (ok && f != null && f.exists()) {
            val updated = ParkingStore.updateCurrent(this) { it.copy(photoPath = f.absolutePath) }
            if (updated != null) {
                spot = updated
                refreshHeaderAndPhoto()
                Toast.makeText(this, "已保存车位柱子照片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spot = ParkingStore.current(this)
        if (spot == null) {
            Toast.makeText(this, "当前没有保存的停车位", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        buildUi(savedInstanceState)
        refreshHeaderAndPhoto()
        startCompassSensor()
        startBleRadar()
        startIndoorLocationTracking()
    }

    private fun buildUi(savedInstanceState: Bundle?) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F17"))
        }

        // 顶部标题栏
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#111827"))
        }
        val btnBack = TextView(this).apply {
            text = "← 返回"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 15f
            setPadding(dp(6), dp(6), dp(12), dp(6))
            setOnClickListener { finish() }
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        tvSub = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
        }
        titleCol.addView(tvTitle)
        titleCol.addView(tvSub)
        header.addView(btnBack)
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        // 跨层/步行距离提示条
        tvFloorTip = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#1E3A5F"))
            setTextColor(Color.parseColor("#E0F2FE"))
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            text = "正在获取室内定位与步行指引…"
        }
        root.addView(tvFloorTip)

        // 上半区：高德室内地图（或开源引擎回退指南针）
        val mapContainer = FrameLayout(this)
        val useAmap = MapProviderManager.getActiveType() == MapProviderType.AMAP && Keys.hasAmap(this)
        if (useAmap) {
            try {
                val mv = MapView(this)
                mv.onCreate(savedInstanceState)
                mapView = mv
                aMap = mv.map
                setupAmapIndoor(mv.map)
                mapContainer.addView(mv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } catch (_: Throwable) {
                mapView = null
            }
        }

        tvCompassFallback = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 16f
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.parseColor("#0F172A"))
            visibility = if (mapView == null) View.VISIBLE else View.GONE
            text = "指南针直线寻车模式\n（当前未启用高德室内地图）"
        }
        mapContainer.addView(tvCompassFallback, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(mapContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.15f))

        // 下半区：蓝牙 RSSI 最后 30 米雷达 + 快捷操作
        val bottomScroll = ScrollView(this)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(18))
        }

        // 蓝牙近场寻车雷达卡片
        val radarCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBg("#132035", "#2563EB")
        }
        val radarTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvRadarTitle = TextView(this).apply {
            text = "蓝牙近场寻车雷达（最后 30m）"
            setTextColor(Color.parseColor("#93C5FD"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        tvRadarRssi = TextView(this).apply {
            text = "-- dBm"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 12f
        }
        radarTitleRow.addView(tvRadarTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        radarTitleRow.addView(tvRadarRssi)
        radarCard.addView(radarTitleRow)

        tvRadarStatus = TextView(this).apply {
            text = "正在扫描爱车圆屏蓝牙信号…"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        }
        tvRadarTrend = TextView(this).apply {
            text = "提示：靠近爱车所在楼层时，信号会自动增强"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        radarCard.addView(tvRadarStatus)
        radarCard.addView(tvRadarTrend)

        btnPingCar = Button(this).apply {
            text = "呼叫爱车（圆屏亮屏闪烁 + 震动）"
            setTextColor(Color.WHITE)
            background = cardBg("#0284C7", "#38BDF8")
            isEnabled = BleLink.isConnected
            alpha = if (BleLink.isConnected) 1f else 0.45f
            setOnClickListener {
                if (bleFinder?.pingVehicle() == true) {
                    Toast.makeText(this@FindMyCarActivity, "已呼叫爱车：圆屏正在闪烁并震动！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FindMyCarActivity, "尚未连上圆屏蓝牙，请再靠近一点", Toast.LENGTH_SHORT).show()
                }
            }
        }
        radarCard.addView(btnPingCar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        bottom.addView(radarCard)

        // 柱子照片预览（有照片时显示）
        ivPhotoPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            setOnClickListener { showPhotoDialog() }
        }
        val photoLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)).apply {
            topMargin = dp(10)
        }
        bottom.addView(ivPhotoPreview, photoLp)

        // 快捷操作行 1：高德 App 寻车 / 改车位号 / 拍柱子照
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row1.addView(makeActionBtn("高德App导航", "#1E293B") { openExternalMap() }, weightLp(0, dp(6)))
        row1.addView(makeActionBtn("改车位/楼层", "#1E293B") { showEditSpotDialog() }, weightLp(dp(3), dp(3)))
        row1.addView(makeActionBtn("拍柱子照片", "#1E293B") { captureParkingPhoto() }, weightLp(dp(6), 0))
        bottom.addView(row1)

        // 快捷操作行 2：已找到爱车（归档并关闭信标）
        val btnFound = makeActionBtn("已找到爱车（结束本次停车并关闭信标）", "#065F46") {
            if (BleLink.isConnected) BleLink.send("#PKB,0")
            ParkingStore.archiveCurrent(this)
            ParkingNotifier.cancel(this)
            Toast.makeText(this, "已归档本次停车记录", Toast.LENGTH_SHORT).show()
            finish()
        }
        val foundLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(10)
        }
        bottom.addView(btnFound, foundLp)

        bottomScroll.addView(bottom)
        root.addView(bottomScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f))
        setContentView(root)
    }

    private fun setupAmapIndoor(map: AMap) {
        val s = spot ?: return
        map.showIndoorMap(true)
        map.uiSettings.isIndoorSwitchEnabled = true
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = true

        val myStyle = MyLocationStyle().apply {
            myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
            interval(2000L)
        }
        map.myLocationStyle = myStyle
        map.isMyLocationEnabled = true

        if (RideTrackMath.valid(s.lat, s.lng)) {
            val pos = LatLng(s.lat, s.lng)
            map.addMarker(
                MarkerOptions()
                    .position(pos)
                    .title("我的停车位：${s.floorAndSpot()}")
                    .snippet(s.mallName.ifBlank { "点击查看详情" })
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )?.showInfoWindow()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 18.5f))
        }

        // 自动切换到停车楼层
        map.setOnIndoorBuildingActiveListener { info ->
            if (info != null && !hasAutoSwitchedFloor) {
                val matchBuilding = s.buildingId.isBlank() || info.poiid == s.buildingId
                if (matchBuilding && (s.floorIndex != 0 || s.floor.isNotBlank())) {
                    hasAutoSwitchedFloor = true
                    if (s.floorIndex != 0) info.activeFloorIndex = s.floorIndex
                    if (s.floor.isNotBlank()) info.activeFloorName = s.floor
                    runCatching { map.setIndoorBuildingInfo(info) }
                }
            }
        }
    }

    private fun startIndoorLocationTracking() {
        val useAmap = MapProviderManager.getActiveType() == MapProviderType.AMAP && Keys.hasAmap(this)
        if (!useAmap) {
            updateFloorAndCompassTip()
            return
        }
        try {
            val client = AMapLocationClient(applicationContext)
            val opt = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isWifiScan = true
                isSensorEnable = true
                interval = 2000L
            }
            client.setLocationOption(opt)
            client.setLocationListener { loc: AMapLocation? ->
                if (loc == null || loc.errorCode != 0) return@setLocationListener
                if (!RideTrackMath.valid(loc.latitude, loc.longitude)) return@setLocationListener
                curLat = loc.latitude
                curLng = loc.longitude
                curFloor = loc.floor?.trim().orEmpty()
                updateFloorAndCompassTip()
                maybePlanWalkRoute()
            }
            locClient = client
            client.startLocation()
        } catch (_: Throwable) {
            updateFloorAndCompassTip()
        }
    }

    private fun maybePlanWalkRoute() {
        val s = spot ?: return
        val map = aMap ?: return
        if (!RideTrackMath.valid(curLat, curLng) || !RideTrackMath.valid(s.lat, s.lng)) return
        val now = System.currentTimeMillis()
        if (now - lastWalkPlanAt < 8000L) return
        lastWalkPlanAt = now

        runCatching {
            val rs = RouteSearch(applicationContext)
            rs.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                override fun onWalkRouteSearched(res: WalkRouteResult?, code: Int) {
                    val path = if (code == 1000) res?.paths?.firstOrNull() else null
                    if (path == null) return
                    val pts = mutableListOf<LatLng>()
                    path.steps?.forEach { st ->
                        st.polyline?.forEach { p -> pts += LatLng(p.latitude, p.longitude) }
                    }
                    if (pts.size >= 2) {
                        runOnUiThread {
                            walkPolyline?.remove()
                            walkPolyline = map.addPolyline(
                                PolylineOptions()
                                    .addAll(pts)
                                    .width(12f)
                                    .color(Color.parseColor("#38BDF8"))
                                    .setDottedLine(true)
                            )
                            val mins = (path.duration / 60.0).roundToInt().coerceAtLeast(1)
                            val distM = path.distance.roundToInt()
                            updateFloorAndCompassTip(walkInfo = "步行约 ${distM}m（${mins} 分钟）")
                        }
                    }
                }
                override fun onBusRouteSearched(p0: BusRouteResult?, p1: Int) {}
                override fun onDriveRouteSearched(p0: DriveRouteResult?, p1: Int) {}
                override fun onRideRouteSearched(p0: RideRouteResult?, p1: Int) {}
            })
            val query = RouteSearch.WalkRouteQuery(
                RouteSearch.FromAndTo(LatLonPoint(curLat, curLng), LatLonPoint(s.lat, s.lng))
            )
            rs.calculateWalkRouteAsyn(query)
        }
    }

    private fun updateFloorAndCompassTip(walkInfo: String = "") {
        val s = spot ?: return
        val distText = if (RideTrackMath.valid(curLat, curLng) && RideTrackMath.valid(s.lat, s.lng)) {
            val d = RideTrackMath.distance(curLat, curLng, s.lat, s.lng).roundToInt()
            if (walkInfo.isNotBlank()) walkInfo else "直线距离约 ${d}m"
        } else {
            walkInfo.ifBlank { "等待当前位置定位…" }
        }

        val floorHint = when {
            curFloor.isNotBlank() && s.floor.isNotBlank() && !curFloor.equals(s.floor, ignoreCase = true) ->
                "您在 $curFloor 层 → 车辆在 ${s.floor} 层（请先乘电梯换层）"
            s.floor.isNotBlank() ->
                "目标楼层：${s.floor}${if (s.spotCode.isNotBlank()) " · ${s.spotCode}" else ""}"
            else -> "目标车位位置"
        }
        tvFloorTip.text = "$floorHint  |  $distText"

        if (tvCompassFallback.visibility == View.VISIBLE && RideTrackMath.valid(curLat, curLng) && RideTrackMath.valid(s.lat, s.lng)) {
            val brg = bearingDegrees(curLat, curLng, s.lat, s.lng)
            val rel = ((brg - phoneAzimuthDeg + 360f) % 360f).roundToInt()
            val dirWord = when (rel) {
                in 0..22, in 338..360 -> "↑ 正前方"
                in 23..67 -> "↗ 右前方"
                in 68..112 -> "→ 右侧"
                in 113..157 -> "↘ 右后方"
                in 158..202 -> "↓ 正后方"
                in 203..247 -> "↙ 左后方"
                in 248..292 -> "← 左侧"
                else -> "↖ 左前方"
            }
            val d = RideTrackMath.distance(curLat, curLng, s.lat, s.lng).roundToInt()
            tvCompassFallback.text = "$dirWord\n直线约 ${d} m\n${s.floorAndSpot()}"
        }
    }

    private fun startCompassSensor() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rot = FloatArray(9)
                val ori = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                SensorManager.getOrientation(rot, ori)
                phoneAzimuthDeg = ((Math.toDegrees(ori[0].toDouble()).toFloat()) + 360f) % 360f
                if (tvCompassFallback.visibility == View.VISIBLE) updateFloorAndCompassTip()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)) {
            sensorManager = sm
            rotListener = l
        }
    }

    private fun startBleRadar() {
        val s = spot ?: return
        val mac = s.beaconMac.ifBlank { BleLink.connectedAddress() }
        bleFinder = BleCarFinder(this, mac) { st ->
            tvRadarStatus.text = st.label
            tvRadarRssi.text = if (st.detected) "${st.smoothRssi} dBm" else "-- dBm"
            tvRadarRssi.setTextColor(
                when (st.proximity) {
                    BleCarFinder.Proximity.VERY_NEAR -> Color.parseColor("#4ADE80")
                    BleCarFinder.Proximity.NEAR -> Color.parseColor("#38BDF8")
                    BleCarFinder.Proximity.MEDIUM -> Color.parseColor("#60A5FA")
                    else -> Color.parseColor("#94A3B8")
                }
            )
            tvRadarTrend.text = when {
                !st.detected -> if (s.isBeaconActiveNow()) "蓝牙信标已开启 · 走近至 30m 内将自动捕获信号" else "若圆屏已开启蓝牙或正在供电，走近后将自动检测信号"
                st.trend == BleCarFinder.Trend.CLOSER -> "信号增强：正在靠近爱车 ↑"
                st.trend == BleCarFinder.Trend.FARTHER -> "信号减弱：方向可能偏了，正在远离 ↓"
                else -> if (st.connected) "已连上圆屏蓝牙！可点击下方按钮呼叫爱车" else "信号稳定，继续沿方向寻找"
            }
            tvRadarTrend.setTextColor(
                when (st.trend) {
                    BleCarFinder.Trend.CLOSER -> Color.parseColor("#4ADE80")
                    BleCarFinder.Trend.FARTHER -> Color.parseColor("#FBBF24")
                    else -> Color.parseColor("#94A3B8")
                }
            )
            val canPing = st.connected || BleLink.isConnected
            btnPingCar.isEnabled = canPing
            btnPingCar.alpha = if (canPing) 1f else 0.45f
        }.also { it.start() }
    }

    private fun refreshHeaderAndPhoto() {
        val s = spot ?: return
        tvTitle.text = s.shortTitle()
        val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(s.timestampMs))
        val mins = ((System.currentTimeMillis() - s.timestampMs) / 60_000L).coerceAtLeast(0)
        val beaconTag = if (s.isBeaconActiveNow()) " · 蓝牙信标运行中" else ""
        tvSub.text = "停放于 $timeStr（已停 ${mins} 分钟）$beaconTag"

        if (s.photoPath.isNotBlank() && File(s.photoPath).exists()) {
            val bmp = runCatching { BitmapFactory.decodeFile(s.photoPath) }.getOrNull()
            if (bmp != null) {
                ivPhotoPreview.setImageBitmap(bmp)
                ivPhotoPreview.visibility = View.VISIBLE
            }
        } else {
            ivPhotoPreview.visibility = View.GONE
        }
    }

    private fun showEditSpotDialog() {
        val s = spot ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val etFloor = EditText(this).apply {
            hint = "楼层（如 B2、B1、地面）"
            setText(s.floor)
        }
        val etSpot = EditText(this).apply {
            hint = "车位编号/区域（如 D-108、近3号梯）"
            setText(s.spotCode)
        }
        val etMall = EditText(this).apply {
            hint = "商场/车库名称"
            setText(s.mallName)
        }
        box.addView(etFloor)
        box.addView(etSpot)
        box.addView(etMall)

        AlertDialog.Builder(this)
            .setTitle("修改停车位信息")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val fl = etFloor.text.toString().trim()
                val sp = etSpot.text.toString().trim()
                val ml = etMall.text.toString().trim()
                val updated = ParkingStore.updateCurrent(this) { cur ->
                    cur.copy(
                        floor = fl,
                        floorIndex = IndoorParkingLocator.parseFloorIndex(fl),
                        spotCode = sp,
                        mallName = ml
                    )
                }
                if (updated != null) {
                    spot = updated
                    refreshHeaderAndPhoto()
                    updateFloorAndCompassTip()
                    ParkingNotifier.showParkedNotification(this, updated)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun captureParkingPhoto() {
        runCatching {
            val dir = File(filesDir, "parking_photos").apply { mkdirs() }
            val file = File(dir, "spot_${System.currentTimeMillis()}.jpg")
            pendingPhotoFile = file
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            takePhotoLauncher.launch(uri)
        }.onFailure {
            Toast.makeText(this, "无法打开相机：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPhotoDialog() {
        val p = spot?.photoPath ?: return
        val bmp = runCatching { BitmapFactory.decodeFile(p) }.getOrNull() ?: return
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
        }
        AlertDialog.Builder(this).setTitle("车位柱子照片").setView(iv).setPositiveButton("关闭", null).show()
    }

    private fun openExternalMap() {
        val s = spot ?: return
        if (!RideTrackMath.valid(s.lat, s.lng)) {
            Toast.makeText(this, "暂无有效坐标", Toast.LENGTH_SHORT).show()
            return
        }
        val dname = Uri.encode(s.shortTitle())
        val amapUri = Uri.parse("amapuri://route/plan/?dlat=${s.lat}&dlon=${s.lng}&dname=$dname&dev=0&t=2")
        val amapIntent = Intent(Intent.ACTION_VIEW, amapUri).setPackage("com.autonavi.minimap")
        val launched = runCatching { startActivity(amapIntent); true }.getOrDefault(false)
        if (!launched) {
            val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lng}?q=${s.lat},${s.lng}($dname)"))
            runCatching { startActivity(geo) }.onFailure {
                Toast.makeText(this, "未找到可用的地图 App", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)).toFloat()) + 360f) % 360f
    }

    private fun makeActionBtn(label: String, bgHex: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        background = cardBg(bgHex, "#334155")
        setOnClickListener { onClick() }
    }

    private fun weightLp(leftMargin: Int, rightMargin: Int) =
        LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            this.leftMargin = leftMargin
            this.rightMargin = rightMargin
        }

    private fun cardBg(fillHex: String, strokeHex: String) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor(fillHex))
        setStroke(dp(1), Color.parseColor(strokeHex))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        refreshHeaderAndPhoto()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        bleFinder?.stop()
        rotListener?.let { runCatching { sensorManager?.unregisterListener(it) } }
        runCatching {
            locClient?.stopLocation()
            locClient?.onDestroy()
        }
        mapView?.onDestroy()
        super.onDestroy()
    }

    companion object {
        fun open(context: Context) {
            val i = Intent(context, FindMyCarActivity::class.java)
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }
}
