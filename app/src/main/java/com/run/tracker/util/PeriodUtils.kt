package com.run.tracker.util

import java.util.Calendar

/**
 * 周期统计工具：计算周报 / 月报的时间窗口与展示文本。
 */
object PeriodUtils {

    /**
     * 当前时间所在周的窗口：本周一 00:00:00 至下周一 00:00:00。
     * 返回 Pair(startMs, endMs)，startMs 含、endMs 不含。
     */
    fun currentWeekRange(nowMs: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        // Calendar 中周日=1、周一=2 ... 周六=7
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysSinceMonday =
            if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 7)
        return start to cal.timeInMillis
    }

    /**
     * 当前时间所在月的窗口：本月 1 日 00:00:00 至下月 1 日 00:00:00。
     * 返回 Pair(startMs, endMs)，startMs 含、endMs 不含。
     */
    fun currentMonthRange(nowMs: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return start to cal.timeInMillis
    }

    /** 窗口展示文本，如 "09-01 ~ 09-07"（endMs 为开区间，展示时取末毫秒所在天） */
    fun formatRangeText(startMs: Long, endMs: Long): String {
        val calStart = Calendar.getInstance().apply { timeInMillis = startMs }
        val calEnd = Calendar.getInstance().apply { timeInMillis = endMs - 1 }
        return String.format(
            java.util.Locale.US,
            "%02d-%02d ~ %02d-%02d",
            calStart.get(Calendar.MONTH) + 1,
            calStart.get(Calendar.DAY_OF_MONTH),
            calEnd.get(Calendar.MONTH) + 1,
            calEnd.get(Calendar.DAY_OF_MONTH)
        )
    }
}
