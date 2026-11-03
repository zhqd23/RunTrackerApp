package com.run.tracker.service

import com.run.tracker.data.model.TrackPoint
import com.run.tracker.util.LocationUtils

/** 跑步状态 */
enum class RunState { IDLE, RUNNING, PAUSED }

/**
 * 跑步会话：维护一次跑步过程中的全部实时指标。
 *
 * 统计口径：
 * - 距离：Haversine 公式逐段累加相邻 GPS 点；
 * - 即时速度：最近两点距离 / 时间差；
 * - 平均速度：总距离 / 总用时（扣除暂停）；
 * - 最大速度：全部即时速度的最大值；
 * - 卡路里：MET 代谢当量估算（跑步 MET≈9.8）。
 */
class RunSession(private val weightKg: Double = DEFAULT_WEIGHT_KG) {

    companion object {
        const val DEFAULT_WEIGHT_KG = 65.0
        /** 超过该速度（m/s，约 43 km/h）的定位点视为噪声 */
        private const val MAX_PLAUSIBLE_SPEED_MS = 12.0
        /** 低于该距离（米）的定位漂移不累加 */
        private const val MIN_POINT_DISTANCE_M = 0.5
    }

    var state: RunState = RunState.IDLE
        private set

    var startTimeMs: Long = 0L
        private set
    var endTimeMs: Long = 0L
        private set

    private var runningStartTimeMs: Long = 0L
    private var accumulatedRunningMs: Long = 0L

    var distanceMeters: Double = 0.0
        private set
    var maxSpeedMs: Double = 0.0
        private set
    var currentSpeedMs: Double = 0.0
        private set

    var currentHeartRate: Int = 0
        private set
    var maxHeartRate: Int = 0
        private set
    private var heartRateSum: Long = 0
    private var heartRateCount: Int = 0

    var currentCadence: Int = 0
        private set
    private var cadenceSum: Long = 0
    private var cadenceCount: Int = 0

    var totalSteps: Long = 0
        private set

    val points: MutableList<TrackPoint> = mutableListOf()

    private var lastLocation: Pair<Double, Double>? = null
    private var lastLocationTimeMs: Long = 0L
    private var lastPoint: TrackPoint? = null

    // ---------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------

    fun start() {
        state = RunState.RUNNING
        startTimeMs = System.currentTimeMillis()
        runningStartTimeMs = startTimeMs
        accumulatedRunningMs = 0L
        points.clear()
        lastLocation = null
        lastPoint = null
        distanceMeters = 0.0
        maxSpeedMs = 0.0
        currentSpeedMs = 0.0
        currentHeartRate = 0
        maxHeartRate = 0
        heartRateSum = 0
        heartRateCount = 0
        currentCadence = 0
        cadenceSum = 0
        cadenceCount = 0
        totalSteps = 0
    }

    fun pause() {
        if (state != RunState.RUNNING) return
        accumulatedRunningMs += System.currentTimeMillis() - runningStartTimeMs
        state = RunState.PAUSED
        // 清空上一参考点：暂停期间的位移不计入距离，恢复后从新点位重新起算
        lastLocation = null
        lastLocationTimeMs = 0L
        currentSpeedMs = 0.0
    }

    fun resume() {
        if (state != RunState.PAUSED) return
        runningStartTimeMs = System.currentTimeMillis()
        state = RunState.RUNNING
    }

    fun finish() {
        if (state == RunState.RUNNING) {
            accumulatedRunningMs += System.currentTimeMillis() - runningStartTimeMs
        }
        state = RunState.IDLE
        endTimeMs = System.currentTimeMillis()
    }

    // ---------------------------------------------------------------------
    // 数据输入
    // ---------------------------------------------------------------------

    fun onLocation(lat: Double, lon: Double, altitude: Double, timeMs: Long) {
        if (state != RunState.RUNNING) return

        val prev = lastLocation
        if (prev != null) {
            val delta = LocationUtils.haversineDistanceMeters(
                prev.first, prev.second, lat, lon
            )
            val dtSec = (timeMs - lastLocationTimeMs) / 1000.0
            val speed = if (dtSec > 0) delta / dtSec else 0.0
            if (speed <= MAX_PLAUSIBLE_SPEED_MS && delta >= MIN_POINT_DISTANCE_M) {
                distanceMeters += delta
                currentSpeedMs = speed
                if (speed > maxSpeedMs) maxSpeedMs = speed
            } else {
                // 噪声点：不累加距离，即时速度视为 0
                currentSpeedMs = 0.0
            }
        } else {
            currentSpeedMs = 0.0
        }
        lastLocation = lat to lon
        lastLocationTimeMs = timeMs

        val point = TrackPoint(
            lat = lat,
            lon = lon,
            timestampMs = timeMs,
            altitude = altitude,
            speedMs = currentSpeedMs,
            heartRate = currentHeartRate,
            cadence = currentCadence
        )
        points.add(point)
        lastPoint = point
    }

    fun onHeartRate(hr: Int) {
        if (hr <= 0) return
        currentHeartRate = hr
        heartRateSum += hr
        heartRateCount++
        if (hr > maxHeartRate) maxHeartRate = hr
        // 更新最后一个点的心率
        if (lastPoint != null && points.isNotEmpty()) {
            val last = points[points.size - 1]
            points[points.size - 1] = last.copy(heartRate = hr)
        }
    }

    fun onCadence(cad: Int) {
        if (cad <= 0) return
        currentCadence = cad
        cadenceSum += cad
        cadenceCount++
        if (lastPoint != null && points.isNotEmpty()) {
            val last = points[points.size - 1]
            points[points.size - 1] = last.copy(cadence = cad)
        }
    }

    fun onSteps(steps: Long) {
        totalSteps = steps
    }

    // ---------------------------------------------------------------------
    // 指标计算
    // ---------------------------------------------------------------------

    /** 当前已用时长（毫秒，扣除暂停） */
    fun elapsedMs(): Long {
        return when (state) {
            RunState.RUNNING ->
                accumulatedRunningMs + (System.currentTimeMillis() - runningStartTimeMs)
            else -> accumulatedRunningMs
        }
    }

    /** 平均速度（米/秒 = 总距离 / 总用时） */
    fun avgSpeedMs(): Double {
        val elapsedSec = elapsedMs() / 1000.0
        return if (elapsedSec > 0) distanceMeters / elapsedSec else 0.0
    }

    fun avgHeartRate(): Int =
        if (heartRateCount == 0) 0 else (heartRateSum / heartRateCount).toInt()

    fun avgCadence(): Int =
        if (cadenceCount == 0) 0 else (cadenceSum / cadenceCount).toInt()

    /** 卡路里估算：MET * 体重(kg) * 时长(h) */
    fun calories(): Double {
        val hours = elapsedMs() / 3_600_000.0
        return 9.8 * weightKg * hours
    }
}
