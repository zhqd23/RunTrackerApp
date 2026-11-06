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
        /** 精度差于该值（米）的定位点直接丢弃：室内漂移常见 30 米以上 */
        private const val MAX_ACCURACY_METERS = 30.0
        /** 距离累加门槛随定位精度放大：精度 5 米时门槛约 2.5 米，过滤掉抖动累积 */
        private const val ACCURACY_DISTANCE_FACTOR = 0.5
        /** 有精度信息时的最小累加间距：间距越大，噪声造成的相对误差越小 */
        private const val MIN_DISTANCE_WITH_ACCURACY = 5.0
        /** 门槛上限，避免精度很差时完全无法累计距离 */
        private const val MAX_MIN_DISTANCE_M = 8.0
        /**
         * 位置 alpha-beta 滤波系数。
         * alpha 决定「向实测值修正」的力度，beta 决定速度估计的跟随速度；
         * 0.5 / 0.2 时对 1Hz 的 GPS 噪声有明显抑制，同时对加减速响应及时。
         */
        private const val POSITION_FILTER_ALPHA = 0.5
        private const val POSITION_VELOCITY_BETA = 0.2
        /** 最大速度的滑动窗口长度（毫秒） */
        private const val SPEED_WINDOW_MS = 10_000L
        /** 窗口跨度不足该值时不计最大速度，避免单点跳变把峰值抬高 */
        private const val MAX_SPEED_MIN_WINDOW_MS = 5_000L
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

    /** 滤波后的位置（用于距离计算，抑制 GPS 抖动带来的系统性偏长） */
    private var filteredLat: Double = 0.0
    private var filteredLon: Double = 0.0
    /** 滤波估计出的速度（度/秒），用于位置预测 */
    private var velocityLat: Double = 0.0
    private var velocityLon: Double = 0.0
    private var lastFilterTimeMs: Long = 0L
    private var hasFilteredPosition = false

    /** (时间戳, 累计距离) 采样，用于滑动窗口计算平滑速度 */
    private val speedSamples = ArrayDeque<Pair<Long, Double>>()

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
        hasFilteredPosition = false
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
        speedSamples.clear()
    }

    fun pause() {
        if (state != RunState.RUNNING) return
        accumulatedRunningMs += System.currentTimeMillis() - runningStartTimeMs
        state = RunState.PAUSED
        // 清空上一参考点：暂停期间的位移不计入距离，恢复后从新点位重新起算
        lastLocation = null
        lastLocationTimeMs = 0L
        hasFilteredPosition = false
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

    fun onLocation(
        lat: Double,
        lon: Double,
        altitude: Double,
        timeMs: Long,
        accuracyMeters: Double = 0.0
    ) {
        if (state != RunState.RUNNING) return
        // 精度过差的点直接丢弃（0 表示未知精度，按可用处理）
        if (accuracyMeters > MAX_ACCURACY_METERS) return

        // 抖动过滤门槛：既随定位精度放大，也保证一个最小位移量。
        // 距离误差约与 σ²/(2d) 成正比，d 是相邻累加点间距；
        // 把 d 从 3 米提到 5~6 米，单靠这一项就能把偏差降一半以上。
        val minDistance = if (accuracyMeters > 0.0) {
            (accuracyMeters * ACCURACY_DISTANCE_FACTOR)
                .coerceIn(MIN_DISTANCE_WITH_ACCURACY, MAX_MIN_DISTANCE_M)
        } else {
            MIN_POINT_DISTANCE_M
        }

        // 位置先用 alpha-beta 滤波再算距离。
        // 距离是逐段长度累加，噪声会让每段都系统性偏长（误差 ≈ σ²/2d），
        // 平滑把 σ 压小后这部分偏差按平方下降。
        // 与单纯指数平滑的区别：这里用速度预测位置，匀速跑动时**没有稳态滞后**，
        // 因此不会像 EMA 那样在开跑最初十几秒少算一截距离。
        if (accuracyMeters > 0.0) {
            if (!hasFilteredPosition) {
                filteredLat = lat
                filteredLon = lon
                velocityLat = 0.0
                velocityLon = 0.0
                lastFilterTimeMs = timeMs
                hasFilteredPosition = true
            } else {
                val dtSec = (timeMs - lastFilterTimeMs) / 1000.0
                if (dtSec > 0) {
                    // 1) 用估计速度预测当前位置
                    val predictedLat = filteredLat + velocityLat * dtSec
                    val predictedLon = filteredLon + velocityLon * dtSec
                    // 2) 用实测值修正位置
                    val residualLat = lat - predictedLat
                    val residualLon = lon - predictedLon
                    filteredLat = predictedLat + POSITION_FILTER_ALPHA * residualLat
                    filteredLon = predictedLon + POSITION_FILTER_ALPHA * residualLon
                    // 3) 用修正量更新速度估计
                    velocityLat += POSITION_VELOCITY_BETA * residualLat / dtSec
                    velocityLon += POSITION_VELOCITY_BETA * residualLon / dtSec
                    lastFilterTimeMs = timeMs
                }
            }
        } else {
            // 没有精度信息（例如单元测试或异常数据）时按原始坐标处理
            filteredLat = lat
            filteredLon = lon
            hasFilteredPosition = true
        }

        val prev = lastLocation
        var accepted = true
        if (prev != null) {
            val delta = LocationUtils.haversineDistanceMeters(
                prev.first, prev.second, filteredLat, filteredLon
            )
            val dtSec = (timeMs - lastLocationTimeMs) / 1000.0
            val speed = if (dtSec > 0) delta / dtSec else 0.0
            if (speed <= MAX_PLAUSIBLE_SPEED_MS && delta >= minDistance) {
                distanceMeters += delta
                currentSpeedMs = speed
            } else {
                // 噪声点：不累加距离，即时速度视为 0
                currentSpeedMs = 0.0
                accepted = false
            }
        } else {
            currentSpeedMs = 0.0
        }

        // 只有被接受的点才作为下一次比较的参考：
        // 这样慢速移动（每帧位移小于门槛）仍会在累计位移超过门槛后计入，
        // 而原地抖动则永远不会被累加
        if (accepted) {
            lastLocation = filteredLat to filteredLon
            lastLocationTimeMs = timeMs
        }

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

        updateSmoothedMaxSpeed(timeMs)
    }

    /**
     * 用最近 [SPEED_WINDOW_MS] 内的累计距离计算平滑速度并更新最大速度。
     *
     * 单个定位点的跳变虽然会通过距离阈值过滤掉一部分，但仍可能恰好落在合理区间内
     * （例如 1 秒内漂移 8 m，约 30 km/h）。按窗口跨度平均后这类尖峰会被显著削平，
     * 同时不影响持续奔跑时的真实速度。
     */
    private fun updateSmoothedMaxSpeed(nowMs: Long) {
        speedSamples.addLast(nowMs to distanceMeters)
        while (speedSamples.size > 2 &&
            nowMs - speedSamples.first().first > SPEED_WINDOW_MS
        ) {
            speedSamples.removeFirst()
        }

        val (startTimeMs, startDistance) = speedSamples.first()
        val spanMs = nowMs - startTimeMs
        // 窗口跨度不足时不更新，避免刚开始的瞬时尖峰成为峰值
        if (spanMs < MAX_SPEED_MIN_WINDOW_MS) return

        val speed = (distanceMeters - startDistance) / (spanMs / 1000.0)
        if (speed > maxSpeedMs) maxSpeedMs = speed
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

    /**
     * 步频输入。
     *
     * 与心率不同，步频为 0 是**有效信息**（说明已经停下来），
     * 因此 0 要能把界面上的数值清零，不能像以前那样忽略掉、
     * 让最后一次的正数一直挂在界面上。
     */
    fun onCadence(cad: Int) {
        if (cad < 0) return
        currentCadence = cad
        if (cad > 0) {
            cadenceSum += cad
            cadenceCount++
        }
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
