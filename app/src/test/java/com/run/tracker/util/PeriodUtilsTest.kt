package com.run.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 周报 / 月报时间窗口与展示文本的单元测试。
 */
class PeriodUtilsTest {

    /** 构造本地时区的指定时刻，便于与工具类内部使用同一时区对齐 */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `周三所在周从本周一开始`() {
        // 2024-06-12 是周三
        val (start, end) = PeriodUtils.currentWeekRange(at(2024, Calendar.JUNE, 12, 15, 30))
        assertEquals(at(2024, Calendar.JUNE, 10), start)
        assertEquals(at(2024, Calendar.JUNE, 17), end)
    }

    @Test
    fun `周一时窗口从当天开始`() {
        // 2024-06-10 是周一
        val (start, end) = PeriodUtils.currentWeekRange(at(2024, Calendar.JUNE, 10, 8, 0))
        assertEquals(at(2024, Calendar.JUNE, 10), start)
        assertEquals(at(2024, Calendar.JUNE, 17), end)
    }

    @Test
    fun `周日属于上一个周一开启的周`() {
        // 2024-06-16 是周日，应归属 06-10 ~ 06-17
        val (start, end) = PeriodUtils.currentWeekRange(at(2024, Calendar.JUNE, 16, 22, 0))
        assertEquals(at(2024, Calendar.JUNE, 10), start)
        assertEquals(at(2024, Calendar.JUNE, 17), end)
    }

    @Test
    fun `月窗口从当月一号到下月一号`() {
        val (start, end) = PeriodUtils.currentMonthRange(at(2024, Calendar.MARCH, 15, 9, 0))
        assertEquals(at(2024, Calendar.MARCH, 1), start)
        assertEquals(at(2024, Calendar.APRIL, 1), end)
    }

    @Test
    fun `十二月窗口跨年`() {
        val (start, end) = PeriodUtils.currentMonthRange(at(2024, Calendar.DECEMBER, 31, 23, 0))
        assertEquals(at(2024, Calendar.DECEMBER, 1), start)
        assertEquals(at(2025, Calendar.JANUARY, 1), end)
    }

    @Test
    fun `周窗口时长恒为七天`() {
        val (start, end) = PeriodUtils.currentWeekRange(at(2024, Calendar.JUNE, 12, 15, 30))
        assertEquals(7 * 24 * 60 * 60 * 1000L, end - start)
    }

    @Test
    fun `窗口文本按闭区间展示末日`() {
        val start = at(2024, Calendar.JUNE, 10)
        val end = at(2024, Calendar.JUNE, 17)
        assertEquals("06-10 ~ 06-16", PeriodUtils.formatRangeText(start, end))
    }

    @Test
    fun `开始时间始终早于结束时间`() {
        val (start, end) = PeriodUtils.currentMonthRange(at(2024, Calendar.FEBRUARY, 29, 12, 0))
        assertTrue(start < end)
    }
}
