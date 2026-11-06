package com.run.tracker.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.run.tracker.util.AccelCadenceEstimator
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
 * 三个传感器分工：
 * - [Sensor.TYPE_ACCELEROMETER]：**步频主数据源**。计步传感器每秒最多只回调 2~3 次
 *   （实测封顶约 153 步/分），跑步步频 170+ 会被硬件截断，因此用加速度计自行检测迈步；
 * - [Sensor.TYPE_STEP_COUNTER]：开机以来的累计步数，用来算「本次跑步步数」；
 *   很多机型延迟/批量上报（实测要走十几秒才有第一帧）；
 * - [Sensor.TYPE_STEP_DETECTOR]：每步回调一次，作为加速度计不可用时的步频兜底数据源。
 *
 * 加速度计估计由 [AccelCadenceEstimator] 完成，计步传感器路径由 [CadenceSmoother] 平滑，
 * 两者都由 1 秒定时器刷新，停下后数值会衰减归零。
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

    /** 加速度计步频估计（主数据源） */
    private val accelEstimator = AccelCadenceEstimator()

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
    /** 设备是否有加速度计 */
    private var hasAccelerometer = false

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

    /** 加速度计：步频主数据源（不受计步传感器每秒 2~3 次的上限限制） */
    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            accelEstimator.onSample(
                SystemClock.elapsedRealtime(),
                event.values[0],
                event.values[1],
                event.values[2]
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        reset()

        val counter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        hasCounter = counter != null
        hasDetector = detector != null
        hasAccelerometer = accelerometer != null

        if (counter == null && detector == null && accelerometer == null) {
            // 没有可用传感器：保持 0，保证应用可运行
            _cadence.value = 0
            return
        }

        counter?.let {
            sensorManager.registerListener(counterListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        detector?.let {
            sensorManager.registerListener(detectorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        // 20ms（50Hz）：3Hz 步频下每步约 16 个采样点，足够做迟滞检测
        accelerometer?.let {
            sensorManager.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // 每秒刷新一次步频：传感器无新事件时也要让数值衰减
        var tick = 0
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                val accelCadence = accelEstimator.cadence(now)
                val stepCadence = (smoother.cadence(now) * calibrator.factor()).roundToInt()
                // 有加速度计时只用它：两种数据源量级差很多（实测 190 vs 65），
                // 来回切换会让瞬时步频看起来「飘忽」
                val value = if (hasAccelerometer) accelCadence else stepCadence
                _cadence.value = value.coerceIn(0, CadenceSmoother.MAX_CADENCE)

                // 每 5 秒输出一次，便于在 Logcat 观察数据源与校准
                if (++tick % 5 == 0) {
                    Log.d(
                        TAG,
                        "cadence accel=$accelCadence step=$stepCadence -> ${_cadence.value}"
                    )
                }
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(counterListener)
        sensorManager.unregisterListener(detectorListener)
        sensorManager.unregisterListener(accelListener)
        tickJob?.cancel()
        tickJob = null
        reset()
    }

    /** 清空基线、采样与输出值（每次开始记录前调用） */
    fun reset() {
        smoother.reset()
        calibrator.reset()
        accelEstimator.reset()
        baselineSteps = -1L
        detectorSteps = 0L
        _steps.value = 0L
        _cadence.value = 0
    }
}
