package org.prooftray.app.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.prooftray.app.data.AppSettingsRepository
import org.prooftray.app.domain.BluetoothRole

data class BleCamera(val address: String, val name: String, val classic: Boolean = false, val advertisementFlags: Int? = null,
                     val make: CameraMake = CameraMake.NIKON, val pairingAvailable: Boolean = true, val advertisedClientId: Int? = null) {
    val needsWakeConnection: Boolean get() = advertisementNeedsWake(advertisementFlags)
}
data class BleState(
    val cameras: List<BleCamera> = emptyList(), val scanning: Boolean = false,
    val authenticated: Boolean = false, val message: String = "蓝牙未连接", val connectedAddress: String = "",
    val imageTransferRequested: Boolean = false, val locationRequested: Boolean = false, val timeRequested: Boolean = false,
    val serialNumber: String = "", val modelNumber: String = "",
    val history: List<String> = emptyList(), val cameraName: String = "", val serverName: String = "",
    val pairingInProgress: Boolean = false, val systemPaired: Boolean = false, val classicAddress: String = "",
    val wifiRequested: Boolean = false, val connectionRequested: Boolean = false, val clientName: String = "", val adapterEnabled: Boolean = true,
    val scanError: String? = null
)

private fun BleState.record(message: String): BleState = copy(message = message,
    history = if (history.lastOrNull() == message) history else (history + message).takeLast(12))

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class NikonBle(private val context: Context, private val scope: CoroutineScope, private val identities: CameraIdentityStore) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val mutableState = MutableStateFlow(BleState(adapterEnabled = adapter?.isEnabled == true))
    val state = mutableState.asStateFlow()
    private val mutableTransferRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val transferRequests = mutableTransferRequests.asSharedFlow()
    @Volatile private var session: NikonGattSession? = null
    private var previousRelease: NikonGattSession? = null
    private val settings = AppSettingsRepository.get(context)
    private val timeRequests = Channel<Unit>(Channel.CONFLATED)
    private var timeRequestRevision = 0L
    @Volatile private var wakeGatt: BluetoothGatt? = null
    private var wakeReady: CompletableDeferred<Unit>? = null
    private var connectionJob: Job? = null
    private var scanJob: Job? = null
    @Volatile private var scanGeneration = 0
    private var activeScan: ScanCallback? = null
    @Volatile private var generation = 0
    @Volatile private var featureFlags = 0
    @Volatile private var sessionKey: ByteArray? = null
    @Volatile private var transportFailure: Pair<Int, BleTransportException>? = null
    private val discovered = ConcurrentHashMap<String, BluetoothDevice>()
    private val advertisements = ConcurrentHashMap<String, Pair<BleCamera, Long>>()
    private val serviceIds = listOf(nikonUuid("de00"), nikonUuid("de01"), UUID.fromString("6155bdb9-c76d-628d-5542-d43d01de0000"))

    private val adapterEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val enabled = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
                if (!enabled) disconnect()
                mutableState.update { it.copy(adapterEnabled = enabled).record(if (enabled) "手机蓝牙已开启，等待连接" else "手机蓝牙已关闭") }
            } else if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (device.address != state.value.classicAddress || bond != BluetoothDevice.BOND_NONE) return
                disconnect()
                mutableState.update { it.copy(systemPaired = false).record("相机的系统配对已移除，请重新配对") }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(adapterEvents, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(adapterEvents, filter)
        scope.launch { settings.state.collect { timeRequests.trySend(Unit) } }
        scope.launch { for (request in timeRequests) syncRequestedTime() }
    }

    private fun refreshAdapterState(): Boolean {
        val enabled = adapter != null && adapter.isEnabled
        mutableState.update { it.copy(adapterEnabled = enabled) }
        return enabled
    }

    private fun scanCallback(token: Int) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (scanGeneration != token) return
            val item = advertisedCamera(result)
            if (discovered.size < 64 || discovered.containsKey(item.address)) discovered[item.address] = result.device
            mutableState.update { it.copy(cameras = (it.cameras.filterNot { old -> old.address == item.address } + item).takeLast(30)) }
        }
        override fun onScanFailed(errorCode: Int) {
            val reason = bluetoothScanFailure(errorCode)
            if (scanGeneration == token) mutableState.update { it.copy(scanning = false, scanError = reason, message = reason) }
        }
    }

    @Synchronized
    fun scan(includeSony: Boolean = false, stopAfterMillis: Long? = 20_000L) {
        runCatching {
            check(refreshAdapterState()) { "请先打开手机蓝牙" }
            stopScan()
            discovered.clear()
            advertisements.clear()
            val token = scanGeneration
            val callback = scanCallback(token).also { activeScan = it }
            mutableState.update { it.copy(cameras = emptyList(), scanning = true, scanError = null, message = "请在机身打开智能手机配对") }
            val filters = serviceIds.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }.toMutableList()
            if (includeSony) {
                filters += ScanFilter.Builder().setManufacturerData(SonyControlData.COMPANY_ID, byteArrayOf(3, 0)).build()
                filters += ScanFilter.Builder().setServiceUuid(ParcelUuid(SonyControlData.pairingService)).build()
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            adapter.bluetoothLeScanner.startScan(filters, settings, callback)
            scanJob = stopAfterMillis?.let { duration ->
                scope.launch {
                    delay(duration)
                    synchronized(this@NikonBle) { if (scanGeneration == token) stopScan() }
                }
            }
        }.onFailure { error ->
            val reason = error.message ?: "无法扫描蓝牙"
            mutableState.update { it.copy(scanning = false, scanError = reason, message = reason) }
        }
    }

    private fun advertisedCamera(result: ScanResult): BleCamera {
        val name = cameraBluetoothName(result.scanRecord?.deviceName).ifBlank { cameraBluetoothName(result.device.name) }
        val sony = result.scanRecord?.getManufacturerSpecificData(SonyControlData.COMPANY_ID)
        val pairingService = result.scanRecord?.serviceUuids?.contains(ParcelUuid(SonyControlData.pairingService)) == true
        if ((sony != null && sony.size >= 2 && sony[0].toInt() == 3 && sony[1].toInt() == 0) || pairingService) {
            val available = result.device.bondState == BluetoothDevice.BOND_BONDED || SonyControlData.advertisesPairing(sony, pairingService)
            return BleCamera(result.device.address, name.ifBlank { "Sony α" }, make = CameraMake.SONY, pairingAvailable = available)
        }
        val manufacturer = result.scanRecord?.manufacturerSpecificData
        val payloads = if (manufacturer == null) emptyList() else (0 until manufacturer.size()).map { manufacturer.valueAt(it) }
        val record = nikonAdvertisement(payloads)
        return BleCamera(result.device.address, name.ifBlank { "Nikon 相机" }, advertisementFlags = record?.flags,
            advertisedClientId = record?.clientId).also {
            if (advertisements.size < 64 || advertisements.containsKey(it.address)) {
                advertisements[it.address] = it to SystemClock.elapsedRealtime()
            }
        }
    }

    fun deviceForAddress(address: String): BluetoothDevice {
        discovered[address]?.let { return it }
        val bluetooth = requireNotNull(adapter) { "手机不支持蓝牙" }
        return bluetooth.bondedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) } ?: bluetooth.getRemoteDevice(address)
    }

    fun latestAdvertisement(address: String): BleCamera? = advertisements[address]?.takeIf {
        SystemClock.elapsedRealtime() - it.second < 30000
    }?.first

    /** A short connection wakes a sleeping wireless module; authentication starts only after a fresh advertisement. */
    suspend fun wakeSleepingCamera(address: String) {
        val operation = currentCoroutineContext()
        operation.ensureActive()
        check(refreshAdapterState()) { "请先打开手机蓝牙" }
        val (token, previous) = synchronized(this) { disconnect(); generation to previousRelease }
        awaitPreviousRelease(previous)
        val connected = CompletableDeferred<Unit>()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
                if (generation != token) return
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) connected.complete(Unit)
                else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    connected.completeExceptionally(BleTransportException(status, "休眠唤醒连接结束"))
                }
            }
        }
        val device = deviceForAddress(address)
        val connection = synchronized(this) {
            operation.ensureActive()
            if (generation != token) throw CancellationException("唤醒操作已取消")
            checkNotNull(device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)) { "无法建立休眠唤醒连接" }.also {
                wakeGatt = it
                wakeReady = connected
            }
        }
        try {
            withTimeout(10000) { connected.await() }
            delay(50)
            Log.i("ProofTray.Bluetooth", "sleep wake connection completed; rescan required")
        } finally {
            synchronized(this) {
                if (wakeGatt === connection) {
                    wakeGatt = null
                    wakeReady = null
                    closeWakeGatt(connection)
                }
            }
        }
    }

    private fun registeredClientId(): Int = identities.bleIdentity()?.let(::nikonClientId) ?: error("请先完成本应用的 BLE 配对")

    fun registeredAdvertisement(camera: BleCamera): Boolean =
        identities.bleIdentity()?.let { camera.advertisedClientId == nikonClientId(it) } ?: false

    suspend fun rediscover(names: Set<String>, previousAddress: String, expectedSerial: String? = null, registered: Boolean = true,
        retryLastAddress: Boolean = registered, knownId: String? = null): BleCamera {
        require(previousAddress.isNotBlank() || names.any(String::isNotBlank)) { "尚未记录相机，请先搜索并选择相机" }
        val clientId = if (registered) registeredClientId() else null
        val previousDevice = discovered[previousAddress]
        val scanToken = synchronized(this) { scan(stopAfterMillis = null); scanGeneration }
        if (state.value.scanning) connectionMessage("正在重新发现已关联相机；休眠唤醒可能需要片刻")
        try {
            val others = settings.state.value.cameras.filter { it.make == CameraMake.NIKON && it.id != knownId }
            val allowName = expectedSerial != null || others.none { matchesCameraName(it.name, names) }
            fun target(item: BleCamera) = !item.classic && item.make == CameraMake.NIKON &&
                (item.address == previousAddress || (allowName && matchesCameraName(item.name, names) &&
                    others.none { it.controlAddress.equals(item.address, true) }))
            fun accepted(item: BleCamera) = target(item) && (clientId == null || item.advertisedClientId == clientId)
            val first = withTimeoutOrNull(if (retryLastAddress) 4000L else 20_000L) {
                state.first { snapshot -> !snapshot.scanning || snapshot.cameras.any { item ->
                    target(item) && (item.address == previousAddress || (expectedSerial != null && accepted(item)))
                } }
            }
            var result = first ?: state.value
            if (first != null && first.scanning && first.cameras.none { it.address == previousAddress }) {
                result = withTimeoutOrNull(1000) {
                    state.first { !it.scanning || it.cameras.any { item -> item.address == previousAddress } || it.cameras.count(::accepted) > 1 }
                } ?: state.value
            }
            result.scanError?.let { error(it) }
            if (scanToken != scanGeneration) throw CancellationException("蓝牙搜索已更换")
            result.cameras.firstOrNull { it.address == previousAddress && accepted(it) }?.let { return it }
            val matches = result.cameras.filter(::accepted)
            check(matches.size <= 1) { "发现多台同名尼康机身，请从搜索列表重新选择" }
            matches.singleOrNull()?.let { return it }
            check(!registered || result.cameras.none(::target)) { "请在机身「已配对设备」中选回这台手机；若登记已移除，请重新配对" }
            if (retryLastAddress && previousAddress.isNotBlank()) {
                if (previousDevice != null) discovered.putIfAbsent(previousAddress, previousDevice)
                return BleCamera(previousAddress, names.firstOrNull().orEmpty())
            }
            error("未发现已关联相机广播，请检查蓝牙与关机后发送设置，或开机后重试")
        } finally { synchronized(this) { if (scanGeneration == scanToken) stopScan() } }
    }

    /** Wait for a selected camera's pending-image advertisement without opening GATT or repeatedly waking the camera. */
    suspend fun awaitTransferAdvertisement(names: Set<String>, accepts: (BleCamera) -> Boolean): BleCamera {
        check(refreshAdapterState()) { "请先打开手机蓝牙" }
        require(names.any(String::isNotBlank)) { "请先关联相机" }
        val scanner = adapter.bluetoothLeScanner ?: error("蓝牙扫描不可用")
        val clientId = registeredClientId()
        val filters = serviceIds.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        var firstScan = true
        while (true) {
            currentCoroutineContext().ensureActive()
            val received = AtomicBoolean(false)
            val pending = CompletableDeferred<BleCamera>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    received.set(true)
                    if (!pending.isActive) return
                    val camera = advertisedCamera(result)
                    if (camera.advertisedClientId != clientId || !matchesCameraName(camera.name, names) || !accepts(camera) ||
                        !advertisementHasTransfers(camera.advertisementFlags)) return
                    discovered[result.device.address] = result.device
                    pending.complete(camera)
                }
                override fun onScanFailed(errorCode: Int) { pending.completeExceptionally(IllegalStateException(bluetoothScanFailure(errorCode))) }
            }
            try {
                scanner.startScan(filters, scanSettings, callback)
                // Restart a silent initial scan once at six seconds. Renew a running standby scan every fifteen minutes.
                if (firstScan) withTimeoutOrNull(6000) { pending.await() }?.let { return it }
                if (!firstScan || received.get()) {
                    withTimeoutOrNull(if (firstScan) 894_000L else 900_000L) { pending.await() }?.let { return it }
                }
            } finally {
                pending.cancel()
                runCatching { scanner.stopScan(callback) }
            }
            firstScan = false
            delay(1000)
        }
    }

    @Synchronized
    fun connect(address: String, preserveHistory: Boolean = false, registerPhone: Boolean = false,
        expectedSerial: String? = null): Deferred<BleState> {
        check(refreshAdapterState()) { "请先打开手机蓝牙" }
        disconnect()
        val token = generation
        val outcome = CompletableDeferred<BleState>()
        val name = latestAdvertisement(address)?.name?.takeUnless { it == "Nikon 相机" }.orEmpty().ifBlank {
            runCatching { cameraBluetoothName(adapter.getRemoteDevice(address).name) }.getOrDefault("")
        }
        mutableState.update { it.copy(history = if (preserveHistory) it.history else emptyList(), cameraName = name, serverName = "")
            .record("正在建立 BLE 通道") }
        val previous = previousRelease
        connectionJob = scope.launch(Dispatchers.IO) {
            awaitPreviousRelease(previous)
            val connection = synchronized(this@NikonBle) {
                if (generation != token) throw endedSession(token)
                NikonGattSession(context, serviceIds,
                    discovering = { mutableState.update { if (generation == token) it.record("蓝牙已连接，正在发现相机服务") else it } },
                    controlPoint = { controlRequest(token, it) }, ended = { sessionEnded(token, it) }).also { session = it }
            }
            try {
                connection.open(deviceForAddress(address))
                step(token, "已发现相机服务，正在订阅握手响应")
                connection.subscribe()
                val start = ByteArray(17).also { SecureRandom().nextBytes(it); it[0] = 1 }
                val identity = synchronized(this@NikonBle) {
                    if (generation != token || session !== connection || !connection.live) throw endedSession(token)
                    identities.bleIdentityOrCreate()
                }
                identity.copyInto(start, 9)
                step(token, "正在验证相机身份：发送请求")
                connection.write("2000", start)
                val challenge = connection.authenticationStage(2)
                val response = NativePtp.pairReply(start, challenge)
                step(token, "相机挑战校验通过，正在等待最终确认")
                connection.write("2000", response)
                val confirmation = connection.authenticationStage(4)
                val key = NativePtp.pairingKey(start, challenge, confirmation)
                check(key.size == 8) { "蓝牙会话密钥生成失败" }
                synchronized(this@NikonBle) {
                    if (generation != token || session !== connection || !connection.live) {
                        key.fill(0)
                        throw endedSession(token)
                    }
                    sessionKey?.fill(0)
                    sessionKey = key
                }
                Log.i("ProofTray.Bluetooth", "authentication stage 4 accepted; session key derived")
                step(token, "身份验证通过，正在读取相机信息")
                val serverName = if (registerPhone) prepareSystemPairing(connection, token, settings.state.value.pairingTimeSync)
                    else connection.readOptional("2003", "") { it.toString(Charsets.UTF_8).substringBefore('\u0000').trim() }
                step(token, "正在读取蓝牙能力与相机信息")
                val features = connection.readOptional("2009", 0) {
                    if (it.size >= 4) ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int else 0
                }
                synchronized(this@NikonBle) {
                    if (generation != token || !connection.live) throw endedSession(token)
                    featureFlags = features
                }
                val modelNumber = connection.readOptional("2a24", "") { cameraBluetoothName(it.toString(Charsets.UTF_8)) }
                val serial = connection.readOptional("200b", "") { cameraSerial(it.toString(Charsets.UTF_8)).orEmpty() }
                check(cameraSerialMatches(cameraSerial(serial), expectedSerial)) { "发现的尼康机身序列号不符，请重新选择相机" }
                // 2008 is a clock/location/connection request, not an authentication acknowledgement.
                synchronized(this@NikonBle) {
                    if (generation != token || session !== connection || !connection.live) throw endedSession(token)
                    val verified = state.value.copy(authenticated = true, connectedAddress = address, serialNumber = serial,
                        serverName = serverName, modelNumber = modelNumber)
                        .record("BLE 身份验证完成")
                    mutableState.value = verified
                    outcome.complete(verified)
                    timeRequests.trySend(Unit)
                }
                Log.i("ProofTray.Bluetooth", "authentication completed")
            } catch (error: Exception) {
                if (error !is CancellationException) Log.w("ProofTray.Camera", "BLE handshake failed", error)
                connection.stop(error)
                outcome.completeExceptionally(error)
                if (error is CancellationException) throw error
            }
        }
        connectionJob?.invokeOnCompletion { error -> if (error != null) outcome.completeExceptionally(error) }
        return outcome
    }

    /** Informational characteristics are missing on some bodies; only a lost transport aborts the handshake. */
    private suspend fun <T> NikonGattSession.readOptional(id: String, fallback: T, decode: (ByteArray) -> T): T = try {
        decode(read(id))
    } catch (error: BleTransportException) { throw error }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { fallback }

    @Synchronized
    private fun controlRequest(token: Int, bytes: ByteArray) {
        if (generation != token) return
        val control = NikonControlPoint.decode(bytes) ?: return
        val requested = control.connectionRequested
        timeRequestRevision++
        mutableState.update { it.copy(connectionRequested = requested, imageTransferRequested = requested && !it.wifiRequested,
            locationRequested = control.locationRequested, timeRequested = control.timeRequested) }
        Log.i("ProofTray.Bluetooth", "camera connection request=$requested wifiPreferred=${state.value.wifiRequested}")
        if (requested && !state.value.wifiRequested) mutableTransferRequests.tryEmit(Unit)
        if (control.timeRequested) timeRequests.trySend(Unit)
    }

    private fun timeSyncAllowed(): Boolean {
        val control = state.value
        return settings.state.value.requestedTimeSync && control.timeRequested && !control.wifiRequested && control.dataReady
    }

    private suspend fun syncRequestedTime() {
        val (token, revision, connection) = synchronized(this) {
            if (!timeSyncAllowed() || session?.live != true) return
            Triple(generation, timeRequestRevision, activeSession())
        }
        try {
            val completed = connection.syncRequestedTime(::currentTime) {
                generation == token && session === connection && connection.live && timeSyncAllowed()
            }
            synchronized(this) {
                if (completed && generation == token && session === connection && timeRequestRevision == revision) {
                    mutableState.update { it.copy(timeRequested = false) }
                }
            }
            if (completed) Log.i("ProofTray.Bluetooth", "Camera time request synchronized and acknowledged")
        } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
        catch (error: Exception) { Log.w("ProofTray.Bluetooth", "Camera time request could not complete", error) }
    }

    private suspend fun awaitPreviousRelease(previous: NikonGattSession?) {
        previous?.awaitRelease()
        synchronized(this) { if (previousRelease === previous) previousRelease = null }
    }

    suspend fun disconnectForHandoff(address: String) {
        val caller = currentCoroutineContext()
        val previous = synchronized(this) {
            caller.ensureActive()
            val active = state.value.connectedAddress
            check(active.isEmpty() || active == address) { "蓝牙控制设备已更换，切换已停止" }
            disconnect()
            previousRelease
        }
        awaitPreviousRelease(previous)
    }

    @Synchronized
    private fun sessionEnded(token: Int, error: Exception) {
        if (generation != token) return
        if (error is BleTransportException) transportFailure = token to error
        generation++
        previousRelease = session
        session = null
        sessionKey?.fill(0)
        sessionKey = null
        featureFlags = 0
        mutableState.update { it.copy(authenticated = false, connectedAddress = "", serialNumber = "", modelNumber = "",
            imageTransferRequested = false, connectionRequested = false, locationRequested = false, timeRequested = false)
            .record("蓝牙连接已结束：${error.message ?: "操作未完成"}") }
    }

    private fun activeSession() = checkNotNull(session?.takeIf { it.live }) { "蓝牙控制通道未连接" }

    private fun step(token: Int, message: String) {
        if (generation != token) throw endedSession(token)
        Log.i("ProofTray.Bluetooth", "handshake: $message")
        mutableState.update { if (generation == token) it.record(message) else it }
    }

    private fun endedSession(token: Int): Exception = transportFailure?.takeIf { it.first == token }?.second
        ?: CancellationException("蓝牙连接操作已取消或被替换")

    @Synchronized
    fun completePairing(expectedControl: String, classic: String, save: () -> Unit) {
        check(state.value.authenticated && session?.live == true && state.value.connectedAddress == expectedControl) {
            "系统配对已保留，但蓝牙控制连接已结束，请重新连接"
        }
        save()
        mutableState.update { it.copy(pairingInProgress = false, systemPaired = true, classicAddress = classic)
            .record("系统已配对，蓝牙控制已连接") }
        timeRequests.trySend(Unit)
    }

    fun pairingStatus(message: String, active: Boolean, paired: Boolean, address: String = "") {
        mutableState.update { it.copy(pairingInProgress = active, systemPaired = paired, classicAddress = address).record(message) }
        timeRequests.trySend(Unit)
    }

    fun connectionMessage(message: String) {
        Log.i("ProofTray.Bluetooth", message)
        mutableState.update { it.record(message) }
    }

    fun offerClassicDevice(device: BleCamera) {
        mutableState.update { it.copy(cameras = (it.cameras.filterNot { old -> old.address == device.address } + device).takeLast(30)) }
    }

    suspend fun prepareSystemPairing(syncClock: Boolean): String {
        check(state.value.authenticated) { "请先完成 BLE 身份验证" }
        return prepareSystemPairing(activeSession(), generation, syncClock)
    }

    private suspend fun prepareSystemPairing(connection: NikonGattSession, token: Int, syncClock: Boolean): String {
        step(token, "正在登记手机名称")
        val namePayload = bluetoothClientName(Build.MODEL)
        val nameWritten = connection.write("2002", namePayload, tolerateAttributeRejection = true)
        // Registration has its own server-name read, after the phone name is acknowledged on this same link.
        val serverName = cameraBluetoothName(connection.read("2003").toString(Charsets.UTF_8))
        synchronized(this) {
            if (generation != token || session !== connection || !connection.live) throw endedSession(token)
            mutableState.update { it.copy(serverName = serverName,
                clientName = if (nameWritten) namePayload.toString(Charsets.UTF_8).substringBefore('\u0000') else it.clientName) }
        }
        if (!connection.writable("2006") || !shouldSyncNikonPairingClock(serverName, syncClock)) return serverName
        step(token, "正在同步相机时间并准备系统配对")
        val timeWritten = connection.write("2006", currentTime(), tolerateAttributeRejection = true)
        if (!timeWritten) step(token, "机身未接受校时，继续系统配对")
        return serverName
    }

    private fun currentTime(): ByteArray {
        val now = Instant.now()
        val rules = ZoneId.systemDefault().rules
        val offset = rules.getStandardOffset(now).totalSeconds / 60
        val daylight = rules.getDaylightSavings(now).toMinutes().toInt()
        return NativePtp.currentTime(now.toEpochMilli(), offset, daylight)
    }

    fun reserveWifi() {
        mutableState.update { it.copy(wifiRequested = true, imageTransferRequested = false) }
    }

    fun releaseWifiReservation() {
        mutableState.update { it.copy(wifiRequested = false, imageTransferRequested = it.connectionRequested) }
        timeRequests.trySend(Unit)
    }

    suspend fun startCameraHotspot(beforeWake: (CameraWifiSettings) -> Unit): CameraWifiSettings {
        val (connection, key) = synchronized(this) {
            check(state.value.dataReady) { "请先完成相机蓝牙连接" }
            activeSession() to (sessionKey?.clone() ?: error("蓝牙认证密钥不可用，请重新连接"))
        }
        fun ready(): Boolean {
            val control = state.value
            return session === connection && connection.live && control.wifiRequested && control.dataReady
        }
        try {
            check(ready()) { "Wi-Fi 切换已取消" }
            try { acknowledgeConnectionRequest(connection, ::ready) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalStateException) {
                if (!ready()) throw error
                Log.i("ProofTray.Bluetooth", "Camera did not acknowledge the pending data request")
            }
            if (connection.readable("2001")) check(connection.read("2001").isNotEmpty()) { "相机尚未就绪，请唤醒机身后重试" }
            val packet = connection.read("2004")
            check(ready()) { "读取热点配置时蓝牙连接已变化" }
            val settings = decodeCameraWifiSettings(packet) { NativePtp.decryptConnectionField(key, it) }
            beforeWake(settings)
            check(connection.write("2005", byteArrayOf(1), endsControlLink = true, allowed = ::ready)) { "开启热点前蓝牙会话已更换，请重新切换 Wi-Fi" }
            Log.i("ProofTray.Bluetooth", "Wi-Fi wake request accepted by GATT")
            mutableState.update { it.record("机身已接收开启热点请求，正在交给 Android 连接") }
            return settings
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.w("ProofTray.Bluetooth", "Wi-Fi wake request failed", error)
            mutableState.update { it.record("请求 Wi-Fi 失败：${error.message}") }
            throw error
        } finally { key.fill(0) }
    }

    @Synchronized
    fun locationSession(): Int? {
        val current = state.value
        if (!current.dataReady || sessionKey == null || session?.let { it.live && it.writable("2007") } != true) return null
        if (featureFlags != 0 && featureFlags and (1 shl 7) == 0) return null
        return generation
    }

    fun transferRole(preference: BluetoothRole): BluetoothRole {
        val role = if (preference != BluetoothRole.AUTOMATIC) preference
            else if (featureFlags and (1 shl 13) != 0) BluetoothRole.WAIT_FOR_CAMERA else BluetoothRole.CONNECT_TO_CAMERA
        Log.i("ProofTray.Bluetooth", "Bluetooth role=$role; features=0x${featureFlags.toString(16)}")
        return role
    }

    suspend fun requestBluetoothTransfer() {
        check(state.value.authenticated) { "请先连接 BLE 控制通道" }
        check(state.value.systemPaired) { "请先完成系统蓝牙配对" }
        check(featureFlags == 0 || featureFlags and (1 shl 5) != 0) { "相机未报告蓝牙图片传输能力" }
        stopScan()
        val connection = activeSession()
        acknowledgeConnectionRequest(connection) { !state.value.wifiRequested && !state.value.pairingInProgress }
        val handoff = needsBluetoothHandoff(state.value.modelNumber)
        check(connection.write("2005", byteArrayOf(2), endsControlLink = handoff) {
            val current = state.value
            !current.wifiRequested && current.dataReady
        }) { "正在配对或使用 Wi-Fi，蓝牙数据传输暂候" }
    }

    private suspend fun acknowledgeConnectionRequest(connection: NikonGattSession, allowed: () -> Boolean) {
        val token = generation
        connection.acknowledgeConnectionRequest { generation == token && session === connection && connection.live && allowed() }
    }

    suspend fun releaseBluetoothTransfer() {
        if (state.value.authenticated && featureFlags and (1 shl 10) != 0) {
            activeSession().write("2005", byteArrayOf(0x80.toByte())) { !state.value.wifiRequested && !state.value.pairingInProgress }
        }
    }

    fun acknowledgeTransferRequest() { mutableState.update { it.copy(imageTransferRequested = false) } }

    fun pairedDevices(): List<BleCamera> {
        check(refreshAdapterState()) { "请先打开手机蓝牙" }
        return adapter.bondedDevices.map {
            BleCamera(it.address, cameraBluetoothName(it.name).ifBlank { "已配对设备" }, it.type != BluetoothDevice.DEVICE_TYPE_LE)
        }
            .sortedBy { it.name }
    }

    suspend fun updateLocation(payload: ByteArray, expectedSession: Int) {
        require(payload.size == 41)
        check(locationSession() == expectedSession) { "蓝牙位置同步会话已失效" }
        check(activeSession().write("2007", payload) { locationSession() == expectedSession }) { "蓝牙位置同步会话已失效" }
        check(locationSession() == expectedSession) { "蓝牙位置同步时连接已变化" }
    }

    @Synchronized
    fun stopScan() {
        scanGeneration++
        scanJob?.cancel()
        activeScan?.let { callback -> runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) } }
        activeScan = null
        mutableState.update { it.copy(scanning = false) }
    }

    @Synchronized
    fun disconnect() {
        generation++
        transportFailure = null
        featureFlags = 0
        sessionKey?.fill(0)
        sessionKey = null
        connectionJob?.cancel()
        stopScan()
        session?.let { previousRelease = it; it.stop() }
        session = null
        wakeReady?.cancel()
        wakeReady = null
        closeWakeGatt(wakeGatt)
        wakeGatt = null
        mutableState.update { it.copy(authenticated = false, connectedAddress = "", imageTransferRequested = false,
            connectionRequested = false, locationRequested = false, timeRequested = false, serialNumber = "", modelNumber = "") }
    }

    fun clearDeviceState() {
        mutableState.update { BleState(adapterEnabled = it.adapterEnabled) }
    }

    fun close() = disconnect()

    private fun closeWakeGatt(connection: BluetoothGatt?) {
        runCatching { connection?.disconnect() }
        runCatching { connection?.close() }
    }
}
