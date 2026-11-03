package com.run.tracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/** 汇总统计结果行（对应 DAO 聚合查询） */
data class RunStatsRow(
    val totalCount: Int,
    val totalDistanceMeters: Double,
    val totalDurationMs: Long,
    val totalCalories: Double,
    val avgHeartRate: Double
)

/** 历史列表摘要：除轨迹点外的全部字段，避免列表页解码整条轨迹 */
data class RunSummary(
    val id: Long,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val distanceMeters: Double,
    val avgSpeedMs: Double,
    val maxSpeedMs: Double,
    val avgHeartRate: Int,
    val maxHeartRate: Int,
    val avgCadence: Int,
    val calories: Double
)

/**
 * 应用数据库。版本 1。
 */
@Database(entities = [RunRecordEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class RunDatabase : RoomDatabase() {

    abstract fun runRecordDao(): RunRecordDao

    companion object {
        @Volatile
        private var INSTANCE: RunDatabase? = null

        fun getInstance(context: Context): RunDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RunDatabase::class.java,
                    "run_tracker.db"
                ).build().also { INSTANCE = it }
            }
    }
}
