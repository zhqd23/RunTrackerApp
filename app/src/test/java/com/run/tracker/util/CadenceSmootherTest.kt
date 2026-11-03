package com.run.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 步频平滑的单元测试：匀速稳定、抖动抑制、停止衰减、上限与重置。
 */
class CadenceSmootherTest {

    @Test
    fun `没有采样时步频为 0`() {
        val smoother = CadenceSmoother()
        assertEquals(0, smoother.cadence(1_000L))
    }

    @Test
    fun `匀速 180 步每分钟趋于稳定`() {
        val smoother = CadenceSmoother()
        var timeMs = 0L
        var steps = 0L
        var cadence = 0
        // 每秒 3 步 = 180 步/分，持续 20 秒，每秒刷新一次
        repeat(20) {
            repeat(3) {
                timeMs += 333
                steps += 1
                smoother.onStep(timeMs, steps)
            }
            cadence = smoother.cadence(timeMs)
        }
        assertEquals(180.0, cadence.toDouble(), 12.0)
    }

    @Test
    fun `步频抖动被平滑`() {
        val smoother = CadenceSmoother()
        var timeMs = 0L
        var steps = 0L
        val values = mutableListOf<Int>()
        // 每秒 2 步 / 4 步 交替（瞬时值在 120 与 240 之间来回跳）
        repeat(20) { second ->
            val perSecond = if (second % 2 == 0) 2 else 4
            repeat(perSecond) {
                timeMs += 1_000 / perSecond
                steps += 1
                smoother.onStep(timeMs, steps)
            }
            values.add(smoother.cadence(timeMs))
        }

        val settled = values.drop(10)
        val maxJump = settled.zipWithNext { a, b -> abs(a - b) }.maxOrNull() ?: 0
        assertTrue("相邻刷新跳变应被压到 40 以内，实际 $maxJump", maxJump < 40)
    }

    @Test
    fun `停止迈步后衰减到 0`() {
        val smoother = CadenceSmoother()
        var timeMs = 0L
        var steps = 0L
        repeat(15) {
            repeat(3) {
                timeMs += 333
                steps += 1
                smoother.onStep(timeMs, steps)
            }
            smoother.cadence(timeMs)
        }
        assertTrue("跑动时步频应有合理值", smoother.cadence(timeMs) > 100)

        // 停下来后每秒刷新一次，8 秒内应衰减到 0
        val stoppedAt = timeMs + 5_000
        var cadence = 0
        repeat(8) { cadence = smoother.cadence(stoppedAt + it * 1_000L) }
        assertEquals(0, cadence)
    }

    @Test
    fun `步频不会超过上限`() {
        val smoother = CadenceSmoother()
        var timeMs = 0L
        var steps = 0L
        // 极端情况：每秒 10 步 = 600 步/分
        repeat(10) {
            repeat(10) {
                timeMs += 100
                steps += 1
                smoother.onStep(timeMs, steps)
            }
            smoother.cadence(timeMs)
        }
        assertEquals(CadenceSmoother.MAX_CADENCE, smoother.cadence(timeMs))
    }

    @Test
    fun `重置后回到 0`() {
        val smoother = CadenceSmoother()
        var timeMs = 0L
        var steps = 0L
        repeat(10) {
            repeat(3) {
                timeMs += 333
                steps += 1
                smoother.onStep(timeMs, steps)
            }
        }
        assertTrue(smoother.cadence(timeMs) > 100)

        smoother.reset()
        assertEquals(0, smoother.cadence(timeMs))
    }
}
