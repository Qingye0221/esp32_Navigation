package com.example.motonav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 阶段二：室内定位与地库停车位记录模型
 * sourceLevel: 1 = 高德室内精定位, 2 = 气压/Wi-Fi/入口兜底, 3 = 开源引擎/手动记录
 */
data class ParkingSpot(
    val id: String,
    val timestampMs: Long,
    val lat: Double,
    val lng: Double,
    val mallName: String,
    val buildingId: String,
    val floor: String,
    val floorIndex: Int,
    val spotCode: String,
    val accuracyM: Float,
    val sourceLevel: Int,
    val isEntranceFallback: Boolean = false,
    val beaconEnabled: Boolean = false,
    val beaconMac: String = "",
    val beaconExpireAt: Long = 0L,
    val photoPath: String = "",
    val note: String = ""
) {
    fun shortTitle(): String {
        val parts = mutableListOf<String>()
        if (mallName.isNotBlank()) parts += mallName
        val loc = listOf(floor, spotCode).filter { it.isNotBlank() }.joinToString("-")
        if (loc.isNotBlank()) parts += loc
        return if (parts.isNotEmpty()) parts.joinToString(" · ") else "已记录的停车位"
    }

    fun floorAndSpot(): String {
        val loc = listOf(floor, spotCode).filter { it.isNotBlank() }.joinToString(" · ")
        return loc.ifBlank { "未知楼层" }
    }

    fun isBeaconActiveNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        beaconEnabled && (beaconExpireAt <= 0L || nowMs < beaconExpireAt)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("ts", timestampMs)
        .put("lat", lat)
        .put("lng", lng)
        .put("mall", mallName)
        .put("bid", buildingId)
        .put("floor", floor)
        .put("fidx", floorIndex)
        .put("spot", spotCode)
        .put("acc", accuracyM.toDouble())
        .put("src", sourceLevel)
        .put("ent", isEntranceFallback)
        .put("bOn", beaconEnabled)
        .put("bMac", beaconMac)
        .put("bExp", beaconExpireAt)
        .put("photo", photoPath)
        .put("note", note)

    companion object {
        fun fromJson(o: JSONObject): ParkingSpot? {
            val lat = o.optDouble("lat", Double.NaN)
            val lng = o.optDouble("lng", Double.NaN)
            val id = o.optString("id").ifBlank { UUID.randomUUID().toString() }
            return ParkingSpot(
                id = id,
                timestampMs = o.optLong("ts", System.currentTimeMillis()),
                lat = lat,
                lng = lng,
                mallName = o.optString("mall"),
                buildingId = o.optString("bid"),
                floor = o.optString("floor"),
                floorIndex = o.optInt("fidx", 0),
                spotCode = o.optString("spot"),
                accuracyM = o.optDouble("acc", -1.0).toFloat(),
                sourceLevel = o.optInt("src", 2),
                isEntranceFallback = o.optBoolean("ent", false),
                beaconEnabled = o.optBoolean("bOn", false),
                beaconMac = o.optString("bMac"),
                beaconExpireAt = o.optLong("bExp", 0L),
                photoPath = o.optString("photo"),
                note = o.optString("note")
            )
        }
    }
}

object ParkingStore {
    private const val PREFS = "motonav"
    private const val KEY_CURRENT = "parking_current_v1"
    private const val KEY_HISTORY = "parking_history_v1"
    private const val MAX_HISTORY = 20

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun current(c: Context): ParkingSpot? {
        val raw = prefs(c).getString(KEY_CURRENT, null) ?: return null
        return runCatching { ParkingSpot.fromJson(JSONObject(raw)) }.getOrNull()
    }

    @Synchronized
    fun history(c: Context): List<ParkingSpot> {
        val raw = prefs(c).getString(KEY_HISTORY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { ParkingSpot.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }.take(MAX_HISTORY)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun saveCurrent(c: Context, spot: ParkingSpot) {
        val old = current(c)
        val hist = history(c).toMutableList()
        if (old != null && old.id != spot.id) {
            hist.removeAll { it.id == old.id }
            hist.add(0, old)
        }
        hist.removeAll { it.id == spot.id }
        val arr = JSONArray()
        hist.take(MAX_HISTORY).forEach { arr.put(it.toJson()) }
        prefs(c).edit()
            .putString(KEY_CURRENT, spot.toJson().toString())
            .putString(KEY_HISTORY, arr.toString())
            .apply()
        notifyChanged()
    }

    @Synchronized
    fun updateCurrent(c: Context, transform: (ParkingSpot) -> ParkingSpot): ParkingSpot? {
        val cur = current(c) ?: return null
        val updated = transform(cur)
        prefs(c).edit().putString(KEY_CURRENT, updated.toJson().toString()).apply()
        notifyChanged()
        return updated
    }

    @Synchronized
    fun archiveCurrent(c: Context) {
        val cur = current(c) ?: return
        val hist = history(c).toMutableList()
        hist.removeAll { it.id == cur.id }
        hist.add(0, cur.copy(beaconEnabled = false))
        val arr = JSONArray()
        hist.take(MAX_HISTORY).forEach { arr.put(it.toJson()) }
        prefs(c).edit()
            .remove(KEY_CURRENT)
            .putString(KEY_HISTORY, arr.toString())
            .apply()
        notifyChanged()
    }

    @Synchronized
    fun clearCurrent(c: Context) {
        prefs(c).edit().remove(KEY_CURRENT).apply()
        notifyChanged()
    }

    @Synchronized
    fun removeHistory(c: Context, id: String) {
        val hist = history(c).filter { it.id != id }
        val arr = JSONArray()
        hist.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString(KEY_HISTORY, arr.toString()).apply()
        notifyChanged()
    }
}
