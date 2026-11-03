package com.run.tracker.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.run.tracker.RunTrackerApp
import com.run.tracker.data.db.RunRecordEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * 跑步详情页 ViewModel：按 id 观察单条记录。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as RunTrackerApp).repository

    private val runId = MutableStateFlow(0L)

    val run: StateFlow<RunRecordEntity?> =
        runId.flatMapLatest { id ->
            if (id <= 0) flowOf(null) else repository.observeRun(id)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    fun load(id: Long) {
        runId.value = id
    }
}
