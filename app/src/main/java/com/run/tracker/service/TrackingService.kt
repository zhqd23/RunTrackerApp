package com.run.tracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.run.tracker.MainActivity
import com.run.tracker.RunTrackerApp
import com.run.tracker.R
import com.run.tracker.data.db.RunRecordEntity
import com.run.tracker.data.model.RunUiState
import com.run.tracker.data.repository.UserProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 跑步前台服务：
 * - 运动期间持续记录定位、心率、计步数据；
 * - 常驻通知栏展示；
 * - 通过静态 StateFlow 向 UI 发布实时状态；
 * - 结束时将汇总数据写入 Room。
 */
class TrackingService : Service() {

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "run_tracking"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.run.tracker.action.START"
        const val ACTION_PAUSE = "com.run.tracker.action.PAUSE"
        const val ACTION_RESUME = "com.run.tracker.action.RESUME"
        const val ACTION_FINISH = "com.run.tracker.action.FINISH"
        const val ACTION_STOP = "com.run.tracker.action.STOP"

        private val _uiState = MutableStateFlow(RunUiState())
        val uiState: StateFlow<RunUiState> = _uiState

        /** 当前是否正在记录（任何非 IDLE 状态） */
        fun isTracking(): Boolean = _uiState.value.state != RunState.IDLE

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(ACTION_START)
            )
        }

        fun pause(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(ACTION_PAUSE)
            )
        }

        fun resume(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(ACTION_RESUME)
            )
        }

        fun finish(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(ACTION_FINISH)
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var locationTracker: LocationTracker
    private lateinit var stepTracker: StepCounterTracker
    private lateinit var heartRateManager: HeartRateManager

    /**
     * 必须在 onCreate 里创建：属性初始化器运行于构造函数阶段，
     * 那时 attachBaseContext 还没执行，`this` 的 getApplicationContext() 是 null。
     */
    private lateinit var session: RunSession
    private var uiUpdateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        session = RunSession(UserProfileStore(this).weightKg)
        createNotificationChannel()
        locationTracker = LocationTracker(this)
        stepTracker = StepCounterTracker(this, serviceScope)
        heartRateManager = HeartRateManager(this, serviceScope)
        collectSensorData()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRun()
            ACTION_PAUSE -> pauseRun()
            ACTION_RESUME -> resumeRun()
            ACTION_FINISH -> finishRun()
            ACTION_STOP -> {
                stopRunResources()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRunResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // 运行控制
    // ------------------------------------------------------------------

    private fun startRun() {
        if (session.state == RunState.RUNNING) return
        session.start()
        locationTracker.start()
        stepTracker.start()
        heartRateManager.isRunningState = true
        heartRateManager.start()
        startAsForeground(buildNotification())
        uiUpdateJob?.cancel()
        uiUpdateJob = serviceScope.launch {
            while (isActive) {
                publishState()
                delay(1000)
            }
        }
        publishState()
    }

    private fun pauseRun() {
        if (session.state != RunState.RUNNING) return
        session.pause()
        // 暂停期间不再请求定位，省电且避免把暂停时的移动算进跑步距离
        locationTracker.stop()
        heartRateManager.isRunningState = false
        publishState()
    }

    private fun resumeRun() {
        if (session.state != RunState.PAUSED) return
        session.resume()
        locationTracker.start()
        heartRateManager.isRunningState = true
        publishState()
    }

    private fun finishRun() {
        // 未开始就收到结束指令时直接忽略，避免写入空记录
        if (session.state == RunState.IDLE) return

        session.finish()
        stopRunResources()

        val record = RunRecordEntity(
            startTimeMs = session.startTimeMs,
            endTimeMs = session.endTimeMs,
            durationMs = session.elapsedMs(),
            distanceMeters = session.distanceMeters,
            avgSpeedMs = session.avgSpeedMs(),
            maxSpeedMs = session.maxSpeedMs,
            avgHeartRate = session.avgHeartRate(),
            maxHeartRate = session.maxHeartRate,
            avgCadence = session.avgCadence(),
            calories = session.calories(),
            trackPoints = session.points
        )

        val app = application as RunTrackerApp

        // 诊断日志：距离是否偏长与平均定位精度强相关，下次反馈时可直接对照
        Log.i(
            TAG,
            "本次跑步：距离=%.0f m 用时=%.1f s 平均精度=%.1f m 轨迹点=%d".format(
                session.distanceMeters,
                session.elapsedMs() / 1000.0,
                session.avgAccuracyMeters(),
                session.pointCount
            )
        )

        // 1) 先把记录同步暂存到本地：即使后面任何一步崩溃，下次启动也能补写回来
        try {
            app.pendingRunStore.save(record)
        } catch (e: Exception) {
            Log.e(TAG, "暂存跑步记录失败", e)
        }

        // 2) 再异步写数据库；写成功才清除暂存
        serviceScope.launch {
            withContext(NonCancellable) {
                try {
                    app.repository.insert(record)
                    app.pendingRunStore.clear()
                } catch (e: Exception) {
                    Log.e(TAG, "保存跑步记录失败，保留暂存以便下次恢复", e)
                }
            }
            stopSelf()
        }

        // 3) 最后做 UI / 通知收尾。这一步出任何问题都不该影响已经暂存的数据，
        //    更不能让异常冒泡导致整个前台服务进程崩溃。
        try {
            _uiState.value = RunUiState(heartRateSource = heartRateManager.source.value)
            stopForeground(STOP_FOREGROUND_REMOVE)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "结束跑步的界面收尾失败", e)
        }
    }

    private fun stopRunResources() {
        uiUpdateJob?.cancel()
        uiUpdateJob = null
        locationTracker.stop()
        stepTracker.stop()
        heartRateManager.stop()
    }

    // ------------------------------------------------------------------
    // 数据采集
    // ------------------------------------------------------------------

    private fun collectSensorData() {
        serviceScope.launch {
            heartRateManager.heartRate.collect { hr ->
                session.onHeartRate(hr)
                heartRateManager.isRunningState = session.state == RunState.RUNNING
            }
        }
        serviceScope.launch {
            locationTracker.locations.collect { loc ->
                // 统一使用系统当前时间作为采样时间戳，保证速度计算一致；
                // 同时把定位精度传下去，供会话过滤室内漂移
                session.onLocation(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    altitude = loc.altitude,
                    timeMs = System.currentTimeMillis(),
                    accuracyMeters = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0
                )
            }
        }
        serviceScope.launch {
            stepTracker.cadence.collect { cad -> session.onCadence(cad) }
        }
        serviceScope.launch {
            stepTracker.steps.collect { steps -> session.onSteps(steps) }
        }
    }

    private fun publishState() {
        _uiState.value = RunUiState(
            state = session.state,
            heartRate = session.currentHeartRate,
            distanceMeters = session.distanceMeters,
            cadence = session.currentCadence,
            avgSpeedMs = session.avgSpeedMs(),
            currentSpeedMs = session.currentSpeedMs,
            durationMs = session.elapsedMs(),
            maxSpeedMs = session.maxSpeedMs,
            avgHeartRate = session.avgHeartRate(),
            maxHeartRate = session.maxHeartRate,
            avgCadence = session.avgCadence(),
            calories = session.calories(),
            heartRateSource = heartRateManager.source.value,
            points = session.points.toList(),
            hasGps = session.points.isNotEmpty()
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tracking_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val state = _uiState.value
        val text = if (state.state == RunState.RUNNING) {
            getString(R.string.tracking_notification_text)
        } else {
            getString(R.string.tracking_notification_paused)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_running)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** Android 8+ 前台服务需声明 foregroundServiceType（Android 14 强制） */
    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
