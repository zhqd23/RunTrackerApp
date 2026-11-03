package com.run.tracker.util

import kotlin.math.roundToInt

/**
 * 步频平滑器：把计步传感器的「累计步数」换算成稳定的「步/分钟」。
 *
 * 直接用固定窗口换算会明显跳动——窗口内多走一步少走一步就差好几拍，
 * 而且传感器是事件驱动的，站着不动时数值会一直停在最后一步。
 * 这里做三件事：
 * 1. 滑动窗口（默认 6 秒）内取首尾采样计算瞬时步频；
 * 2. 用指数移动平均（EMA）平滑，避免数值瞬间跳变；
 * 3. 超过 4 秒没有新步数就逐步衰减到 0，反映「停下来」。
 */
class CadenceSmoother(
    private val windowMs: Long = 6_000L,
    private val staleMs: Long = 4_000L,
    private val alpha: Double = 0.25,
    private val minSpanMs: Long = 2_000L
) {

    companion object {
        /** 步频上限（步/分钟），超过按上限显示 */
        const val MAX_CADENCE = 300
    }

    /** 采样队列：(时间戳, 传感器累计步数) */
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** 平滑后的步频 */
    private var smoothed = 0.0

    /** 记录一次计步采样 */
    fun onStep(timeMs: Long, totalSteps: Long) {
        samples.addLast(timeMs to totalSteps)
        trim(timeMs)
    }

    /** 计算当前应显示的步频（步/分钟） */
    fun cadence(nowMs: Long): Int {
        trim(nowMs)

        val last = samples.lastOrNull()
        if (last == null || nowMs - last.first > staleMs) {
            // 已经停下来：指数衰减，避免从 180 直接掉到 0
            smoothed *= 0.5
            if (smoothed < 5) smoothed = 0.0
            return smoothed.roundToInt().coerceIn(0, MAX_CADENCE)
        }

        if (samples.size < 2) return smoothed.roundToInt().coerceIn(0, MAX_CADENCE)

        val (startTimeMs, startSteps) = samples.first()
        val spanMs = last.first - startTimeMs
        if (spanMs < minSpanMs) return smoothed.roundToInt().coerceIn(0, MAX_CADENCE)

        val instant = (last.second - startSteps) * 60_000.0 / spanMs
        // 首次有有效值直接采用，之后做 EMA，避免从 0 缓慢爬升
        smoothed = if (smoothed <= 0.0) instant else alpha * instant + (1 - alpha) * smoothed
        return smoothed.roundToInt().coerceIn(0, MAX_CADENCE)
    }

    /** 清空全部状态（每次开始记录前调用） */
    fun reset() {
        samples.clear()
        smoothed = 0.0
    }

    private fun trim(nowMs: Long) {
        while (samples.size > 2 && nowMs - samples.first().first > windowMs) {
            samples.removeFirst()
        }
    }
}
