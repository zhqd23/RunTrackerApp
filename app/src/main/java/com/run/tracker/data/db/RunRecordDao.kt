package com.run.tracker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 跑步记录 DAO */
@Dao
interface RunRecordDao {

    @Insert
    suspend fun insert(run: RunRecordEntity): Long

    @Delete
    suspend fun delete(run: RunRecordEntity)

    /** 按 id 删除（历史列表只持有摘要时使用） */
    @Query("DELETE FROM run_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 按开始时间倒序观察全部记录 */
    @Query("SELECT * FROM run_records ORDER BY startTimeMs DESC")
    fun observeAll(): Flow<List<RunRecordEntity>>

    /**
     * 观察历史列表摘要：不查询 trackPoints 列，避免为列表解码每条记录的完整轨迹。
     */
    @Query(
        """
        SELECT id, startTimeMs, endTimeMs, durationMs, distanceMeters,
               avgSpeedMs, maxSpeedMs, avgHeartRate, maxHeartRate,
               avgCadence, calories
        FROM run_records
        ORDER BY startTimeMs DESC
        """
    )
    fun observeSummaries(): Flow<List<RunSummary>>

    /** 观察单条记录 */
    @Query("SELECT * FROM run_records WHERE id = :id")
    fun observeById(id: Long): Flow<RunRecordEntity?>

    /** 同步读取全部（详情页兜底） */
    @Query("SELECT * FROM run_records ORDER BY startTimeMs DESC LIMIT 200")
    suspend fun getAll(): List<RunRecordEntity>

    /** 同步读取单条 */
    @Query("SELECT * FROM run_records WHERE id = :id")
    suspend fun getById(id: Long): RunRecordEntity?

    /** 汇总统计 */
    @Query(
        """
        SELECT
            COUNT(*) AS totalCount,
            COALESCE(SUM(distanceMeters), 0) AS totalDistanceMeters,
            COALESCE(SUM(durationMs), 0) AS totalDurationMs,
            COALESCE(SUM(calories), 0) AS totalCalories,
            COALESCE(AVG(avgHeartRate), 0) AS avgHeartRate
        FROM run_records
        """
    )
    fun observeStats(): Flow<RunStatsRow?>

    /** 按时间窗口聚合统计（周报 / 月报），[startMs] 含、[endMs] 不含 */
    @Query(
        """
        SELECT
            COUNT(*) AS totalCount,
            COALESCE(SUM(distanceMeters), 0) AS totalDistanceMeters,
            COALESCE(SUM(durationMs), 0) AS totalDurationMs,
            COALESCE(SUM(calories), 0) AS totalCalories,
            COALESCE(AVG(avgHeartRate), 0) AS avgHeartRate
        FROM run_records
        WHERE startTimeMs >= :startMs AND startTimeMs < :endMs
        """
    )
    suspend fun getPeriodStats(startMs: Long, endMs: Long): RunStatsRow?

    /** 按时间窗口聚合统计，并随数据变化自动刷新（周报 / 月报） */
    @Query(
        """
        SELECT
            COUNT(*) AS totalCount,
            COALESCE(SUM(distanceMeters), 0) AS totalDistanceMeters,
            COALESCE(SUM(durationMs), 0) AS totalDurationMs,
            COALESCE(SUM(calories), 0) AS totalCalories,
            COALESCE(AVG(avgHeartRate), 0) AS avgHeartRate
        FROM run_records
        WHERE startTimeMs >= :startMs AND startTimeMs < :endMs
        """
    )
    fun observePeriodStats(startMs: Long, endMs: Long): Flow<RunStatsRow?>
}
