package com.run.tracker.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跑步会话核心统计逻辑的单元测试：
 * 距离累加与噪声过滤、暂停语义、心率 / 步频聚合、状态流转。
 */
class RunSessionTest {

    /** 纬度相差 0.00002 度约等于 2.2239 米，1 秒间隔对应约 2.22 m/s 的正常跑步速度 */
    private val stepLat = 0.00002

    /** 位移（米）-> 纬度度数（近似） */
    private fun lat(meters: Double): Double = meters / 111_194.93

    @Test
    fun `初始状态为空会话`() {
        val session = RunSession()
        assertEquals(RunState.IDLE, session.state)
        assertEquals(0.0, session.distanceMeters, 1e-9)
        assertEquals(0.0, session.avgSpeedMs(), 1e-9)
        assertEquals(0, session.avgHeartRate())
        assertEquals(0, session.avgCadence())
        assertEquals(0L, session.totalSteps)
        assertTrue(session.points.isEmpty())
    }

    @Test
    fun `开始后进入运行状态并清空历史数据`() {
        val session = RunSession()
        session.start()
        assertEquals(RunState.RUNNING, session.state)
        assertTrue(session.startTimeMs > 0)
        assertTrue(session.points.isEmpty())
        assertEquals(0.0, session.distanceMeters, 1e-9)
    }

    @Test
    fun `首个定位点只作为参考不累加距离`() {
        val session = RunSession()
        session.start()
        session.onLocation(0.0, 0.0, 0.0, 0L)
        assertEquals(0.0, session.distanceMeters, 1e-9)
        assertEquals(1, session.points.size)
    }

    @Test
    fun `相邻定位点逐段累加距离`() {
        val session = RunSession()
        session.start()
        session.onLocation(0.0, 0.0, 0.0, 0L)
        session.onLocation(stepLat, 0.0, 0.0, 1_000L)
        session.onLocation(stepLat * 2, 0.0, 0.0, 2_000L)

        assertEquals(4.4478, session.distanceMeters, 0.01)
        assertEquals(2.2239, session.currentSpeedMs, 0.01)
        assertEquals(3, session.points.size)
    }

    @Test
    fun `精度过差的定位点被丢弃`() {
        val session = RunSession()
        session.start()
        // 室内常见的 50 米精度点：既不入轨迹，也不计距离
        session.onLocation(0.0, 0.0, 0.0, 0L, accuracyMeters = 50.0)

        assertTrue(session.points.isEmpty())
        assertEquals(0.0, session.distanceMeters, 1e-9)
    }

    @Test
    fun `精度正常的定位点正常记录并累计距离`() {
        val session = RunSession()
        session.start()
        // 每秒 3 米、共 40 秒，精度 3 米
        var latValue = 0.0
        var time = 0L
        repeat(41) {
            session.onLocation(latValue, 0.0, 0.0, time, accuracyMeters = 3.0)
            latValue += 3.0 / 111_194.93
            time += 1_000L
        }
        assertEquals(41, session.points.size)
        // 结束时会补上最后不足门槛的尾段
        session.finish()
        // 40 段 x 3 米 = 120 米
        assertEquals(120.0, session.distanceMeters, 2.0)
    }

    @Test
    fun `原地抖动不会累积距离`() {
        val session = RunSession()
        session.start()
        // 在 ±1 米之间来回抖动：位置经平滑后几乎不动，不会累加
        session.onLocation(0.0, 0.0, 0.0, 0L, accuracyMeters = 5.0)
        session.onLocation(lat(1.0), 0.0, 0.0, 1_000L, accuracyMeters = 5.0)
        session.onLocation(lat(-1.0), 0.0, 0.0, 2_000L, accuracyMeters = 5.0)
        session.onLocation(lat(1.0), 0.0, 0.0, 3_000L, accuracyMeters = 5.0)
        session.onLocation(lat(-1.0), 0.0, 0.0, 4_000L, accuracyMeters = 5.0)

        assertEquals(0.0, session.distanceMeters, 1e-9)
    }

