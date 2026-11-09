package com.run.tracker.data.repository

import com.run.tracker.data.db.RunDatabase
import com.run.tracker.data.db.RunRecordDao
import com.run.tracker.data.db.RunRecordEntity
import com.run.tracker.data.db.RunStatsRow
import com.run.tracker.data.db.RunSummary
import com.run.tracker.util.MetCalculator
import kotlinx.coroutines.flow.Flow

/**
 * 跑步数据仓库：统一封装 Room 访问入口。
 */
class RunRepository(private val dao: RunRecordDao) {

    /** 历史列表摘要（不含轨迹，列表页使用） */
    val allRunSummaries: Flow<List<RunSummary>> = dao.observeSummaries()

    val stats = dao.observeStats()

    fun observeRun(id: Long): Flow<RunRecordEntity?> = dao.observeById(id)

    suspend fun getRun(id: Long): RunRecordEntity? = dao.getById(id)

    suspend fun insert(run: RunRecordEntity): Long = dao.insert(run)

    suspend fun delete(run: RunRecordEntity) = dao.delete(run)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    /** 按时间窗口聚合统计（周报 / 月报） */
    suspend fun getPeriodStats(startMs: Long, endMs: Long): RunStatsRow? =
        dao.getPeriodStats(startMs, endMs)

    /** 按时间窗口聚合统计，数据变化时自动发射（周报 / 月报） */
    fun observePeriodStats(startMs: Long, endMs: Long): Flow<RunStatsRow?> =
        dao.observePeriodStats(startMs, endMs)

    /**
     * 按给定体重重算全部历史记录的卡路里。
     *
     * 体重或卡路里算法变更后调用，让旧记录与新记录口径一致；
     * 只读三个字段，不碰轨迹数据。
     */
    suspend fun recalculateCalories(weightKg: Double) {
        dao.listForCalorieRecalc().forEach { row ->
            dao.updateCalories(
                row.id,
                MetCalculator.caloriesForRun(weightKg, row.distanceMeters, row.durationMs)
            )
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: RunRepository? = null

        fun getInstance(database: RunDatabase): RunRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: RunRepository(database.runRecordDao()).also { INSTANCE = it }
            }
    }
}
