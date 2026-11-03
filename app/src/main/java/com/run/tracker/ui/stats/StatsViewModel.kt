package com.run.tracker.ui.stats

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.run.tracker.R
import com.run.tracker.RunTrackerApp
import com.run.tracker.data.db.RunStatsRow
import com.run.tracker.util.PeriodUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** 统计页视图模式：累计 / 周报 / 月报 */
enum class StatsTab(@StringRes val labelRes: Int) {
    TOTAL(R.string.stats_tab_total),
    WEEK(R.string.stats_tab_week),
    MONTH(R.string.stats_tab_month)
}

/**
 * 统计页 ViewModel：累计统计 + 周报 / 月报周期统计。
 *
 * 周期统计走 Room 的 Flow 查询，新增/删除记录后会自动刷新，
 * 不再依赖切换 Tab 触发的一次性查询。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as RunTrackerApp).repository

    /** 全部累计统计 */
    val stats: StateFlow<RunStatsRow?> =
        repository.stats.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private val _tab = MutableStateFlow(StatsTab.TOTAL)
    val tab: StateFlow<StatsTab> = _tab.asStateFlow()

    /** 当前周期时间窗口（startMs 含、endMs 不含），未加载时为 (0, 0) */
    private val _periodRange = MutableStateFlow(Pair(0L, 0L))
    val periodRange: StateFlow<Pair<Long, Long>> = _periodRange.asStateFlow()

    /** 当前周期聚合统计（数据变化时自动重算） */
    val periodStats: StateFlow<RunStatsRow?> = _tab
        .flatMapLatest { tab ->
            when (tab) {
                StatsTab.TOTAL -> flowOf(null)
                StatsTab.WEEK, StatsTab.MONTH -> {
                    val (startMs, endMs) = _periodRange.value
                    repository.observePeriodStats(startMs, endMs)
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    fun selectTab(tab: StatsTab) {
        if (_tab.value == tab) return
        // 先算好窗口再切 Tab，保证 flatMapLatest 拿到的是新窗口
        if (tab != StatsTab.TOTAL) {
            _periodRange.value = if (tab == StatsTab.WEEK) {
                PeriodUtils.currentWeekRange()
            } else {
                PeriodUtils.currentMonthRange()
            }
        }
        _tab.value = tab
    }
}
