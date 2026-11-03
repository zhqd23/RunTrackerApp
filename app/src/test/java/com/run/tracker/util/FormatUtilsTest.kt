package com.run.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * 展示格式化的单元测试（时长 / 距离 / 速度 / 配速 / 日期）。
 */
class FormatUtilsTest {

    @Test
    fun `时长小于一小时只显示分秒`() {
        assertEquals("00:00", FormatUtils.formatDuration(0L))
        assertEquals("00:59", FormatUtils.formatDuration(59_000L))
        assertEquals("01:01", FormatUtils.formatDuration(61_000L))
        assertEquals("59:59", FormatUtils.formatDuration(3_599_000L))
    }

    @Test
    fun `时长超过一小时显示时分秒`() {
        assertEquals("1:00:00", FormatUtils.formatDuration(3_600_000L))
        assertEquals("1:02:03", FormatUtils.formatDuration(3_723_000L))
        assertEquals("10:00:00", FormatUtils.formatDuration(36_000_000L))
    }

    @Test
    fun `毫秒向下取整到秒`() {
        // 999 毫秒不足 1 秒，仍显示 00:00
        assertEquals("00:00", FormatUtils.formatDuration(999L))
        assertEquals("00:01", FormatUtils.formatDuration(1_999L))
    }

    @Test
    fun `距离保留两位小数`() {
        assertEquals("1.50", FormatUtils.formatKm(1_500.0))
        assertEquals("0.00", FormatUtils.formatKm(0.0))
        assertEquals("42.20", FormatUtils.formatKm(42_200.0))
    }

    @Test
    fun `距离简写保留一位小数`() {
        assertEquals("1.5", FormatUtils.formatKmShort(1_500.0))
        assertEquals("0.0", FormatUtils.formatKmShort(0.0))
        assertEquals("42.2", FormatUtils.formatKmShort(42_200.0))
    }

    @Test
    fun `速度换算成公里每小时并保留一位小数`() {
        assertEquals("9.0", FormatUtils.formatKmh(2.5))
        assertEquals("0.0", FormatUtils.formatKmh(0.0))
        assertEquals("36.0", FormatUtils.formatKmh(10.0))
    }

    @Test
    fun `配速格式为分撇秒双引号`() {
        assertEquals("5'33\"", FormatUtils.formatPace(3.0))     // 10.8 km/h
        assertEquals("6'00\"", FormatUtils.formatPace(2.7777777777777777)) // 10 km/h
    }

    @Test
    fun `配速在无速度时显示占位符`() {
        assertEquals("--'--\"", FormatUtils.formatPace(0.0))
        assertEquals("--'--\"", FormatUtils.formatPace(-2.0))
    }

    @Test
    fun `日期时间格式化`() {
        val ts = Calendar.getInstance().apply {
            clear()
            set(2024, Calendar.JANUARY, 5, 7, 8, 9)
        }.timeInMillis
        assertEquals("2024-01-05 07:08", FormatUtils.formatDateTime(ts))
    }

    @Test
    fun `短日期格式化`() {
        val ts = Calendar.getInstance().apply {
            clear()
            set(2024, Calendar.DECEMBER, 31, 23, 59, 0)
        }.timeInMillis
        assertEquals("12-31", FormatUtils.formatDateShort(ts))
    }
}
