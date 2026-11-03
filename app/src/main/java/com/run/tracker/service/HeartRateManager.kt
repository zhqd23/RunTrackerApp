package com.run.tracker.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import java.util.UUID

/** 心率数据来源 */
enum class HeartRateSource {
    /** 未开始 / 已停止：既没有连接设备，也没有在生成模拟数据 */
    IDLE,
    SIMULATED,
    BLE,
    SEARCHING
}

/**
 * 心率管理器：连接 BLE 标准心率带（Heart Rate Service 0x180D / Measurement 0x2A37）。
 *
 * 连接策略（针对真机上「连不上心率带」的常见原因做了加固）：
 * 1. 先直连上次成功连接过的设备，避免每次都靠扫描碰运气；
 * 2. 只对广播里带 0x180D 服务的设备立即发起连接；仅靠名字匹配的设备延迟 6 秒再试，
 *    避免被附近同名的蓝牙设备挤掉；
 * 3. **单次失败不再直接降级为模拟心率**，而是继续扫描 / 尝试下一个候选设备，
 *    直到 60 秒总时限才降级；
 * 4. 连接成功后若 12 秒内没能订阅上心率通知，判定失败并重来；
 * 5. Android 13+ 使用新的 writeDescriptor(descriptor, value) 接口。
 */
class HeartRateManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        val HEART_RATE_SERVICE_UUID: UUID =
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_UUID: UUID =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 常见心率带/心率设备名关键字，用于广播数据不含服务 UUID 时的兜底识别 */
        private val HEART_RATE_NAME_HINTS = listOf(
            "polar", "hrm", "heart", "wahoo", "garmin", "tickr",
            "h7", "h9", "h10", "chest", "sensor", "magene", "coospo",
            "xoss", "moo", "scosche", "rhythm", "decathlon"
        )

        private const val PREFS = "run_tracker_hr"
        private const val KEY_LAST_DEVICE = "last_device_address"

        /** 单次连接超时 */
        private const val CONNECT_TIMEOUT_MS = 15_000L
        /** 直连上次设备的最长等待，超过就转为扫描 */
        private const val DIRECT_RECONNECT_TIMEOUT_MS = 8_000L
        /** 连接成功后等待订阅心率通知的超时 */
        private const val READY_TIMEOUT_MS = 12_000L
        /** 搜索总时长，超时才降级为模拟心率 */
        private const val SEARCH_TIMEOUT_MS = 60_000L
        /** 仅按名称匹配的设备，扫描开始后延迟这么久再尝试 */
        private const val NAME_FALLBACK_DELAY_MS = 6_000L

        /** 调试日志开关：true = 输出 BLE 全链路日志到 Logcat（Tag=HeartRateManager） */
        private const val DEBUG_LOGGING = true
        private const val TAG = "HeartRateManager"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate

    private val _source = MutableStateFlow(HeartRateSource.IDLE)
    val source: StateFlow<HeartRateSource> = _source

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var bluetoothGatt: BluetoothGatt? = null
    private var simulateJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var readyTimeoutJob: Job? = null
    private var searchDeadlineJob: Job? = null

    /** 管理器是否处于活跃状态（start/stop 控制） */
    private var isRunning = false
    /** 心率通知是否已订阅成功（BLE 链路真正可用） */
    private var bleReady = false
    /** 本次是否曾经成功连上过（用于跑动中途掉线后重新搜索） */
    private var hadBleSession = false
    /** 正在连接中，避免重复发起连接 */
    private var connecting = false
    /** 处理失败重试时的重入保护 */
    private var handlingFailure = false

    private var scanStartedAtMs = 0L
    private var searchDeadlineMs = 0L

    /** 已尝试连接过的设备地址，避免扫描回调重复触发同一设备 */
    private val attemptedDevices = mutableSetOf<String>()

    /** 已打日志的扫描设备地址，避免高频扫描回调刷屏 */
    private val loggedScanDevices = mutableSetOf<String>()

    /** 仅按名称匹配到的候选设备，延迟尝试 */
    private val nameCandidates = mutableListOf<BluetoothDevice>()

    // ---------------------------------------------------------------------
    // 日志
    // ---------------------------------------------------------------------

    private fun logd(msg: String) {
        if (DEBUG_LOGGING) Log.d(TAG, msg)
    }

    private fun logw(msg: String) {
        if (DEBUG_LOGGING) Log.w(TAG, msg)
    }

    private fun loge(msg: String, t: Throwable? = null) {
        if (DEBUG_LOGGING) Log.e(TAG, msg, t)
    }

    // ---------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun start() {
        isRunning = true
        bleReady = false
        hadBleSession = false
        connecting = false
        stopSimulation()
        cancelTimers()
        closeGatt()
        attemptedDevices.clear()
        loggedScanDevices.clear()
        nameCandidates.clear()

        val hasPerm = hasBluetoothPermissions()
        val btEnabled = isBluetoothEnabled()
        logd("start: hasPerm=$hasPerm btEnabled=$btEnabled")
        if (!hasPerm || !btEnabled) {
            logw("start: no permission or BT disabled -> simulated")
            _source.value = HeartRateSource.SIMULATED
            startSimulation()
            return
        }

        _source.value = HeartRateSource.SEARCHING
        restartSearchWindow()

        // 优先直连上次成功连接过的心率带
        if (!tryDirectReconnect()) {
            startScan()
        }
    }

    /** 重置搜索总时限（开始时 / 跑动中途掉线后重新搜索时调用） */
    private fun restartSearchWindow() {
        searchDeadlineMs = SystemClock.elapsedRealtime() + SEARCH_TIMEOUT_MS
        searchDeadlineJob?.cancel()
        searchDeadlineJob = scope.launch {
            delay(SEARCH_TIMEOUT_MS)
            if (isRunning && !bleReady) {
                logw("search timeout ${SEARCH_TIMEOUT_MS}ms -> simulated")
                stopScan()
                fallbackToSimulation()
            }
        }
    }

    fun stop() {
        logd("stop")
        isRunning = false
        bleReady = false
        connecting = false
        stopScan()
        cancelTimers()
        closeGatt()
        stopSimulation()
        attemptedDevices.clear()
        loggedScanDevices.clear()
        nameCandidates.clear()
        _heartRate.value = 0
        // 停止后不应再显示「已连接」或「模拟中」，回到未连接状态
        _source.value = HeartRateSource.IDLE
    }

    private fun cancelTimers() {
        connectTimeoutJob?.cancel(); connectTimeoutJob = null
        readyTimeoutJob?.cancel(); readyTimeoutJob = null
        searchDeadlineJob?.cancel(); searchDeadlineJob = null
    }

    // ---------------------------------------------------------------------
    // 扫描
    // ---------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val verified = isVerifiedHeartRateDevice(result.scanRecord)
            if (loggedScanDevices.add(device.address)) {
                val name = device.name ?: result.scanRecord?.deviceName ?: "?"
                logd("scan: name=$name addr=${device.address} verified=$verified")
            }
            if (verified || isHeartRateByName(device, result.scanRecord)) {
                mainHandler.post { onCandidateFound(device, verified) }
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            logw("scan failed: code=$errorCode")
            mainHandler.post { if (isRunning && !bleReady) attemptFailed("scan failed $errorCode") }
        }
    }

    /** 广播包里带标准心率服务 UUID —— 基本可以确定是心率带 */
    private fun isVerifiedHeartRateDevice(record: ScanRecord?): Boolean =
        record?.serviceUuids?.any { it.uuid == HEART_RATE_SERVICE_UUID } == true

    /** 广播包不含服务 UUID 时，用设备名关键字兜底（需延迟尝试） */
    private fun isHeartRateByName(device: BluetoothDevice, record: ScanRecord?): Boolean {
        val name = device.name ?: record?.deviceName ?: return false
        return HEART_RATE_NAME_HINTS.any { name.contains(it, ignoreCase = true) }
    }

    private fun onCandidateFound(device: BluetoothDevice, verified: Boolean) {
        if (!isRunning || bleReady || connecting || _connected.value) return
        if (attemptedDevices.contains(device.address)) return

        if (verified) {
            logd("verified candidate: ${device.address}")
            connectToDevice(device, CONNECT_TIMEOUT_MS)
        } else {
            if (nameCandidates.none { it.address == device.address }) {
                nameCandidates.add(device)
                logd("queued by name: ${device.address}")
            }
            maybeTryNameCandidate()
        }
    }

    /** 名字匹配的设备要等一会儿再试，把机会先留给广播里带心率服务的设备 */
    private fun maybeTryNameCandidate() {
        if (!isRunning || bleReady || connecting || _connected.value) return
        val elapsed = SystemClock.elapsedRealtime() - scanStartedAtMs
        if (elapsed < NAME_FALLBACK_DELAY_MS) return
        tryNextNameCandidate()
    }

    private fun tryNextNameCandidate(): Boolean {
        if (!isRunning || bleReady || connecting || _connected.value) return false
        val device = nameCandidates.firstOrNull { !attemptedDevices.contains(it.address) } ?: return false
        logd("trying name candidate: ${device.address}")
        connectToDevice(device, CONNECT_TIMEOUT_MS)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!isRunning || bleReady || _connected.value) return
        val scanner = bluetoothAdapter()?.bluetoothLeScanner
        if (scanner == null) {
            logw("BluetoothLeScanner unavailable")
            fallbackToSimulation()
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
            scanStartedAtMs = SystemClock.elapsedRealtime()
            logd("scan started")
        } catch (e: Exception) {
            loge("startScan error", e)
            fallbackToSimulation()
        }
    }

    private fun stopScan() {
        try {
            bluetoothAdapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            loge("stopScan error", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryDirectReconnect(): Boolean {
        val address = lastDeviceAddress() ?: return false
        val adapter = bluetoothAdapter() ?: return false
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            loge("getRemoteDevice error", e)
            return false
        }
        logd("direct reconnect: $address")
        attemptedDevices.add(address)
        connectToDevice(device, DIRECT_RECONNECT_TIMEOUT_MS)
        return true
    }

    // ---------------------------------------------------------------------
    // 连接
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice, timeoutMs: Long) {
        if (!isRunning || bleReady) return
        connecting = true
        attemptedDevices.add(device.address)
        stopScan()
        stopSimulation()
        closeGatt()
        _source.value = HeartRateSource.SEARCHING
        logd("connecting: ${device.address} timeout=${timeoutMs}ms")

        bluetoothGatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            loge("connectGatt error", e)
            null
        }

        if (bluetoothGatt == null) {
            attemptFailed("connectGatt returned null")
            return
        }

        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(timeoutMs)
            if (isRunning && !bleReady) attemptFailed("connect timeout")
        }
    }

    /**
     * 单次尝试失败后的统一处理：
     * 先尝试其他候选设备，其次重新扫描，只有超出总时限才降级为模拟心率。
     * 若失败发生在已经成功连接之后（跑动中途掉线），会重开一个完整的搜索窗口。
     */
    private fun attemptFailed(reason: String) {
        if (!isRunning || bleReady || handlingFailure) return
        handlingFailure = true
        try {
            logw("attempt failed: $reason")
            val wasConnected = hadBleSession
            hadBleSession = false

            connecting = false
            connectTimeoutJob?.cancel(); connectTimeoutJob = null
            readyTimeoutJob?.cancel(); readyTimeoutJob = null
            closeGatt()
            _source.value = HeartRateSource.SEARCHING

            if (wasConnected) {
                // 跑动途中掉线：重新给一次完整搜索窗口，优先直连原设备
                logd("connection lost after ready, restarting search window")
                restartSearchWindow()
                if (tryDirectReconnect()) return
            }

            if (tryNextNameCandidate()) return
            if (SystemClock.elapsedRealtime() < searchDeadlineMs) {
                startScan()
            } else {
                fallbackToSimulation()
            }
        } finally {
            handlingFailure = false
        }
    }

    private fun closeGatt() {
        val gatt = bluetoothGatt ?: return
        bluetoothGatt = null
        _connected.value = false
        try {
            gatt.disconnect()
        } catch (e: Exception) {
            loge("gatt disconnect error", e)
        }
        try {
            gatt.close()
        } catch (e: Exception) {
            loge("gatt close error", e)
        }
    }

    // ---------------------------------------------------------------------
    // GATT 回调
    // ---------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // 已被替换/关闭的旧连接，回调直接忽略，避免干扰新一轮连接
            if (bluetoothGatt !== gatt) {
                logd("ignore stale gatt callback")
                return
            }
            logd("connection state: status=$status newState=$newState")

            when {
                newState == BluetoothGatt.STATE_CONNECTED &&
                        status == BluetoothGatt.GATT_SUCCESS -> {
                    connecting = false
                    _connected.value = true
                    connectTimeoutJob?.cancel()
                    logd("connected, discovering services")
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!gatt.discoverServices()) {
                        attemptFailed("discoverServices returned false")
                        return
                    }
                    readyTimeoutJob?.cancel()
                    readyTimeoutJob = scope.launch {
                        delay(READY_TIMEOUT_MS)
                        if (isRunning && !bleReady) attemptFailed("ready timeout")
                    }
                }

                newState == BluetoothGatt.STATE_DISCONNECTED -> {
                    connecting = false
                    _connected.value = false
                    bleReady = false
                    if (isRunning) {
                        attemptFailed("disconnected status=$status")
                    } else {
                        closeGatt()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logw("discover services failed: status=$status")
                attemptFailed("services status=$status")
                return
            }
            val service: BluetoothGattService? = gatt.getService(HEART_RATE_SERVICE_UUID)
            if (service == null) {
                logw("HR service 0x180D not found")
                attemptFailed("no HR service")
                return
            }
            val characteristic = service.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
            if (characteristic == null) {
                logw("HR measurement 0x2A37 not found")
                attemptFailed("no HR measurement")
                return
            }
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                logw("HR characteristic lacks PROPERTY_NOTIFY")
                attemptFailed("no notify property")
                return
            }
            if (!enableNotifications(gatt, characteristic)) {
                logw("enabling notifications failed")
                attemptFailed("enable notify failed")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            logd("descriptor write: status=$status")
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                attemptFailed("descriptor write status=$status")
                return
            }
            bleReady = true
            hadBleSession = true
            readyTimeoutJob?.cancel()
            stopSimulation()
            _source.value = HeartRateSource.BLE
            gatt.device?.address?.let { saveLastDevice(it) }
            logd("BLE ready: ${gatt.device?.address}")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != HEART_RATE_MEASUREMENT_UUID) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onHeartRateValue(value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != HEART_RATE_MEASUREMENT_UUID) return
            onHeartRateValue(value)
        }
    }

    private fun onHeartRateValue(value: ByteArray) {
        val hr = parseHeartRate(value) ?: return
        logd("HR data: $hr bpm")
        _heartRate.value = hr
        if (!bleReady) {
            bleReady = true
            hadBleSession = true
            readyTimeoutJob?.cancel()
            stopSimulation()
        }
        _source.value = HeartRateSource.BLE
    }

    /** 订阅心率测量通知；Android 13+ 必须使用带 value 的新接口 */
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
            status == 0 // BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    /** 解析心率测量特征值（首字节 flags + 心率值） */
    private fun parseHeartRate(value: ByteArray): Int? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt() and 0xFF
        return if (flags and 0x01 == 0x01) {
            // 16 bit 心率
            if (value.size < 3) null
            else ((value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8))
        } else {
            // 8 bit 心率
            if (value.size < 2) null
            else value[1].toInt() and 0xFF
        }
    }

    // ---------------------------------------------------------------------
    // 设备信息 / 权限
    // ---------------------------------------------------------------------

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    private fun isBluetoothEnabled(): Boolean = bluetoothAdapter()?.isEnabled == true

    /** 连接 + 扫描所需权限（Android 12+ 拆分出 BLUETOOTH_CONNECT） */
    private fun hasBluetoothPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun saveLastDevice(address: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DEVICE, address)
            .apply()
    }

    private fun lastDeviceAddress(): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE, null)

    // ---------------------------------------------------------------------
    // 模拟数据
    // ---------------------------------------------------------------------

    /** 由外部（服务）告知当前是否处于跑步状态，供模拟模式生成合理心率 */
    var isRunningState: Boolean = false

    /** 模拟心率：跑步时 120~175 bpm 波动，静止时约 75 bpm */
    private fun startSimulation() {
        if (simulateJob?.isActive == true) return
        if (bleReady) return
        simulateJob = scope.launch {
            var phase = 0.0
            while (isActive && isRunning) {
                val base = if (isRunningState) 145.0 else 75.0
                val wave = (kotlin.math.sin(phase) * 8.0).toInt()
                val noise = Random.nextInt(-4, 5)
                val hr = (base + wave + noise).toInt().coerceIn(55, 195)
                _heartRate.value = hr
                phase += 0.35
                delay(1000)
            }
        }
    }

    private fun stopSimulation() {
        simulateJob?.cancel()
        simulateJob = null
    }

    private fun fallbackToSimulation() {
        if (!isRunning || bleReady) return
        logd("fallback to simulated")
        _source.value = HeartRateSource.SIMULATED
        startSimulation()
    }
}
