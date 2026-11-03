package com.run.tracker.ui.tracking

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.run.tracker.data.model.RunUiState
import com.run.tracker.service.TrackingService
import kotlinx.coroutines.flow.StateFlow

/**
 * 实时运动页 ViewModel：直接订阅前台服务的实时状态并转发控制指令。
 */
class TrackingViewModel(application: Application) : AndroidViewModel(application) {

    val uiState: StateFlow<RunUiState> = TrackingService.uiState

    fun start() = TrackingService.start(getApplication())

    fun pause() = TrackingService.pause(getApplication())

    fun resume() = TrackingService.resume(getApplication())

    fun finish() = TrackingService.finish(getApplication())
}
