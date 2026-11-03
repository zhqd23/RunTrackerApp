package com.run.tracker.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地理计算工具：使用 Haversine 公式计算球面两点距离。
 */
object LocationUtils {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Haversine 距离（米）。
     * @param lat1 纬度1（度）
     * @param lon1 经度1（度）
     * @param lat2 纬度2（度）
     * @param lon2 经度2（度）
     */
    fun haversineDistanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * 由两点计算即时速度（米/秒）。
     * 时间差过小或距离过近时返回 0，避免噪声。
     */
    fun speedBetweenPoints(
        lat1: Double, lon1: Double, t1: Long,
        lat2: Double, lon2: Double, t2: Long
    ): Double {
        val dtMs = t2 - t1
        if (dtMs <= 0) return 0.0
        val dist = haversineDistanceMeters(lat1, lon1, lat2, lon2)
        if (dist < 0.3) return 0.0
        return dist / (dtMs / 1000.0)
    }

    /** 米/秒 -> 公里/小时 */
    fun msToKmh(speedMs: Double): Double = speedMs * 3.6

    /** 米/秒 -> 分/公里配速（min/km），速度为 0 时返回无限（用 -1 表示） */
    fun speedToPaceMinPerKm(speedMs: Double): Double {
        if (speedMs <= 0) return -1.0
        val kmh = msToKmh(speedMs)
        return 60.0 / kmh
    }
}
