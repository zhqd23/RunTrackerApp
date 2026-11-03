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
enum class HeartRateSource { SIMULATED, BLE, SEARCHING }

/**
 * 心率管理器：
 * - 通过 BLE 标准心率服务（Heart Rate Service 0x180D / Heart Rate Measurement 0x2A37）连接心率带；
 * - 扫描阶段按「广播服务 UUID + 设备名关键字」过滤，只连接真正的心率设备；
 * - 连接成功并启用通知后进入 BLE 模式；未授权 / 无设备 / 连接超时则自动进入模拟模式；
 * - 对外暴露心率值与来源状态。
 *
 * 调试：把 [DEBUG_LOGGING] 置为 true，全链路日志输出到 Logcat（Tag=HeartRateManager）。
 */
class HeartRateManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 常见心率带/心率设备名关键字，用于广播数据不含服务 UUID 时的兜底识别 */
        private val HEART_RATE_NAME_HINTS = listOf(
            "polar", "hrm", "heart", "wahoo", "garmin", "tickr",
            "h7", "h9", "h10", "chest", "sensor", "magene", "coospo",
            "xoss", "moo", "scosche", "rhythm", "decathlon"
        )

        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /** 调试日志开关：true = 输出 BLE 全链路日志到 Logcat（抓完 log 改回 false 即可） */
        private const val DEBUG_LOGGING = true
        private const val TAG = "HeartRateManager"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate

    private val _source = MutableStateFlow(HeartRateSource.SEARCHING)
    val source: StateFlow<HeartRateSource> = _source

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var bluetoothGatt: BluetoothGatt? = null
    private var simulateJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var connectTimeoutJob: Job? = null

    /** 管理器是否处于活跃状态（start/stop 控制） */
    private var isRunning = false

    /** 已尝试连接过的设备地址，避免扫描回调重复触发同一设备 */
    private val attemptedDevices = mutableSetOf<String>()

    /** 已打日志的扫描设备地址，避免高频扫描回调刷屏 */
    private val loggedScanDevices = mutableSetOf<String>()

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
    // 扫描（现代 BluetoothLeScanner + 软件过滤）
    // ---------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val isHR = isHeartRateDevice(device, result.scanRecord)
            if (loggedScanDevices.add(device.address)) {
                val name = device.name ?: result.scanRecord?.deviceName ?: "?"
                logd("scan: name=$name addr=${device.address} isHR=$isHR")
            }
            if (isHR) {
                mainHandler.post { onCandidateFound(device) }
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>?) {
            results?.forEach { result ->
                val device = result.device ?: return@forEach
                if (isHeartRateDevice(device, result.scanRecord)) {
                    mainHandler.post { onCandidateFound(device) }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            logw("scan failed: code=$errorCode")
            mainHandler.post { fallbackToSimulation() }
        }
    }

    /** 判断设备是否为心率设备：广播服务 UUID 优先，设备名关键字兜底 */
    private fun isHeartRateDevice(device: BluetoothDevice, record: ScanRecord?): Boolean {
        val serviceUuids = record?.serviceUuids
        if (serviceUuids != null &&
            serviceUuids.any { it.uuid == HEART_RATE_SERVICE_UUID }
        ) {
            return true
        }
        val name = device.name ?: record?.deviceName
        return name != null && HEART_RATE_NAME_HINTS.any { name.contains(it, ignoreCase = true) }
    }

    private fun onCandidateFound(device: BluetoothDevice) {
        if (!isRunning || _connected.value) return
        val address = device.address
        if (attemptedDevices.contains(address)) return
        attemptedDevices.add(address)
        logd("candidate: name=${device.name} addr=$address")
        connectToDevice(device)
    }

    // ---------------------------------------------------------------------
    // GATT 连接
    // ---------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logd("connection state: status=$status newState=$newState")
            // status 非 GATT_SUCCESS 说明连接本身失败，不能当已连接处理
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    logw("connect failed: status=$status")
                    _connected.value = false
                    closeGatt()
                    if (isRunning) fallbackToSimulation()
                }
                return
            }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    _connected.value = true
                    connectTimeoutJob?.cancel()
                    logd("connected, discovering services")
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    _connected.value = false
                    _source.value = HeartRateSource.SIMULATED
                    logd("disconnected -> simulated")
                    if (isRunning) startSimulation()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            logd("services discovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logw("discover services failed: status=$status")
                disconnectAndFallback()
                return
            }
            val service: BluetoothGattService? = gatt.getService(HEART_RATE_SERVICE_UUID)
            if (service == null) {
                logw("HR service 0x180D not found")
                disconnectAndFallback()
                return
            }
            val characteristic: BluetoothGattCharacteristic? =
                service.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
            if (characteristic == null) {
                logw("HR measurement 0x2A37 not found")
                disconnectAndFallback()
                return
            }
            // 特征必须支持通知，否则无法收到实时心率
            if (characteristic.properties and
                BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            ) {
                logw("HR characteristic lacks PROPERTY_NOTIFY")
                disconnectAndFallback()
                return
            }
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                logw("setCharacteristicNotification failed")
                disconnectAndFallback()
                return
            }
            val descriptor: BluetoothGattDescriptor? =
                characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor == null) {
                logw("CCC descriptor 0x2902 not found")
                disconnectAndFallback()
                return
            }
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            logd("enabling notification via CCC descriptor")
            gatt.writeDescriptor(descriptor)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            logd("descriptor write: status=$status")
            // 通知启用成功 → BLE 心率链路就绪
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _source.value = HeartRateSource.BLE
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                val value = characteristic.value ?: return
                parseHeartRate(value)?.let {
                    logd("HR data: $it bpm")
                    _heartRate.value = it
                    _source.value = HeartRateSource.BLE
                }
            }
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
    // 生命周期
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun start() {
        isRunning = true
        stopSimulation()
        closeGatt()
        attemptedDevices.clear()
        loggedScanDevices.clear()
        val hasPerm = hasBluetoothPermissions()
        val btEnabled = isBluetoothEnabled()
        logd("start: hasPerm=$hasPerm btEnabled=$btEnabled")
        if (hasPerm && btEnabled) {
            _source.value = HeartRateSource.SEARCHING
            startScan()
        } else {
            logw("start: no permission or BT disabled -> simulated")
            _source.value = HeartRateSource.SIMULATED
            startSimulation()
        }
    }

    fun stop() {
        logd("stop")
        isRunning = false
        stopScan()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        closeGatt()
        stopSimulation()
        attemptedDevices.clear()
        loggedScanDevices.clear()
        _heartRate.value = 0
    }

    // ---------------------------------------------------------------------
    // 连接控制
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!isRunning) return
        stopScan()
        stopSimulation()
        closeGatt()
        _source.value = HeartRateSource.SEARCHING
        logd("connecting: name=${device.name} addr=${device.address}")
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
        // 连接超时：超时后仍未建立连接则放弃该设备并降级
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_source.value == HeartRateSource.SEARCHING && !_connected.value) {
                logw("connect timeout ${CONNECT_TIMEOUT_MS}ms")
                closeGatt()
                fallbackToSimulation()
            }
        }
    }

    private fun disconnectAndFallback() {
        closeGatt()
        if (isRunning) fallbackToSimulation()
    }

    private fun closeGatt() {
        try {
            bluetoothGatt?.disconnect()
        } catch (e: Exception) {
            loge("gatt disconnect error", e)
        }
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            loge("gatt close error", e)
        }
        bluetoothGatt = null
        _connected.value = false
    }

    private fun fallbackToSimulation() {
        if (!isRunning) return
        logd("fallback to simulated")
        _source.value = HeartRateSource.SIMULATED
        startSimulation()
    }

    // ---------------------------------------------------------------------
    // 扫描控制
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startScan() {
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
            logd("scan started (low latency, timeout ${SCAN_TIMEOUT_MS}ms)")
            scanTimeoutJob?.cancel()
            scanTimeoutJob = scope.launch {
                delay(SCAN_TIMEOUT_MS)
                if (isRunning && !_connected.value && _source.value != HeartRateSource.BLE) {
                    logw("scan timeout ${SCAN_TIMEOUT_MS}ms")
                    stopScan()
                    fallbackToSimulation()
                }
            }
        } catch (e: Exception) {
            loge("startScan error", e)
            fallbackToSimulation()
        }
    }

    private fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        try {
            bluetoothAdapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            loge("stopScan error", e)
        }
    }

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

    // ---------------------------------------------------------------------
    // 模拟数据
    // ---------------------------------------------------------------------

    /** 模拟心率：跑步时 120~175 bpm 波动，静止时约 75 bpm */
    private fun startSimulation() {
        if (simulateJob?.isActive == true) return
        simulateJob = scope.launch {
            var phase = 0.0
            while (isActive && isRunning) {
                val base = if (_runningState) 145.0 else 75.0
                val wave = (kotlin.math.sin(phase) * 8.0).toInt()
                val noise = Random.nextInt(-4, 5)
                val hr = (base + wave + noise).toInt().coerceIn(55, 195)
                _heartRate.value = hr
                phase += 0.35
                delay(1000)
            }
        }
    }

    /** 由外部（服务）告知当前是否处于跑步状态，供模拟模式生成合理心率 */
    var isRunningState: Boolean
        get() = _runningState
        set(value) {
            _runningState = value
        }
    private var _runningState = false

    private fun stopSimulation() {
        simulateJob?.cancel()
        simulateJob = null
    }
}
