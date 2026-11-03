package com.run.tracker.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.run.tracker.data.model.TrackPoint

/**
 * 跑步记录实体，存储到 Room 数据库。
 * 轨迹点列表通过 Converters 序列化为 JSON 文本列。
 */
@Entity(tableName = "run_records")
data class RunRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** 开始时间（毫秒时间戳） */
    val startTimeMs: Long,
    /** 结束时间（毫秒时间戳） */
    val endTimeMs: Long,
    /** 总时长（毫秒，扣除暂停时间） */
    val durationMs: Long,
    /** 总距离（米） */
    val distanceMeters: Double,
    /** 平均速度（米/秒 = 总距离 / 总用时） */
    val avgSpeedMs: Double,
    /** 最大速度（米/秒） */
    val maxSpeedMs: Double,
    /** 平均心率（bpm） */
    val avgHeartRate: Int,
    /** 最高心率（bpm） */
    val maxHeartRate: Int,
    /** 平均步频（步/分钟） */
    val avgCadence: Int,
    /** 卡路里估算（千卡） */
    val calories: Double,
    /** 轨迹点列表（由 Room TypeConverter 序列化） */
    val trackPoints: List<TrackPoint>
)
