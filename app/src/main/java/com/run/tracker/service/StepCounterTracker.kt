package com.run.tracker.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.run.tracker.util.CadenceSmoother
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 步频追踪：使用 Step Counter（计步传感器）计算步频与本次跑步步数。
 *
 * Step Counter 传感器提供的是「开机以来」的累计步数，因此：
 * - 首次收到事件时记录基线，[steps] 输出「本次跑步步数 = 累计步数 - 基线」；
 * - 步频交给 [CadenceSmoother] 做滑动窗口 + 平滑，并由 1 秒定时器驱动刷新，
 *   这样即使传感器没有新事件（停下来），数值也会按预期衰减。
 */
class StepCounterTracker(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** 本次跑步步频（步/分钟） */
    private val _cadence = MutableStateFlow(0)
    val cadence: StateFlow<Int> = _cadence

    /** 本次跑步步数（已扣除起跑时的传感器累计值） */
    private val _steps = MutableStateFlow(0L)
    val steps: StateFlow<Long> = _steps

    private val smoother = CadenceSmoother()

    /** 起跑时的传感器累计步数，-1 表示尚未记录基线 */
    private var baselineSteps = -1L
    private var tickJob: Job? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
            val totalSteps = event.values[0].toLong()
            val now = SystemClock.elapsedRealtime()

            // 第一帧只记录基线，本次跑步从 0 步开始
            if (baselineSteps < 0) {
                baselineSteps = totalSteps
                _steps.value = 0L
                return
            }

            _steps.value = (totalSteps - baselineSteps).coerceAtLeast(0L)
            smoother.onStep(now, totalSteps)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        reset()
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            // 无计步传感器时保持 0，保证应用可运行
            _cadence.value = 0
            return
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)

        // 每秒刷新一次步频：传感器无新事件时也要让数值衰减
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                _cadence.value = smoother.cadence(SystemClock.elapsedRealtime())
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        tickJob?.cancel()
        tickJob = null
        reset()
    }

    /** 清空基线、采样与输出值（每次开始记录前调用） */
    fun reset() {
        smoother.reset()
        baselineSteps = -1L
        _steps.value = 0L
        _cadence.value = 0
    }
}