    @Test
    fun `慢速移动仍能累计距离`() {
        val session = RunSession()
        session.start()
        // 每秒 3 米、精度 10 米（门槛 5 米）、共 30 秒
        var latValue = 0.0
        var time = 0L
        repeat(31) {
            session.onLocation(latValue, 0.0, 0.0, time, accuracyMeters = 10.0)
            latValue += 3.0 / 111_194.93
            time += 1_000L
        }
        // 30 段 x 3 米 = 90 米
        assertEquals(90.0, session.distanceMeters, 10.0)
    }

    @Test
    fun `正常跑动时门槛不影响距离累计`() {
        val session = RunSession()
        session.start()
        // 每秒 4 米、精度 5 米、共 30 秒
        var latValue = 0.0
        var time = 0L
        repeat(31) {
            session.onLocation(latValue, 0.0, 0.0, time, accuracyMeters = 5.0)
            latValue += 4.0 / 111_194.93
            time += 1_000L
        }
        // 30 段 x 4 米 = 120 米
        assertEquals(120.0, session.distanceMeters, 10.0)
    }

    /** 用合成轨迹 + 高斯噪声跑一段，返回距离误差百分比 */
    private fun simulateWithNoise(session: RunSession, noiseSigma: Double, seconds: Int = 1000): Double {
        val random = java.util.Random(7)
        val stepMeters = 3.0
        var trueDistance = 0.0
        var time = 0L
        session.onLocation(0.0, 0.0, 0.0, time, accuracyMeters = noiseSigma * 4)
        repeat(seconds) {
            trueDistance += stepMeters
            time += 1_000L
            val latNoise = random.nextGaussian() * noiseSigma
            val lonNoise = random.nextGaussian() * noiseSigma
            session.onLocation(
                lat(trueDistance + latNoise),
                lat(lonNoise),
                0.0,
                time,
                accuracyMeters = noiseSigma * 4
            )
        }
        val error = (session.distanceMeters - trueDistance) / trueDistance * 100.0
        println("noise=${noiseSigma}m 误差=%.2f%% 测得=%.0fm 真实=%.0fm".format(error, session.distanceMeters, trueDistance))
        return error
    }

    @Test
    fun `模拟 GPS 噪声下的距离误差应小于百分之一`() {
        val session = RunSession()
        session.start()
        val error = simulateWithNoise(session, noiseSigma = 1.0)
        assertTrue("σ=1m 时误差应小于 1%%，实际 %.2f%%".format(error), error < 1.0)
    }

    @Test
    fun `较强 GPS 噪声下距离误差应小于百分之三`() {
        val session = RunSession()
        session.start()
        val error = simulateWithNoise(session, noiseSigma = 2.0)
        assertTrue("σ=2m 时误差应小于 3%%，实际 %.2f%%".format(error), error < 3.0)
    }

    @Test
    fun `单点跳变不会抬高最大速度`() {
        val session = RunSession()
        session.start()
        session.onLocation(0.0, 0.0, 0.0, 0L)
        // 1 秒内漂移约 8.4 米（约 30 km/h），窗口跨度不足 5 秒，不计入最大速度
        session.onLocation(0.0000755, 0.0, 0.0, 1_000L)
        assertEquals(0.0, session.maxSpeedMs, 1e-9)

        // 之后原地停留到 11 秒，窗口跨度足够，但窗口内平均速度接近 0
        session.onLocation(0.0000755, 0.0, 0.0, 11_000L)
        assertTrue(
            "最大速度应被窗口削平，实际 ${session.maxSpeedMs}",
            session.maxSpeedMs < 1.5
        )
    }

    @Test
    fun `匀速跑动时最大速度接近真实速度`() {
        val session = RunSession()
        session.start()
        // 每 2 秒前进约 4 米，即 2 m/s
        var timeMs = 0L
        var lat = 0.0
        repeat(10) {
            session.onLocation(lat, 0.0, 0.0, timeMs)
            lat += 0.000036
            timeMs += 2_000L
        }

        assertEquals(2.0, session.maxSpeedMs, 0.15)
    }

    @Test
    fun `瞬时超速的定位跳变被丢弃`() {
        val session = RunSession()
        session.start()
        session.onLocation(0.0, 0.0, 0.0, 0L)
        // 1 秒跳 0.01 度（约 1.1 公里），速度约 1112 m/s，远超阈值
        session.onLocation(0.01, 0.0, 0.0, 1_000L)

        assertEquals(0.0, session.distanceMeters, 1e-9)
        assertEquals(0.0, session.currentSpeedMs, 1e-9)
        assertEquals(0.0, session.maxSpeedMs, 1e-9)
        // 噪声点仍会被记录到轨迹中（用于绘图），只是不计距离
        assertEquals(2, session.points.size)
    }

