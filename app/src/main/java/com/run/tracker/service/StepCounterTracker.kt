package com.run.tracker.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 步频追踪：使用 Step Counter（计步传感器）计算步频与本次跑步步数。
 *
 * Step Counter 传感器提供的是「开机以来」的累计步数，因此：
 * - 首次收到事件时记录基线，[steps] 输出「本次跑步步数 = 累计步数 - 基线」；
 * - 步频通过固定时间窗口（10 秒）内的步数增量换算为「步/分钟」。
 */
class StepCounterTracker(private val context: Context) {

    companion object {
        /** 步频统计窗口（毫秒） */
        private const val CADENCE_WINDOW_MS = 10_000L
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** 本次跑步步频（步/分钟） */
    private val _cadence = MutableStateFlow(0)
    val cadence: StateFlow<Int> = _cadence

    /** 本次跑步步数（已扣除起跑时的传感器累计值） */
    private val _steps = MutableStateFlow(0L)
    val steps: StateFlow<Long> = _steps

    /** 起跑时的传感器累计步数，-1 表示尚未记录基线 */
    private var baselineSteps = -1L
    private var windowStartSteps = 0L
    private var windowStartTime = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
            val totalSteps = event.values[0].toLong()
            val now = event.timestamp / 1_000_000L // ns -> ms

            // 第一帧只记录基线，本次跑步从 0 步开始
            if (baselineSteps < 0) {
                baselineSteps = totalSteps
                windowStartSteps = totalSteps
                windowStartTime = now
                _steps.value = 0L
                return
            }

            _steps.value = (totalSteps - baselineSteps).coerceAtLeast(0L)

            val windowDurationMs = now - windowStartTime
            if (windowDurationMs < CADENCE_WINDOW_MS) return

            val stepsInWindow = totalSteps - windowStartSteps
            _cadence.value =
                (stepsInWindow * 60_000.0 / windowDurationMs).toInt().coerceIn(0, 300)
            windowStartTime = now
            windowStartSteps = totalSteps
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        reset()
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            ?: run {
                // 无计步传感器时保持 0，保证应用可运行
                _cadence.value = 0
                return
            }
        sensorManager.registerListener(
            listener, sensor, SensorManager.SENSOR_DELAY_UI
        )
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        reset()
    }

    /** 清空基线、窗口与输出值（每次开始记录前调用） */
    fun reset() {
        baselineSteps = -1L
        windowStartSteps = 0L
        windowStartTime = 0L
        _steps.value = 0L
        _cadence.value = 0
    }
}
