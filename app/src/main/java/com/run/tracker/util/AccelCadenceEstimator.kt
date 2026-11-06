package com.run.tracker.util

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 基于加速度计的步频估计。
 *
 * 为什么不用计步传感器：很多机型的 `step_detector` 每秒最多只回调 2~3 次
 * （实测封顶约 153 步/分），跑步步频 170+ 时会被硬件截断；
 * 加速度计不受这个限制，跑步类 App 普遍用它自己算步频。
 *
 * 算法：
 * 1. 取三轴合加速度模长（与手机朝向无关）；
 * 2. 低通滤波估计重力基线，减去后得到「迈步引起的垂直振动」；
 * 3. 用自适应阈值 + 迟滞（hysteresis）检测上下穿越，一次完整穿越 = 一步；
 * 4. 记录相邻步间隔，取最近若干个的中位/均值换算成「步/分钟」。
 *
 * 迟滞检测比单纯过零点更抗噪，能避免高频抖动被重复计数。
 */
class AccelCadenceEstimator(
    /** 两次迈步的最小间隔，决定可测步频上限（250ms ≈ 240 步/分） */
    private val minIntervalMs: Long = 250L,
    /** 两次迈步的最大间隔，超过说明不是连续跑动 */
    private val maxIntervalMs: Long = 1_800L,
    /** 参与平均的步间隔个数 */
    private val historySize: Int = 8,
    /** 超过这么久没有新步，判定为停止 */
    private val staleMs: Long = 3_000L
) {

    companion object {
        const val MAX_CADENCE = 240
        /** 阈值下限，避免静止时噪声被当成步 */
        private const val MIN_THRESHOLD = 0.35
        /** 输出平滑系数：越小越稳，但跟随变化越慢 */
        private const val OUTPUT_SMOOTHING = 0.4
    }

    /** 重力基线（低通） */
    private var gravityBaseline = 0.0
    /** 信号幅度估计，用于自适应阈值 */
    private var amplitudeEma = 0.0
    /** 迟滞状态：当前是否处于「上半周期」 */
    private var rising = false
    private var lastStepTimeMs = 0L
    private val intervals = ArrayDeque<Long>()
    /** 平滑后的步频输出 */
    private var smoothedCadence = 0.0

    /**
     * 喂入一次加速度采样（单位 m/s²）。
     *
     * 该方法在传感器回调线程执行，而 [cadence] 在 1 秒定时器线程执行，
     * 两者共享 [intervals] 等状态，必须加锁——否则排序时可能读到并发清空的槽位
     * 而抛出 NullPointerException（真机崩溃栈确认过）。
     */
    @Synchronized
    fun onSample(timeMs: Long, x: Float, y: Float, z: Float) {
        val magnitude = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)

        // 低通估计重力（约 0.2 秒时间常数）
        gravityBaseline = if (gravityBaseline <= 0.0) magnitude
        else gravityBaseline * 0.9 + magnitude * 0.1

        val vertical = magnitude - gravityBaseline
        amplitudeEma = amplitudeEma * 0.95 + abs(vertical) * 0.05
        val threshold = (amplitudeEma * 0.6).coerceAtLeast(MIN_THRESHOLD)

        when {
            vertical > threshold && !rising -> rising = true
            vertical < -threshold && rising -> {
                rising = false
                recordStep(timeMs)
            }
        }
    }

    private fun recordStep(timeMs: Long) {
        if (lastStepTimeMs > 0) {
            val interval = timeMs - lastStepTimeMs
            if (interval in minIntervalMs..maxIntervalMs) {
                intervals.addLast(interval)
                while (intervals.size > historySize) intervals.removeFirst()
            }
        }
        lastStepTimeMs = timeMs
    }

    /** 当前步频（步/分钟），0 表示没有检测到连续跑动 */
    @Synchronized
    fun cadence(nowMs: Long): Int {
        if (lastStepTimeMs <= 0) return 0
        if (nowMs - lastStepTimeMs > staleMs) {
            intervals.clear()
            smoothedCadence = 0.0
            return 0
        }
        if (intervals.size < 2) return smoothedCadence.roundToInt()

        val sorted = intervals.sorted()
        val median = sorted[sorted.size / 2]
        val raw = (60_000.0 / median).coerceIn(0.0, MAX_CADENCE.toDouble())
        // 再做一次 EMA，抑制个别间隔抖动造成的瞬时跳变
        smoothedCadence = if (smoothedCadence <= 0.0) raw
        else smoothedCadence * (1 - OUTPUT_SMOOTHING) + raw * OUTPUT_SMOOTHING
        return smoothedCadence.roundToInt().coerceIn(0, MAX_CADENCE)
    }

    @Synchronized
    fun reset() {
        gravityBaseline = 0.0
        amplitudeEma = 0.0
        rising = false
        lastStepTimeMs = 0L
        intervals.clear()
    }
}
