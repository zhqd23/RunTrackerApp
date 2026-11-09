package com.run.tracker.data.db

/**
 * 卡路里重算用的轻量投影：只取算卡路里需要的三个字段，
 * 避免为了重算把每条记录的轨迹 JSON 也读出来。
 */
data class RunCalorieRow(
    val id: Long,
    val distanceMeters: Double,
    val durationMs: Long
)
