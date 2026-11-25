package org.prooftray.app.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.android.ble.callback.FailCallback

@SuppressLint("MissingPermission")
internal class SonyControl(private val context: Context, private val deviceForAddress: (String) -> BluetoothDevice,
    private val report: (Boolean) -> Unit) {
    private var current: SonyBleSession? = null
    private val connections = Mutex()
    private val locationRevision = MutableStateFlow(0)
    val locationChanges = locationRevision.asStateFlow()

    suspend fun pair(target: BleCamera, restoreOnly: Boolean, progress: (String) -> Unit): String {
        val device = deviceForAddress(target.address)
        check(!restoreOnly || device.bondState != BluetoothDevice.BOND_NONE) { "系统配对已移除，请重新配对这台相机" }
        check(target.pairingAvailable || device.bondState == BluetoothDevice.BOND_BONDED) { "请在机身打开「智能手机连接」，再选择这台相机" }
        val systemPairing = usesSonySystemPairing(Build.VERSION.SDK_INT, Build.MANUFACTURER)
        if (!systemPairing && device.bondState == BluetoothDevice.BOND_NONE) {
            progress("正在准备手机蓝牙发现")
            val adapter = requireNotNull(context.getSystemService(BluetoothManager::class.java).adapter) { "手机不支持蓝牙" }
            awaitSonyDiscoveryGrace(context, adapter)
        }
        progress("正在连接所选机身的蓝牙")
        var confirmed = false
        var reopened = false
        while (true) {
            val link = session(device, forPairing = !confirmed)
            if (device.bondState == BluetoothDevice.BOND_BONDING) {
                progress("正在等待已有的系统配对请求完成")
                link.awaitBond(device, requestBond = false)
            }
            val bonded = device.bondState == BluetoothDevice.BOND_BONDED
            try {
                if (!confirmed) {
                    if (bonded) progress("正在核对已有配对")
                    check(!restoreOnly || bonded) { "系统配对未确认，请核对这台相机的配对状态" }
                    if (!bonded || (!restoreOnly && !systemPairing && !link.acceptsExistingBond())) {
                        link.pair(device, systemPairing, progress)
                    }
                    confirmed = true
                }
                progress("正在确认机身的蓝牙控制服务")
                return link.confirmControl(device)
            } catch (error: SonyBluetoothFailure) {
                val newBond = !bonded && device.bondState != BluetoothDevice.BOND_NONE
                if (reopened || !canReconnectSonyPairing(error.gattStatus, confirmed, newBond)) throw error
                if (newBond) {
                    link.awaitBond(device, requestBond = false)
                    confirmed = true
                }
                progress("系统已配对，正在重新连接这台机身的蓝牙控制服务")
                reopened = true
                link.close()
            }
        }
    }

    suspend fun connection(address: String, manual: CameraConnection? = null): CameraConnection {
        val device = deviceForAddress(address)
        val session = session(device)
        return if (manual == null) session.hotspot(device)
            else manual.copy(sonyAccess = session.access(device), sonyCamera = true)
    }

    suspend fun networkId(address: String): UUID? {
        val device = deviceForAddress(address)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "索尼系统配对已移除，请重新配对这台相机" }
        return session(device).networkId()
    }

    suspend fun location(address: String): SonyBleSession {
        val device = deviceForAddress(address)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "请先在手机和机身确认索尼配对" }
        val session = session(device)
        check(session.supportsLocation) { "此机身未提供蓝牙位置关联服务" }
        return session
    }

    @Synchronized fun isCurrent(session: SonyBleSession): Boolean = current === session && session.live

    private suspend fun session(device: BluetoothDevice, forPairing: Boolean = false): SonyBleSession = connections.withLock {
        val caller = currentCoroutineContext()
        val (previous, session) = synchronized(this) {
            caller.ensureActive()
            current?.takeIf { it.live && (forPairing || it.controlReady) && it.bluetoothDevice == device }?.let { return@withLock it }
            val previous = current
            val next = SonyBleSession(context, forPairing, ::controlState) { locationRevision.update { it + 1 } }
            current = next
            report(false)
            previous to next
        }
        previous?.close()
        try {
            caller.ensureActive()
            session.open(device)
            if (!forPairing) session.confirmControl(device)
            synchronized(this) { check(current === session) { "索尼蓝牙连接已更换" } }
        } catch (failure: Exception) {
            synchronized(this) { if (current === session) { current = null; report(false) } }
            session.close()
            throw failure
        }
        session
    }

    @Synchronized private fun controlState(session: SonyBleSession, ready: Boolean) {
        if (current === session) report(ready && session.controlReady)
    }

    fun close() {
        val old = synchronized(this) {
            val session = current ?: return
            current = null
            report(false)
            session
        }
        old.close()
    }
}

