package com.run.tracker.data.model

/**
 * 轨迹点：一次跑步过程中按时间序列记录的定位与传感器快照。
 *
 * @param lat 纬度（度）
 * @param lon 经度（度）
 * @param timestampMs 采样时间戳（毫秒）
 * @param altitude 海拔（米）
 * @param speedMs 该点即时速度（米/秒，由相邻两点距离/时间差计算）
 * @param heartRate 该点心率（bpm，0 表示无数据）
 * @param cadence 该点步频（步/分钟，0 表示无数据）
 */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val timestampMs: Long,
    val altitude: Double = 0.0,
    val speedMs: Double = 0.0,
    val heartRate: Int = 0,
    val cadence: Int = 0
)
