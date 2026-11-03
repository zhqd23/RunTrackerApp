package com.run.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Haversine 距离与速度换算的单元测试。
 */
class LocationUtilsTest {

    @Test
    fun `同一位置距离为 0`() {
        val d = LocationUtils.haversineDistanceMeters(39.9042, 116.4074, 39.9042, 116.4074)
        assertEquals(0.0, d, 1e-9)
    }

    @Test
    fun `纬度相差 1 度约等于 111 公里`() {
        val d = LocationUtils.haversineDistanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_194.93, d, 1.0)
    }

    @Test
    fun `经度相差 1 度在赤道约等于 111 公里`() {
        val d = LocationUtils.haversineDistanceMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_194.93, d, 1.0)
    }

    @Test
    fun `经度差在高纬度会缩短`() {
        val equator = LocationUtils.haversineDistanceMeters(0.0, 0.0, 0.0, 1.0)
        val beijing = LocationUtils.haversineDistanceMeters(39.9, 116.0, 39.9, 117.0)
        // 北纬 40 度附近 1 经度约 85 公里，明显小于赤道的 111 公里
        assertEquals(85_000.0, beijing, 1_000.0)
        assertEquals(true, beijing < equator)
    }

    @Test
    fun `两点间速度按时间差计算`() {
        // 0.0009 度纬度约 100 米，1 秒 -> 约 100 m/s
        val speed = LocationUtils.speedBetweenPoints(
            0.0, 0.0, 0L,
            0.0009, 0.0, 1_000L
        )
        assertEquals(100.0, speed, 1.0)
    }

    @Test
    fun `时间差非正时速度返回 0`() {
        assertEquals(
            0.0,
            LocationUtils.speedBetweenPoints(0.0, 0.0, 2_000L, 0.001, 0.0, 1_000L),
            1e-9
        )
    }

    @Test
    fun `距离过近时速度返回 0 以过滤 GPS 抖动`() {
        // 0.000001 度纬度约 0.11 米，低于 0.3 米阈值
        assertEquals(
            0.0,
            LocationUtils.speedBetweenPoints(0.0, 0.0, 0L, 0.000001, 0.0, 1_000L),
            1e-9
        )
    }

    @Test
    fun `米每秒换算公里每小时`() {
        assertEquals(3.6, LocationUtils.msToKmh(1.0), 1e-9)
        assertEquals(36.0, LocationUtils.msToKmh(10.0), 1e-9)
    }

    @Test
    fun `配速为速度倒数的换算`() {
        // 12 km/h = 3.333 m/s -> 5 分/公里
        assertEquals(5.0, LocationUtils.speedToPaceMinPerKm(3.3333333), 1e-6)
    }

    @Test
    fun `速度为 0 时配速返回 -1`() {
        assertEquals(-1.0, LocationUtils.speedToPaceMinPerKm(0.0), 1e-9)
        assertEquals(-1.0, LocationUtils.speedToPaceMinPerKm(-1.0), 1e-9)
    }
}
