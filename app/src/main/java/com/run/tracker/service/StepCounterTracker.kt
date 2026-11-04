package com.run.tracker.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.run.tracker.util.CadenceSmoother
import com.run.tracker.util.StepCalibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 步频追踪：计算步频与本次跑步步数。
 *
 * 两个传感器分工：
 * - [Sensor.TYPE_STEP_COUNTER]：开机以来的累计步数，用来算「本次跑步步数」。
 *   但很多机型延迟/批量上报（实测这台 OPPO 系机型要走十几秒才有第一帧），
 *   直接拿它算步频会让开头长时间显示 0；
 * - [Sensor.TYPE_STEP_DETECTOR]：每走一步立即回调，用它驱动步频，响应快且连续；
 *   设备没有该传感器时才退化为用 counter 的累计值。
 *
 * 步频统一交给 [CadenceSmoother] 做滑动窗口 + 平滑，并由 1 秒定时器驱动刷新，
 * 这样即使传感器没有新事件（停下来），数值也会按预期衰减。
 */
class StepCounterTracker(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "StepCounterTracker"
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** 本次跑步步频（步/分钟） */
    private val _cadence = MutableStateFlow(0)
    val cadence: StateFlow<Int> = _cadence

    /** 本次跑步步数（已扣除起跑时的传感器累计值） */
    private val _steps = MutableStateFlow(0L)
    val steps: StateFlow<Long> = _steps

    private val smoother = CadenceSmoother()

    /** 自适应校准：某些机型 detector 会漏报，用 counter 的比例修正 */
    private val calibrator = StepCalibrator()

    /** 起跑时的传感器累计步数，-1 表示尚未记录基线 */
    private var baselineSteps = -1L
    /** step_detector 累计的步数（每步 +1） */
    private var detectorSteps = 0L
    /** 设备是否有独立的 step_detector */
    private var hasDetector = false
    /** 设备是否有 step_counter */
    private var hasCounter = false

    private var tickJob: Job? = null

    /** 计步器：负责累计步数；没有 detector 时才兼职喂步频 */
    private val counterListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
            val totalSteps = event.values[0].toLong()

            // 第一帧只记录基线，本次跑步从 0 步开始
            if (baselineSteps < 0) {
                baselineSteps = totalSteps
                _steps.value = 0L
                return
            }

            _steps.value = (totalSteps - baselineSteps).coerceAtLeast(0L)
            if (!hasDetector) {
                smoother.onStep(SystemClock.elapsedRealtime(), totalSteps)
            } else {
                // counter 上报的时刻同时取 detector 的计数，用两者的增量比做校准
                calibrator.onCounterSample(
                    timeMs = SystemClock.elapsedRealtime(),
                    counterSteps = _steps.value,
                    detectorSteps = detectorSteps
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** 步态检测：每走一步回调一次，是步频的主要数据源 */
    private val detectorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return
            detectorSteps += 1
            smoother.onStep(SystemClock.elapsedRealtime(), detectorSteps)
            if (!hasCounter) {
                _steps.value = detectorSteps
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        reset()

        val counter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        hasCounter = counter != null
        hasDetector = detector != null

        if (counter == null && detector == null) {
            // 没有计步相关传感器：保持 0，保证应用可运行
            _cadence.value = 0
            return
        }

        counter?.let {
            sensorManager.registerListener(counterListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        detector?.let {
            sensorManager.registerListener(detectorListener, it, SensorManager.SENSOR_DELAY_UI)
        }

        // 每秒刷新一次步频：传感器无新事件时也要让数值衰减
        var tick = 0
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val raw = smoother.cadence(SystemClock.elapsedRealtime())
                val factor = calibrator.factor()
                val calibrated = (raw * factor).roundToInt()
                _cadence.value = calibrated.coerceIn(0, CadenceSmoother.MAX_CADENCE)
                // 每 5 秒输出一次，便于在 Logcat 观察校准是否生效
                if (++tick % 5 == 0) {
                    Log.d(
                        TAG,
                        "cadence raw=$raw factor=%.2f -> %d".format(factor, _cadence.value)
                    )
                }
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(counterListener)
        sensorManager.unregisterListener(detectorListener)
        tickJob?.cancel()
        tickJob = null
        reset()
    }

    /** 清空基线、采样与输出值（每次开始记录前调用） */
    fun reset() {
        smoother.reset()
        calibrator.reset()
        baselineSteps = -1L
        detectorSteps = 0L
        _steps.value = 0L
        _cadence.value = 0
    }
}
