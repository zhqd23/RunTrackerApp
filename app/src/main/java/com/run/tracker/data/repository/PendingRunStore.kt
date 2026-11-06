package com.run.tracker.data.repository

import android.content.Context
import com.run.tracker.data.db.Converters
import com.run.tracker.data.db.RunRecordEntity
import org.json.JSONObject

/**
 * 待保存跑步记录的本地暂存。
 *
 * 结束跑步时先同步写到这里，再异步写数据库；写库成功后才清除。
 * 这样即便进程在「结束」瞬间崩溃（曾出现过闪退导致整次记录丢失），
 * 下次启动也能把这条记录补写进数据库。
 */
class PendingRunStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val converters = Converters()

    /** 同步写入（用 commit 而非 apply），确保崩溃前数据已经落盘 */
    fun save(record: RunRecordEntity) {
        val json = JSONObject().apply {
            put("startTimeMs", record.startTimeMs)
            put("endTimeMs", record.endTimeMs)
            put("durationMs", record.durationMs)
            put("distanceMeters", record.distanceMeters)
            put("avgSpeedMs", record.avgSpeedMs)
            put("maxSpeedMs", record.maxSpeedMs)
            put("avgHeartRate", record.avgHeartRate)
            put("maxHeartRate", record.maxHeartRate)
            put("avgCadence", record.avgCadence)
            put("calories", record.calories)
            put("trackPoints", converters.fromTrackPoints(record.trackPoints))
        }
        prefs.edit().putString(KEY_RECORD, json.toString()).commit()
    }

    /** 读取未保存的记录，没有则返回 null */
    fun load(): RunRecordEntity? {
        val raw = prefs.getString(KEY_RECORD, null) ?: return null
        return try {
            val json = JSONObject(raw)
            RunRecordEntity(
                startTimeMs = json.getLong("startTimeMs"),
                endTimeMs = json.getLong("endTimeMs"),
                durationMs = json.getLong("durationMs"),
                distanceMeters = json.getDouble("distanceMeters"),
                avgSpeedMs = json.getDouble("avgSpeedMs"),
                maxSpeedMs = json.getDouble("maxSpeedMs"),
                avgHeartRate = json.getInt("avgHeartRate"),
                maxHeartRate = json.getInt("maxHeartRate"),
                avgCadence = json.getInt("avgCadence"),
                calories = json.getDouble("calories"),
                trackPoints = converters.toTrackPoints(json.getString("trackPoints"))
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_RECORD).commit()
    }

    companion object {
        private const val PREFS = "pending_run"
        private const val KEY_RECORD = "record"
    }
}
