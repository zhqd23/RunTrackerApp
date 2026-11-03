package com.run.tracker

import android.app.Application
import android.preference.PreferenceManager
import com.run.tracker.data.db.RunDatabase
import com.run.tracker.data.repository.RunRepository
import org.osmdroid.config.Configuration

/**
 * 应用入口：初始化 osmdroid 全局配置，提供全局数据库/仓库单例。
 */
class RunTrackerApp : Application() {

    val database: RunDatabase by lazy { RunDatabase.getInstance(this) }

    val repository: RunRepository by lazy { RunRepository.getInstance(database) }

    override fun onCreate() {
        super.onCreate()
        // osmdroid 地图全局配置
        Configuration.getInstance().load(
            this,
            PreferenceManager.getDefaultSharedPreferences(this)
        )
        Configuration.getInstance().userAgentValue = packageName
    }
}
