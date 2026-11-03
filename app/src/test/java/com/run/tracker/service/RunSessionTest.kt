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
        assertEquals(2.2239, session.maxSpeedMs, 0.01)
        assertEquals(3, session.points.size)
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

        session.onCadence(0)
        assertEquals(180, session.currentCadence)

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
