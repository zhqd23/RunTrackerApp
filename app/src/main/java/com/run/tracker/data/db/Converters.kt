package com.run.tracker.data.db

import androidx.room.TypeConverter
import com.run.tracker.data.model.TrackPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * Room TypeConverter：在 List<TrackPoint> 与 JSON 文本之间转换。
 * 使用 Android 内置 org.json，避免额外依赖。
 */
class Converters {

    @TypeConverter
    fun fromTrackPoints(points: List<TrackPoint>): String {
        val array = JSONArray()
        points.forEach { p ->
            val obj = JSONObject()
            obj.put("lat", p.lat)
            obj.put("lon", p.lon)
            obj.put("ts", p.timestampMs)
            obj.put("alt", p.altitude)
            obj.put("spd", p.speedMs)
            obj.put("hr", p.heartRate)
            obj.put("cad", p.cadence)
            array.put(obj)
        }
        return array.toString()
    }

    @TypeConverter
    fun toTrackPoints(json: String): List<TrackPoint> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                TrackPoint(
                    lat = obj.getDouble("lat"),
                    lon = obj.getDouble("lon"),
                    timestampMs = obj.getLong("ts"),
                    altitude = obj.optDouble("alt", 0.0),
                    speedMs = obj.optDouble("spd", 0.0),
                    heartRate = obj.optInt("hr", 0),
                    cadence = obj.optInt("cad", 0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
