package com.run.tracker.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.run.tracker.RunTrackerApp
import com.run.tracker.data.db.RunSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 历史记录页 ViewModel：观察全部跑步记录摘要（不含轨迹点）。
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as RunTrackerApp).repository

    val runs: StateFlow<List<RunSummary>> =
        repository.allRunSummaries.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun delete(id: Long) {
        viewModelScope.launch { repository.deleteById(id) }
    }
}
