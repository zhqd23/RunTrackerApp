package com.run.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 步频自适应校准的单元测试。
 *
 * 用「同一时刻的 counter / detector 增量比」判断 detector 是否漏报。
 */
class StepCalibratorTest {

    private fun feed(
        calibrator: StepCalibrator,
        times: List<Long>,
        counters: List<Long>,
        detectors: List<Long>
    ) {
        for (i in times.indices) {
            calibrator.onCounterSample(times[i], counters[i], detectors[i])
        }
    }

    private val times = listOf(0L, 5_000L, 10_000L, 15_000L, 20_000L, 25_000L)

    @Test
    fun `两个传感器计数一致时不校准`() {
        val calibrator = StepCalibrator()
        feed(
            calibrator, times,
            counters = listOf(0L, 10L, 20L, 30L, 40L, 50L),
            detectors = listOf(0L, 10L, 20L, 30L, 40L, 50L)
        )
        assertEquals(1.0, calibrator.factor(), 1e-9)
    }

    @Test
    fun `检测器只上报一半步数时放大两倍`() {
        val calibrator = StepCalibrator()
        feed(
            calibrator, times,
            counters = listOf(0L, 10L, 20L, 30L, 40L, 50L),
            detectors = listOf(0L, 5L, 10L, 15L, 20L, 25L)
        )
        assertEquals(2.0, calibrator.factor(), 1e-9)
    }

    @Test
    fun `时间跨度不足时保持一比一`() {
        val calibrator = StepCalibrator()
        feed(
            calibrator, times.take(3),
            counters = listOf(0L, 10L, 20L),
            detectors = listOf(0L, 5L, 10L)
        )
        assertEquals(1.0, calibrator.factor(), 1e-9)
    }

    @Test
    fun `检测器样本太少时不校准`() {
        val calibrator = StepCalibrator()
        feed(
            calibrator, times,
            counters = listOf(0L, 20L, 40L, 60L, 80L, 100L),
            detectors = listOf(0L, 1L, 2L, 3L, 4L, 5L)
        )
        assertEquals(1.0, calibrator.factor(), 1e-9)
    }

    @Test
    fun `比例异常大时按上限处理`() {
        val calibrator = StepCalibrator()
        feed(
            calibrator, times,
            counters = listOf(0L, 20L, 40L, 60L, 80L, 100L),
            detectors = listOf(0L, 4L, 8L, 12L, 16L, 20L)
        )
        assertEquals(2.5, calibrator.factor(), 1e-9)
    }

    @Test
    fun `检测器多报时不做下调`() {
        val calibrator = StepCalibrator()
        feed(
            calibrator, times,
            counters = listOf(0L, 5L, 10L, 15L, 20L, 25L),
            detectors = listOf(0L, 10L, 20L, 30L, 40L, 50L)
        )
        assertEquals(1.0, calibrator.factor(), 1e-9)
    }

    @Test
    fun `重置后回到一比一`() {
        val calibrator = StepCalibrator()
        feed(
            calibrator, times,
            counters = listOf(0L, 10L, 20L, 30L, 40L, 50L),
            detectors = listOf(0L, 5L, 10L, 15L, 20L, 25L)
        )
        assertEquals(2.0, calibrator.factor(), 1e-9)

        calibrator.reset()
        assertEquals(1.0, calibrator.factor(), 1e-9)
    }
}
