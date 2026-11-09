package com.run.tracker

import android.app.Application
import android.preference.PreferenceManager
import android.util.Log
import com.run.tracker.data.db.RunDatabase
import com.run.tracker.data.repository.PendingRunStore
import com.run.tracker.data.repository.RunRepository
import com.run.tracker.data.repository.UserProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

/**
 * 应用入口：初始化 osmdroid 全局配置，提供全局数据库/仓库单例，
 * 并在启动时补写上次「结束跑步」时没能写入数据库的记录。
 */
class RunTrackerApp : Application() {

    val database: RunDatabase by lazy { RunDatabase.getInstance(this) }

    val repository: RunRepository by lazy { RunRepository.getInstance(database) }

    /** 结束跑步时的本地暂存，用于崩溃恢复 */
    val pendingRunStore: PendingRunStore by lazy { PendingRunStore(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // osmdroid 地图全局配置
        Configuration.getInstance().load(
            this,
            PreferenceManager.getDefaultSharedPreferences(this)
        )
        Configuration.getInstance().userAgentValue = packageName

        recoverPendingRun()
        migrateCalories()
    }

    /**
     * 卡路里算法从「固定 MET 9.8」改为「按配速查表 + 体重」后，
     * 旧记录里存的是旧算法的结果，这里一次性按新算法重算，保证全表口径一致。
     */
    private fun migrateCalories() {
        val prefs = getSharedPreferences(MIGRATION_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CALORIES_V2, false)) return

        val weightKg = UserProfileStore(this).weightKg
        appScope.launch {
            try {
                repository.recalculateCalories(weightKg)
                prefs.edit().putBoolean(KEY_CALORIES_V2, true).apply()
                Log.i(TAG, "历史记录卡路里已按新算法重算（体重 $weightKg kg）")
            } catch (e: Exception) {
                Log.e(TAG, "重算历史卡路里失败，下次启动重试", e)
            }
        }
    }

    /**
     * 若上次「结束跑步」在写库前崩溃，这里把暂存的记录补写进数据库。
     */
    private fun recoverPendingRun() {
        val record = try {
            pendingRunStore.load()
        } catch (e: Exception) {
            Log.e(TAG, "读取待保存记录失败", e)
            null
        } ?: return

        Log.i(TAG, "发现未保存的跑步记录，开始补写")
        appScope.launch {
            try {
                repository.insert(record)
                pendingRunStore.clear()
                Log.i(TAG, "未保存的跑步记录已补写成功")
            } catch (e: Exception) {
                Log.e(TAG, "补写跑步记录失败，将在下次启动重试", e)
            }
        }
    }

    companion object {
        private const val TAG = "RunTrackerApp"
        private const val MIGRATION_PREFS = "migration"
        private const val KEY_CALORIES_V2 = "calories_v3"
    }
}
