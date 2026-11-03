package com.run.tracker.data.model

import com.run.tracker.service.HeartRateSource
import com.run.tracker.service.RunState

/**
 * 实时运动页 UI 状态：由前台服务定期发布，ViewModel 直接订阅。
 */
data class RunUiState(
    val state: RunState = RunState.IDLE,
    /** 当前心率（bpm） */
    val heartRate: Int = 0,
    /** 总距离（米） */
    val distanceMeters: Double = 0.0,
    /** 当前步频（步/分钟） */
    val cadence: Int = 0,
    /** 平均速度（米/秒） */
    val avgSpeedMs: Double = 0.0,
    /** 即时速度（米/秒） */
    val currentSpeedMs: Double = 0.0,
    /** 已用时长（毫秒） */
    val durationMs: Long = 0,
    /** 最大速度（米/秒） */
    val maxSpeedMs: Double = 0.0,
    /** 平均心率（bpm） */
    val avgHeartRate: Int = 0,
    /** 最高心率（bpm） */
    val maxHeartRate: Int = 0,
    /** 平均步频（步/分钟） */
    val avgCadence: Int = 0,
    /** 卡路里（千卡） */
    val calories: Double = 0.0,
    /** 心率数据来源（BLE / 模拟 / 搜索中） */
    val heartRateSource: HeartRateSource = HeartRateSource.SIMULATED,
    /** 轨迹点 */
    val points: List<TrackPoint> = emptyList(),
    /** 是否已获取到 GPS 定位 */
    val hasGps: Boolean = false
)
