package com.run.tracker.util

/**
 * 步频自适应校准。
 *
 * 部分机型的 `TYPE_STEP_DETECTOR` 会漏报（例如每两步才回调一次），
 * 导致步频显示成实际值的一半左右。
 *
 * 这里用 `TYPE_STEP_COUNTER` 作为基准：它虽然上报滞后，但计数值准确。
 * 关键在于**同一时刻**取两个传感器的读数——每当 counter 上报一次，
 * 就把「counter 当前值」和「此刻 detector 的计数」作为一对样本记录下来，
 * 于是滞后不会影响比例。用最近一个较长窗口内的增量比 [counterΔ / detectorΔ]
 * 得到校准系数，只放大不下调，并限制在 [1.0, 2.5] 之间。
 */
class StepCalibrator(
    /** 参与计算的最短时间跨度，太短时 counter 的滞后会放大误差 */
    private val minSpanMs: Long = 20_000L,
    /** 参与计算的最少 detector 步数，样本太少时比例不可靠 */
    private val minDetectorSteps: Long = 10L,
    /** 保留的样本窗口长度 */
    private val windowMs: Long = 120_000L,
    /** 校准上限：再离谱的比例也按此处理 */
    private val maxFactor: Double = 2.5,
    /** 系数平滑，避免校准值突变 */
    private val alpha: Double = 0.35
) {

    companion object {
        const val MIN_FACTOR = 1.0
    }

    /** 样本：(时间戳, counter 累计步数, 同时刻 detector 累计步数) */
    private val samples = ArrayDeque<Triple<Long, Long, Long>>()

    private var factor = 1.0

    /** 记录一次 counter 上报（必须同时给出此刻的 detector 计数） */
    fun onCounterSample(timeMs: Long, counterSteps: Long, detectorSteps: Long) {
        samples.addLast(Triple(timeMs, counterSteps, detectorSteps))
        while (samples.size > 2 && timeMs - samples.first().first > windowMs) {
            samples.removeFirst()
        }
        updateFactor()
    }

    /** 当前校准系数，1.0 表示不校准 */
    fun factor(): Double = factor

    fun reset() {
        samples.clear()
        factor = 1.0
    }

    private fun updateFactor() {
        val first = samples.firstOrNull() ?: return
        val last = samples.last()

        val spanMs = last.first - first.first
        if (spanMs < minSpanMs) return

        val counterDelta = last.second - first.second
        val detectorDelta = last.third - first.third
        if (detectorDelta < minDetectorSteps) return
        if (detectorDelta <= 0) return

        val raw = counterDelta.toDouble() / detectorDelta
        // 只做放大补偿：比例小于 1 说明 detector 反而多报，不做下调
        val clamped = raw.coerceIn(MIN_FACTOR, maxFactor)

        factor = if (factor == MIN_FACTOR) clamped else alpha * clamped + (1 - alpha) * factor
    }
}
