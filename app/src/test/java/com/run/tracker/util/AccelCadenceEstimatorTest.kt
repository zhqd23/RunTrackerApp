package com.run.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 加速度计步频估计的单元测试。
 *
 * 用合成信号模拟 50Hz 采样下不同步频的垂直振动，验证估计值是否准确。
 */
class AccelCadenceEstimatorTest {

    /** 生成一段「重力 + 垂直正弦振动」的加速度序列，返回结束时刻 */
    private fun feed(
        estimator: AccelCadenceEstimator,
        hertz: Double,
        seconds: Int = 10,
        amplitude: Double = 3.0
    ): Long {
        val sampleRateHz = 50
        val sampleIntervalMs = 1_000L / sampleRateHz
        var timeMs = 0L
        repeat(seconds * sampleRateHz) { i ->
            val t = i.toDouble() / sampleRateHz
            val value = 9.8 + amplitude * sin(2 * PI * hertz * t)
            estimator.onSample(timeMs, 0f, value.toFloat(), 0f)
            timeMs += sampleIntervalMs
        }
        return timeMs
    }

    @Test
    fun `三赫兹步频应估计为每秒一百八十步`() {
        val estimator = AccelCadenceEstimator()
        val end = feed(estimator, hertz = 3.0)
        assertEquals(180.0, estimator.cadence(end).toDouble(), 10.0)
    }

    @Test
    fun `两赫兹步频应估计为每秒一百二十步`() {
        val estimator = AccelCadenceEstimator()
        val end = feed(estimator, hertz = 2.0)
        assertEquals(120.0, estimator.cadence(end).toDouble(), 10.0)
    }

    @Test
    fun `接近三赫兹的高步频不会被计步传感器上限截断`() {
        val estimator = AccelCadenceEstimator()
        val end = feed(estimator, hertz = 3.05)   // 约 183 步/分
        // 关键回归点：旧实现走计步传感器时最高只能到约 153
        assertTrue(
            "高步频应超过 160，实际 ${estimator.cadence(end)}",
            estimator.cadence(end) > 160
        )
    }

    @Test
    fun `静止时步频为零`() {
        val estimator = AccelCadenceEstimator()
        var timeMs = 0L
        repeat(250) {
            estimator.onSample(timeMs, 0f, 9.8f, 0f)
            timeMs += 20
        }
        assertEquals(0, estimator.cadence(timeMs))
    }

    @Test
    fun `停止迈步三秒后归零`() {
        val estimator = AccelCadenceEstimator()
        val end = feed(estimator, hertz = 3.0)
        assertTrue(estimator.cadence(end) > 0)
        assertEquals(0, estimator.cadence(end + 4_000))
    }

    @Test
    fun `重置后步频为零`() {
        val estimator = AccelCadenceEstimator()
        val end = feed(estimator, hertz = 3.0)
        assertTrue(estimator.cadence(end) > 0)

        estimator.reset()
        assertEquals(0, estimator.cadence(end))
    }

    @Test
    fun `小幅抖动不会被当成迈步`() {
        val estimator = AccelCadenceEstimator()
        var timeMs = 0L
        repeat(250) { i ->
            // 幅度 0.1 m/s² 的噪声，远低于阈值
            val value = 9.8 + 0.1 * sin(2 * PI * 3.0 * (i / 50.0))
            estimator.onSample(timeMs, 0f, value.toFloat(), 0f)
            timeMs += 20
        }
        assertEquals(0, estimator.cadence(timeMs))
    }
}