    @Test
    fun `小于半米的定位漂移不累加`() {
        val session = RunSession()
        session.start()
        session.onLocation(0.0, 0.0, 0.0, 0L)
        // 0.000001 度纬度约 0.11 米，低于 0.5 米阈值
        session.onLocation(0.000001, 0.0, 0.0, 1_000L)

        assertEquals(0.0, session.distanceMeters, 1e-9)
    }

    @Test
    fun `暂停后不再记录定位点`() {
        val session = RunSession()
        session.start()
        session.onLocation(0.0, 0.0, 0.0, 0L)
        session.pause()
        session.onLocation(stepLat, 0.0, 0.0, 1_000L)

        assertEquals(1, session.points.size)
        assertEquals(0.0, session.distanceMeters, 1e-9)
    }

    @Test
    fun `暂停期间计时冻结`() {
        val session = RunSession()
        session.start()
        session.pause()
        val elapsed = session.elapsedMs()
        val avgSpeed = session.avgSpeedMs()

        Thread.sleep(30)

        assertEquals(elapsed, session.elapsedMs())
        assertEquals(avgSpeed, session.avgSpeedMs(), 1e-9)
    }

    @Test
    fun `恢复后的第一个定位点不计入暂停期间的位移`() {
        val session = RunSession()
        session.start()
        session.onLocation(0.0, 0.0, 0.0, 0L)
        session.onLocation(stepLat, 0.0, 0.0, 1_000L)
        val distanceBeforePause = session.distanceMeters

        session.pause()
        session.resume()
        // 暂停期间移动到 1 公里外，恢复后第一个点只作为新参考
        session.onLocation(0.01, 0.0, 0.0, 60_000L)

        assertEquals(distanceBeforePause, session.distanceMeters, 1e-9)
        assertEquals(0.0, session.currentSpeedMs, 1e-9)
    }

    @Test
    fun `结束会话后状态回到空闲并保留轨迹`() {
        val session = RunSession()
        session.start()
        session.onLocation(0.0, 0.0, 0.0, 0L)
        session.onLocation(stepLat, 0.0, 0.0, 1_000L)

        session.finish()

        assertEquals(RunState.IDLE, session.state)
        assertTrue(session.endTimeMs > 0)
        assertEquals(2, session.points.size)
    }

    @Test
    fun `心率累加平均值与峰值`() {
        val session = RunSession()
        session.start()
        session.onLocation(0.0, 0.0, 0.0, 0L)

        session.onHeartRate(150)
        assertEquals(150, session.currentHeartRate)
        assertEquals(150, session.points.last().heartRate)

        session.onHeartRate(120)
        assertEquals(135, session.avgHeartRate())
        assertEquals(150, session.maxHeartRate)
    }

    @Test
    fun `非正心率被忽略`() {
        val session = RunSession()
        session.start()
        session.onHeartRate(0)
        session.onHeartRate(-5)

        assertEquals(0, session.currentHeartRate)
        assertEquals(0, session.avgHeartRate())
    }

    @Test
    fun `步频累加平均值`() {
        val session = RunSession()
        session.start()
        session.onLocation(0.0, 0.0, 0.0, 0L)

        session.onCadence(180)
        assertEquals(180, session.currentCadence)
        assertEquals(180, session.points.last().cadence)

        // 步频为 0 表示停下来，界面应随之归零，但不影响平均值
        session.onCadence(0)
        assertEquals(0, session.currentCadence)

        session.onCadence(170)
        assertEquals(175, session.avgCadence())
    }

    @Test
    fun `记录本次跑步步数`() {
        val session = RunSession()
        session.start()
        session.onSteps(42L)

        assertEquals(42L, session.totalSteps)
    }

    @Test
    fun `刚开始跑步时卡路里接近 0`() {
        val session = RunSession()
        session.start()
        assertTrue(session.calories() >= 0.0)
        assertTrue(session.calories() < 0.1)
    }
}

