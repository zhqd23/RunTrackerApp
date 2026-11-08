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
        /** 精度差于该值（米）的定位点直接丢弃：20 米以上的点对距离贡献的多是噪声 */
        private const val MAX_ACCURACY_METERS = 15.0
        /** 距离累加门槛随定位精度放大：精度 ×2，配合下面的最小值 */
        private const val ACCURACY_DISTANCE_FACTOR = 2.0
        /**
         * 有精度信息时的最小累加间距（米）。
         * 真机 8km 轨迹离线回放：门槛 15m -> 8.07km（与其他软件一致），
         * 门槛 8m -> 8.27km（偏长 2.4%）。间距越大，噪声造成的相对误差越小。
         */
        private const val MIN_DISTANCE_WITH_ACCURACY = 15.0
        /** 门槛上限，避免精度很差时完全无法累计距离 */
        private const val MAX_MIN_DISTANCE_M = 25.0
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

    /** 定位精度累计，用于诊断「距离偏长」是否由 GPS 质量引起 */
    private var accuracySum: Double = 0.0
    private var accuracyCount: Int = 0

    var currentCadence: Int = 0
        private set
    private var cadenceSum: Long = 0
    private var cadenceCount: Int = 0

    var totalSteps: Long = 0
        private set

    /** 轨迹点：由多个采集线程写入（定位 / 心率 / 步频），必须加锁访问 */
    private val pointsLock = Any()
    private val mutablePoints = mutableListOf<TrackPoint>()

    /**
     * 轨迹点快照。
     * 返回的是复制后的只读列表，UI 与落库都在其他线程读取，
     * 直接用可变列表会出现「写入过程中被遍历」的并发问题。
     */
    val points: List<TrackPoint>
        get() = synchronized(pointsLock) { mutablePoints.toList() }

    /** 轨迹点数量（避免为了取长度复制整个列表） */
    val pointCount: Int
        get() = synchronized(pointsLock) { mutablePoints.size }

    private var lastLocation: Pair<Double, Double>? = null
    private var lastLocationTimeMs: Long = 0L
    /** 最近一次采样点（未必被接受），用于结束补足尾段距离 */
    private var lastSeenLocation: Pair<Double, Double>? = null

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
        synchronized(pointsLock) { mutablePoints.clear() }
        lastLocation = null
        lastSeenLocation = null
        distanceMeters = 0.0
        maxSpeedMs = 0.0
        currentSpeedMs = 0.0
        currentHeartRate = 0
        maxHeartRate = 0
        heartRateSum = 0
        heartRateCount = 0
        accuracySum = 0.0
        accuracyCount = 0
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
        // 收尾：最后一段位移可能因为不足门槛而没被计入，结束补上，
        // 否则每次跑步都会系统性少算最后 10~20 米
        appendTailDistance()
        state = RunState.IDLE
        endTimeMs = System.currentTimeMillis()
    }

    /** 把「最后一个被接受的点」到「最后一个采样点」之间的位移补进总距离 */
    private fun appendTailDistance() {
        val reference = lastLocation ?: return
        val tail = lastSeenLocation ?: return
        val delta = LocationUtils.haversineDistanceMeters(
            reference.first, reference.second, tail.first, tail.second
        )
        if (delta >= MIN_POINT_DISTANCE_M && delta < 100.0) {
            distanceMeters += delta
        }
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

        if (accuracyMeters > 0.0) {
            accuracySum += accuracyMeters
            accuracyCount++
        }

        lastSeenLocation = lat to lon

        // 抖动过滤门槛：随定位精度放大，并保证一个足够大的最小位移量。
        // 距离误差约与 σ²/(2d) 成正比（d 为相邻累加点间距），
        // 用真机 8km 轨迹离线回放验证过：门槛 15m 时结果 8.07km，
        // 与其他软件一致；门槛 8m 时 8.27km，偏长约 2.4%。
        val minDistance = if (accuracyMeters > 0.0) {
            (accuracyMeters * ACCURACY_DISTANCE_FACTOR)
                .coerceIn(MIN_DISTANCE_WITH_ACCURACY, MAX_MIN_DISTANCE_M)
        } else {
            MIN_POINT_DISTANCE_M
        }

        val prev = lastLocation
        var accepted = true
        if (prev != null) {
            val delta = LocationUtils.haversineDistanceMeters(
                prev.first, prev.second, lat, lon
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
            lastLocation = lat to lon
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
        synchronized(pointsLock) { mutablePoints.add(point) }

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
        synchronized(pointsLock) {
            if (mutablePoints.isNotEmpty()) {
                val last = mutablePoints[mutablePoints.size - 1]
                mutablePoints[mutablePoints.size - 1] = last.copy(heartRate = hr)
            }
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
        synchronized(pointsLock) {
            if (mutablePoints.isNotEmpty()) {
                val last = mutablePoints[mutablePoints.size - 1]
                mutablePoints[mutablePoints.size - 1] = last.copy(cadence = cad)
            }
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

    /** 本次跑步的平均定位精度（米），没有数据时返回 0 */
    fun avgAccuracyMeters(): Double =
        if (accuracyCount == 0) 0.0 else accuracySum / accuracyCount

    fun avgCadence(): Int =
        if (cadenceCount == 0) 0 else (cadenceSum / cadenceCount).toInt()

    /** 卡路里估算：MET * 体重(kg) * 时长(h) */
    fun calories(): Double {
        val hours = elapsedMs() / 3_600_000.0
        return 9.8 * weightKg * hours
    }
}