/** Foreground control session for the camera selected by the user. */
@SuppressLint("MissingPermission")
internal class SonyBleSession(
    context: Context, private val forPairing: Boolean, private val report: (SonyBleSession, Boolean) -> Unit, locationChanged: () -> Unit
) {
    @Volatile private var control: BluetoothGattService? = null
    @Volatile private var pairing: BluetoothGattService? = null
    @Volatile private var verified: Pair<BluetoothGattService, String>? = null
    @Volatile private var functions: Pair<BluetoothGattService, Set<Int>>? = null
    @Volatile private var location: BluetoothGattService? = null
    private val hotspotRequests = Mutex()
    @Volatile private var hotspotStatus: SonyHotspotStatus? = null
    private val client = SonyGattClient(context, forPairing, ::bindServices, ::onServicesInvalidated)
    @Volatile var bluetoothDevice: BluetoothDevice? = null
        private set
    val live: Boolean get() = client.ready
    val controlReady: Boolean get() = live && verified?.let { it.first === control } == true
    val supportsLocation: Boolean get() = listOf(SonyLocationProtocol.STATUS, SonyLocationProtocol.LOCATION,
        SonyLocationProtocol.CAPABILITIES, SonyLocationProtocol.LOCK, SonyLocationProtocol.ENABLE,
        SonyLocationProtocol.TIME_SETTING, SonyLocationProtocol.AREA_SETTING).all { location.find(it) != null }
    val locationSync = SonyLocationSession(object : SonyLocationTransport {
        override val live: Boolean get() = this@SonyBleSession.live
        override suspend fun read(id: Int): ByteArray = this@SonyBleSession.read(requiredLocation(id))
        override suspend fun write(id: Int, value: ByteArray, allowed: () -> Boolean) {
            try { this@SonyBleSession.write(requiredLocation(id), value, allowed = allowed) }
            catch (error: SonyBluetoothFailure) {
                if (id != SonyLocationProtocol.LOCK || !value.contentEquals(byteArrayOf(1))) throw error
                val message = when (error.gattStatus) {
                    0x9d -> "机身暂不允许位置关联，请检查机身设置"
                    0x9f -> "位置关联正由其他设备使用，请先关闭其他手机或 Creators’ App 的位置关联"
                    else -> throw error
                }
                throw IllegalStateException(message, error)
            }
        }
        override suspend fun observe(callback: ((ByteArray) -> Unit)?) {
            client.observe(requiredLocation(SonyLocationProtocol.STATUS), "设置位置状态通知（DD01）", 8000, callback)
        }
        override fun close() = this@SonyBleSession.close()
    }, SystemClock::elapsedRealtimeNanos, locationChanged)

    private fun bindServices(gatt: BluetoothGatt): Boolean {
        control = gatt.getService(SonyControlData.controlService)
        pairing = gatt.getService(SonyControlData.pairingService)
        location = gatt.getService(SonyLocationProtocol.service)
        return control != null || (forPairing && pairing.find(0xee01) != null)
    }

    private fun onServicesInvalidated(reason: Exception) {
        verified = null; control = null; pairing = null; location = null; functions = null
        hotspotStatus?.failed(reason)
        report(this, false)
    }

    fun close() = client.close()

    suspend fun open(device: BluetoothDevice) {
        bluetoothDevice = device
        client.open(device)
    }

    suspend fun acceptsExistingBond(): Boolean {
        if (control == null) return false
        return try {
            if (!supportsFunction(SonyControlData.SSH_INFO, 0xcc17)) return false
            // A successful protected read confirms access even if Wi-Fi authentication is still initializing.
            read(required(0xcc17), 30_000)
            true
        } catch (error: SonyBluetoothFailure) {
            if (!live || !error.attributeRejected) throw error
            false
        }
    }

    suspend fun confirmControl(device: BluetoothDevice): String {
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "手机与这台机身的系统配对尚未确认" }
        val service = control ?: throw sonyGattFailure("读取机身控制服务", FailCallback.REASON_NULL_ATTRIBUTE)
        verified?.takeIf { live && it.first === service }?.let { return it.second }
        check(read(required(0xcc0a)).isNotEmpty()) { "机身返回的固件信息为空（CC0A）" }
        val model = read(required(0xcc0b)).toString(Charsets.US_ASCII).trimEnd('\u0000').trim()
        check(model.isNotBlank()) { "机身返回的型号为空（CC0B）" }
        readFunctions()
        verified = service to model
        if (!controlReady || device.bondState != BluetoothDevice.BOND_BONDED) {
            throw sonyGattFailure("确认蓝牙控制连接", FailCallback.REASON_DEVICE_DISCONNECTED)
        }
        report(this, true)
        return model
    }

    private suspend fun readFunctions(): Set<Int>? {
        val service = control ?: throw sonyGattFailure("读取机身控制服务", FailCallback.REASON_NULL_ATTRIBUTE)
        functions?.takeIf { it.first === service }?.let { return it.second }
        val characteristic = service.find(0xcc0d) ?: return null
        val supported = SonyControlData.supportedFunctions(read(characteristic))
        if (control !== service) throw sonyGattFailure("读取机身功能列表（CC0D）", FailCallback.REASON_DEVICE_DISCONNECTED)
        functions = service to supported
        return supported
    }

    private suspend fun supportsFunction(feature: Int, legacyCharacteristic: Int): Boolean =
        readFunctions()?.contains(feature) ?: (control.find(legacyCharacteristic) != null)

    suspend fun pair(device: BluetoothDevice, systemPairing: Boolean, progress: (String) -> Unit) {
        val command = requireNotNull(pairing.find(0xee01)) { "未发现索尼配对服务，请在机身打开「智能手机连接」并保持在等待连接画面" }
        val status = pairing.find(0xee03)
        val notify = status != null && status.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val result = CompletableDeferred<SonyControlData.PairingReply>()
        var subscribed = false
        try {
            if (status != null && (notify || status.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)) {
                progress("正在订阅机身配对进度")
                subscribed = optionalPairingRequest {
                    client.observe(status, "订阅机身配对进度（EE03）", 6000) { data -> SonyControlData.pairResult(data)?.let(result::complete) }
                }
            }
            pairing.find(0xee04)?.takeIf { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }?.let { info ->
                progress("正在读取机身配对信息")
                optionalPairingRequest { read(info) }
            }
            supervisorScope {
                val confirmation = async(start = CoroutineStart.LAZY) {
                    if (!systemPairing) {
                        progress("正在发送配对请求，请在手机和机身上确认")
                        try { write(command, byteArrayOf(6, 8, 1, 0, 0, 0, 0), 55_000) }
                        catch (error: SonyBluetoothFailure) {
                            if (error.gattStatus != FailCallback.REASON_DEVICE_DISCONNECTED || !result.isCompleted ||
                                result.await().failure != null) throw error
                        }
                    }
                    progress("正在等待系统配对，请核对手机和机身的提示")
                    awaitBond(device, requestBond = systemPairing)
                }
                try {
                    select<Unit> {
                        result.onAwait { reply ->
                            reply.failure?.let { throw SonyBluetoothFailure(it, reply.diagnostic) }
                            confirmation.await()
                        }
                        confirmation.onAwait { }
                    }
                }
                finally { confirmation.cancel() }
            }
        } catch (error: SonyBluetoothFailure) {
            if (error.gattStatus != null && result.isCompleted) {
                val reply = result.await()
                reply.failure?.let { throw SonyBluetoothFailure(it, "${reply.diagnostic} · ${error.diagnostic}", cause = error) }
            }
            throw error
        } finally {
            if (subscribed && status != null) stopObserving(status)
        }
    }

    private suspend fun optionalPairingRequest(block: suspend () -> Unit): Boolean = try { block(); true }
        catch (error: SonyBluetoothFailure) {
            currentCoroutineContext().ensureActive()
            if (!live || (!error.attributeRejected && error.gattStatus != FailCallback.REASON_REQUEST_FAILED)) throw error
            Log.i("ProofTray.Bluetooth", "Optional Sony pairing request skipped: ${error.diagnostic}")
            false
        }

    suspend fun awaitBond(device: BluetoothDevice, requestBond: Boolean) {
        currentCoroutineContext().ensureActive()
        if (requestBond && device.bondState == BluetoothDevice.BOND_NONE) {
            check(device.createBond() || device.bondState != BluetoothDevice.BOND_NONE) { "手机未能启动系统配对，请在机身重新打开「智能手机连接」" }
        }
        var bonding = false
        val bonded = withTimeoutOrNull(45_000) {
            var state = device.bondState
            while (state != BluetoothDevice.BOND_BONDED) {
                check(!bonding || state != BluetoothDevice.BOND_NONE) { "系统配对已取消或未获确认，请在两端重新核对配对请求" }
                bonding = state == BluetoothDevice.BOND_BONDING
                // Android owns an accepted createBond request even if the pairing GATT link has already closed.
                if (!requestBond && !bonding && !live) throw sonyGattFailure("等待系统配对", FailCallback.REASON_DEVICE_DISCONNECTED)
                delay(150)
                state = device.bondState
            }
            true
        } ?: false
        check(bonded) { "等待系统配对确认超时，请查看手机和机身上的配对请求" }
    }

    suspend fun networkId(): UUID? {
        if (!supportsFunction(SonyControlData.ACCESS_POINT, 0xcca2)) return null
        val id = control.find(0xcca2) ?: return null
        val bytes = try { read(id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return null }
        if (bytes.size != 16 || bytes.all { it == 0.toByte() }) return null
        return ByteBuffer.wrap(bytes).let { UUID(it.long, it.long) }
    }

    suspend fun access(device: BluetoothDevice): SonyAccess? =
        if (supportsFunction(SonyControlData.SSH_INFO, 0xcc17)) readAccess(device) else null

    private suspend fun readAccess(device: BluetoothDevice): SonyAccess? {
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "索尼系统配对已移除，请重新配对这台相机" }
        return SonyControlData.access(read(required(0xcc17), 30_000))
    }

    suspend fun hotspot(device: BluetoothDevice): CameraConnection = hotspotRequests.withLock {
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "请先在手机和机身上确认索尼配对" }
        val status = required(0xcc09)
        val watch = SonyHotspotStatus()
        hotspotStatus = watch
        try {
            watch.guard {
                client.observe(status, "订阅机身热点状态（CC09）", 6000) { data -> SonyControlData.wifiStatus(data)?.let(watch::notified) }
                startHotspot(watch, status)
                watch.beginInfo()
                val hotspot = withTimeoutOrNull(15_000) {
                    val ssid = wifiText(0xcc06)
                    val passphrase = wifiText(0xcc07)
                    val bssid = control.find(0xcc0c)?.takeIf { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }?.let {
                        try { SonyControlData.bssid(read(it, 15_000)) }
                        catch (failure: SonyBluetoothFailure) {
                            if (failure.gattStatus != FailCallback.REASON_REQUEST_FAILED || !live) throw failure
                            null // Android could not start the optional read. A returned ATT error still ends the exchange.
                        }
                    }
                    CameraConnection(ssid = ssid, password = passphrase, sonyCamera = true, bssid = bssid)
                } ?: throw IOException("读取索尼热点参数超时（CC06/CC07/CC0C）")
                hotspot.copy(sonyAccess = access(device))
            }
        } catch (error: Exception) {
            close()
            throw error
        } finally {
            watch.close()
            if (hotspotStatus === watch) hotspotStatus = null
            stopObserving(status)
        }
    }

    private suspend fun startHotspot(watch: SonyHotspotStatus, status: BluetoothGattCharacteristic) {
        val readable = status.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        suspend fun refresh() {
            val revision = watch.revision()
            watch.read(SonyControlData.wifiStatus(read(status)), revision)
        }
        val ready = withTimeoutOrNull(30_000) {
            if (readable) refresh()
            var startSent = watch.beginStart()
            if (startSent) write(required(0xcc08), byteArrayOf(1))
            watch.checkFailure()
            while (watch.latest()?.ready != true) {
                if (!startSent && watch.latest()?.terminated == true) {
                    write(required(0xcc08), byteArrayOf(1))
                    startSent = true
                } else if (!watch.changed() && readable) refresh()
                watch.checkFailure()
            }
            true
        }
        check(ready == true) { "等待机身开启 Wi-Fi 超时，请检查机身连接状态" }
    }

    private suspend fun wifiText(id: Int): String {
        val characteristic = required(id)
        val text = SonyControlData.wifiText(read(characteristic, 15_000))
        check(text.isNotEmpty()) { "${characteristic.displayName()}为空" }
        return text
    }

    private fun required(id: Int) = control.find(id) ?: throw sonyGattFailure("查找机身特征（${id.toString(16).uppercase()}）",
        FailCallback.REASON_NULL_ATTRIBUTE)
    private fun requiredLocation(id: Int) = requireNotNull(location.find(id)) { "机身位置关联服务已失效" }

    private fun BluetoothGattService?.find(id: Int): BluetoothGattCharacteristic? =
        this?.characteristics?.firstOrNull { (it.uuid.mostSignificantBits ushr 32) == id.toLong() }

    private fun BluetoothGattCharacteristic.displayName(): String {
        val id = (uuid.mostSignificantBits ushr 32).toInt()
        val name = when (id) {
            0xee01 -> "机身配对请求"
            0xee04 -> "机身配对信息"
            0xcc06 -> "机身热点名称"
            0xcc07 -> "机身热点密码"
            0xcc08 -> "机身热点开关"
            0xcc09 -> "机身热点状态"
            0xcc0a -> "机身固件信息"
            0xcc0b -> "机身型号"
            0xcc0d -> "机身功能列表"
            0xcc17 -> "机身访问认证信息"
            0xcca2 -> "机身网络标识"
            else -> "蓝牙特征"
        }
        return "$name（${id.toString(16).uppercase()}）"
    }

    private suspend fun read(characteristic: BluetoothGattCharacteristic, timeout: Long = 8000): ByteArray =
        client.read(characteristic, "读取${characteristic.displayName()}", timeout)

    private suspend fun write(
        characteristic: BluetoothGattCharacteristic, value: ByteArray, timeout: Long = 8000, allowed: () -> Boolean = { true }
    ) {
        client.write(characteristic, value, "写入${characteristic.displayName()}", timeout, allowed)
    }

    private suspend fun stopObserving(item: BluetoothGattCharacteristic) {
        if (live) withContext(NonCancellable) {
            try { client.observe(item, "结束${item.displayName()}通知", 6000, null) }
            catch (failure: SonyBluetoothFailure) { Log.i("ProofTray.Bluetooth", failure.diagnostic) }
        }
    }
}
