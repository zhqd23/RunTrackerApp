package com.run.tracker.util

import java.util.Locale

/**
 * 数据格式化工具。
 */
object FormatUtils {

    /** 毫秒 -> "1:23:45" 或 "23:45" */
    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /** 米 -> 公里字符串（保留 2 位小数） */
    fun formatKm(meters: Double): String =
        String.format(Locale.US, "%.2f", meters / 1000.0)

    /** 米 -> 公里字符串（保留 1 位小数，用于大字展示） */
    fun formatKmShort(meters: Double): String =
        String.format(Locale.US, "%.1f", meters / 1000.0)

    /** 米/秒 -> 公里/小时字符串 */
    fun formatKmh(speedMs: Double): String =
        String.format(Locale.US, "%.1f", LocationUtils.msToKmh(speedMs))

    /** 米/秒 -> 配速字符串 "5'30\""（分/公里） */
    fun formatPace(speedMs: Double): String {
        val paceMin = LocationUtils.speedToPaceMinPerKm(speedMs)
        if (paceMin <= 0) return "--'--\""
        // 先换算成总秒数再四舍五入，避免 5'00" 被截断显示成 4'59"
        val totalSec = Math.round(paceMin * 60).toInt()
        return String.format(Locale.US, "%d'%02d\"", totalSec / 60, totalSec % 60)
    }

    /** 毫秒时间戳 -> "yyyy-MM-dd HH:mm" */
    fun formatDateTime(tsMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = tsMs
        val y = cal.get(java.util.Calendar.YEAR)
        val mo = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val mi = cal.get(java.util.Calendar.MINUTE)
        return String.format(Locale.US, "%d-%02d-%02d %02d:%02d", y, mo, d, h, mi)
    }

    /** 毫秒时间戳 -> "MM-dd" */
    fun formatDateShort(tsMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = tsMs
        val mo = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%02d-%02d", mo, d)
    }
}
