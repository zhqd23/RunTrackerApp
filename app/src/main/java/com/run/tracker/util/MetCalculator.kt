package com.run.tracker.util

/**
 * 卡路里估算。
 *
 * 采用 Compendium of Physical Activities 的跑步 MET 表：MET 随配速上升，
 * 固定用 9.8 会让快跑低估、慢跑高估。表内按分段线性插值，
 * 低于最低速度取最低档，高于最高速度取最高档。
 *
 * 单次消耗 = MET × 体重(kg) × 时长(h)
 */
object MetCalculator {

    /** 速度(km/h) / MET 交替存放，按速度升序 */
    private val TABLE = doubleArrayOf(
        5.0, 4.8,
        6.4, 6.0,
        8.0, 8.3,
        9.7, 9.8,
        11.3, 11.0,
        12.9, 11.8,
        14.5, 12.3,
        16.1, 12.8,
        17.7, 14.5,
        19.3, 16.0
    )

    /** 按速度查 MET，速度非法或越界时收敛到表两端 */
    fun metForSpeedKmh(speedKmh: Double): Double {
        if (speedKmh.isNaN()) return TABLE[1]
        val pointCount = TABLE.size / 2
        val maxSpeed = TABLE[(pointCount - 1) * 2]
        if (speedKmh <= TABLE[0]) return TABLE[1]
        if (speedKmh >= maxSpeed) return TABLE[(pointCount - 1) * 2 + 1]

        var i = 0
        while (i < pointCount - 2 && speedKmh >= TABLE[(i + 1) * 2]) i++
        val x0 = TABLE[i * 2]
        val y0 = TABLE[i * 2 + 1]
        val x1 = TABLE[(i + 1) * 2]
        val y1 = TABLE[(i + 1) * 2 + 1]
        return y0 + (y1 - y0) * (speedKmh - x0) / (x1 - x0)
    }

    /**
     * 消耗卡路里。
     *
     * @param weightKg 体重（kg），<=0 时返回 0
     * @param speedKmh 平均速度（km/h）
     * @param hours    运动时长（小时），<=0 时返回 0
     */
    fun calories(weightKg: Double, speedKmh: Double, hours: Double): Double {
        if (weightKg <= 0.0 || hours <= 0.0) return 0.0
        return metForSpeedKmh(speedKmh) * weightKg * hours
    }

    /**
     * 由「距离 + 时长」直接估算消耗，内部换算平均速度。
     *
     * 注意单位：durationMs 是毫秒，先除以 1000 得秒，
     * 否则 m/ms 会让速度小 1000 倍、全部落到 MET 最低档。
     */
    fun caloriesForRun(weightKg: Double, distanceMeters: Double, durationMs: Long): Double {
        if (durationMs <= 0L) return 0.0
        val speedKmh = distanceMeters / (durationMs / 1000.0) * 3.6
        return calories(weightKg, speedKmh, durationMs / 3_600_000.0)
    }
}
