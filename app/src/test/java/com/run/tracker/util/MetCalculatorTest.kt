package com.run.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡路里估算测试：MET 查表插值 + 体重/时长换算。
 */
class MetCalculatorTest {

    @Test
    fun metMatchesTablePoints() {
        assertEquals(9.8, MetCalculator.metForSpeedKmh(9.7), 1e-9)
        assertEquals(11.0, MetCalculator.metForSpeedKmh(11.3), 1e-9)
        assertEquals(8.3, MetCalculator.metForSpeedKmh(8.0), 1e-9)
        assertEquals(4.8, MetCalculator.metForSpeedKmh(5.0), 1e-9)
    }

    @Test
    fun metInterpolatesBetweenPoints() {
        // 9.7 → 9.8，11.3 → 11.0，中点 10.5 应为 10.4
        assertEquals(10.4, MetCalculator.metForSpeedKmh(10.5), 1e-9)
        // 8.0 → 8.3，9.7 → 9.8，8.85 处应为 8.3 + 1.5 × 0.5 = 9.05
        assertEquals(9.05, MetCalculator.metForSpeedKmh(8.85), 1e-9)
    }

    @Test
    fun metClampsOutOfRange() {
        assertEquals(4.8, MetCalculator.metForSpeedKmh(0.0), 1e-9)
        assertEquals(4.8, MetCalculator.metForSpeedKmh(-3.0), 1e-9)
        assertEquals(16.0, MetCalculator.metForSpeedKmh(25.0), 1e-9)
        assertEquals(16.0, MetCalculator.metForSpeedKmh(19.3), 1e-9)
    }

    @Test
    fun metHandlesInvalidSpeed() {
        assertEquals(4.8, MetCalculator.metForSpeedKmh(Double.NaN), 1e-9)
        assertEquals(16.0, MetCalculator.metForSpeedKmh(Double.POSITIVE_INFINITY), 1e-9)
    }

    @Test
    fun caloriesScalesWithWeight() {
        // 8.0 km/h 的 MET 是 8.3：8.3 × 70kg × 1h = 581
        assertEquals(581.0, MetCalculator.calories(70.0, 8.0, 1.0), 1e-9)
        // 10.4 km/h 插值得 10.325 MET：10.325 × 85 × 0.5 = 438.8125
        assertEquals(438.8125, MetCalculator.calories(85.0, 10.4, 0.5), 1e-6)
        // 体重翻倍，卡路里翻倍
        assertEquals(
            MetCalculator.calories(85.0, 10.4, 0.5) * 2,
            MetCalculator.calories(170.0, 10.4, 0.5),
            1e-6
        )
    }

    @Test
    fun caloriesIsZeroForInvalidInput() {
        assertEquals(0.0, MetCalculator.calories(0.0, 10.0, 0.5), 1e-9)
        assertEquals(0.0, MetCalculator.calories(-5.0, 10.0, 0.5), 1e-9)
        assertEquals(0.0, MetCalculator.calories(70.0, 10.0, 0.0), 1e-9)
        assertEquals(0.0, MetCalculator.calories(70.0, 10.0, -1.0), 1e-9)
    }

    @Test
    fun fasterRunBurnsMorePerMinute() {
        val slow = MetCalculator.calories(70.0, 8.0, 1.0 / 6)   // 10 分钟 8km/h
        val fast = MetCalculator.calories(70.0, 14.5, 1.0 / 6)  // 10 分钟 14.5km/h
        assertEquals(true, fast > slow)
    }

    @Test
    fun caloriesForRunConvertsMillisecondsCorrectly() {
        // 5.21 km / 30:17（1817 秒）/ 78 kg → 平均 10.32 km/h、MET≈10.27 → 约 404 kcal
        val kcal = MetCalculator.caloriesForRun(78.0, 5210.0, 1_817_000L)
        assertEquals(404.2, kcal, 0.5)
        // 同样的距离用毫秒直接除会小 1000 倍，这里确保不会退化成最低 MET
        assertEquals(true, kcal > 350.0)
    }

    @Test
    fun caloriesForRunZeroDurationIsZero() {
        assertEquals(0.0, MetCalculator.caloriesForRun(78.0, 5210.0, 0L), 1e-9)
    }
}
