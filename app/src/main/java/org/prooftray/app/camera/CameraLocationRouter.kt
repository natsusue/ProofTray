package org.prooftray.app.camera

import android.os.SystemClock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.prooftray.app.data.AppSettingsRepository

/** Picks the link (Sony BLE, Nikon BLE or Nikon Wi-Fi) that receives location samples and keeps each sample bound to it. */
internal class CameraLocationRouter(
    private val settings: AppSettingsRepository, private val camera: StateFlow<CameraState>, private val sony: SonyControl,
    private val ble: NikonBle, private val requests: Mutex, private val wifiSession: () -> CameraBrowseSession?
) {
    private val writes = Mutex()
    private var target: CameraLocationChannel? = null
    /** Set when the user ends the Sony control link, so the location service does not reopen it on its own. */
    @Volatile var sonyStopped = false

    suspend fun prepare(owner: Any, resume: Boolean): CameraLocationChannel? = writes.withLock {
        val previous = target
        if (previous != null && previous.owner === owner && previous.isCurrent()) {
            previous.prepare(resume)
            return@withLock previous
        }
        target = null
        previous?.clear()
        val selected = settings.state.value
        fun selectionCurrent(): Boolean {
            val latest = settings.state.value
            val activeId = camera.value.activeCameraId
            if (activeId != null && activeId != latest.selectedCameraId) return false
            if (!latest.locationSync || camera.value.manuallyDisconnected || (latest.sonySelected && sonyStopped)) return false
            return latest.selectedCameraId == selected.selectedCameraId && latest.selectedAddress == selected.selectedAddress
        }
        if (!selectionCurrent()) return@withLock null
        val channel = if (selected.sonySelected) {
            check(selected.sonyAddress.isNotBlank()) { "请先添加索尼相机" }
            // Opening the location service only uses bonded BLE; it never requests a hotspot or a Wi-Fi session.
            SonyLocationChannel(owner, sony, sony.location(selected.sonyAddress), ::selectionCurrent)
        } else {
            val bleSession = ble.locationSession()
            val wifi = wifiSession()
            when {
                bleSession != null && ble.state.value.connectedAddress == selected.controlAddress ->
                    NikonBleLocationChannel(owner, ble, bleSession, ::selectionCurrent)
                wifi != null && wifi.link.supportsLocation() -> NikonWifiLocationChannel(owner, wifi, requests, wifiSession, ::selectionCurrent)
                else -> return@withLock null
            }
        }
        target = channel
        check(channel.isCurrent()) { "位置同步目标已变化" }
        channel.prepare(resume)
        channel
    }

    suspend fun sync(channel: CameraLocationChannel, fix: CameraLocationFix): String = writes.withLock {
        check(target === channel && channel.isCurrent()) { "位置同步目标已变化" }
        check(fix.fresh(SystemClock.elapsedRealtimeNanos(), channel.maxAgeNanos)) { "定位已过期，等待新的位置" }
        channel.send(fix)
    }

    suspend fun finishSelection() = writes.withLock {
        val current = target
        target = null
        current?.clear()
        Unit
    }

    suspend fun clear(owner: Any) = writes.withLock {
        val current = target?.takeIf { it.owner === owner } ?: return@withLock
        target = null
        current.clear()
    }
}

private fun nikonLocationPayload(fix: CameraLocationFix?): ByteArray =
    if (fix == null) NativePtp.location(0.0, 0.0, Double.NaN, 0, 0, false)
    else NativePtp.location(fix.latitude, fix.longitude, fix.altitude, fix.timeMillis, 0, true)

private class SonyLocationChannel(
    owner: Any, private val control: SonyControl, private val session: SonyBleSession, private val selected: () -> Boolean
) : CameraLocationChannel(owner, sony = true) {
    override fun isCurrent() = selected() && control.isCurrent(session)
    override fun requested() = !session.locationSync.paused
    override suspend fun prepare(resume: Boolean) = session.locationSync.prepare(resume)
    override suspend fun send(fix: CameraLocationFix): String {
        session.locationSync.send(fix, ::isCurrent)
        return "索尼蓝牙"
    }
    override suspend fun clear() = session.locationSync.stop()
}

private class NikonBleLocationChannel(
    owner: Any, private val ble: NikonBle, private val session: Int, private val selected: () -> Boolean
) : CameraLocationChannel(owner) {
    private var sent = false
    override fun isCurrent() = selected() && ble.locationSession() == session
    override fun requested() = ble.state.value.locationRequested
    override suspend fun send(fix: CameraLocationFix): String {
        sent = true
        ble.updateLocation(nikonLocationPayload(fix), session)
        return "BLE"
    }
    override suspend fun clear() {
        if (sent && ble.locationSession() == session) ble.updateLocation(nikonLocationPayload(null), session)
        sent = false
    }
}

private class NikonWifiLocationChannel(
    owner: Any, private val wifi: CameraBrowseSession, private val requests: Mutex,
    private val currentWifi: () -> CameraBrowseSession?, private val selected: () -> Boolean
) : CameraLocationChannel(owner) {
    private var sent = false
    private fun sameSession() = currentWifi()?.let { it.link === wifi.link && it.generation == wifi.generation } == true
    override fun isCurrent() = selected() && sameSession()
    override fun requested() = true
    override suspend fun send(fix: CameraLocationFix): String = requests.withLock {
        check(isCurrent()) { "相机会话已更换" }
        sent = true
        wifi.link.updateLocation(nikonLocationPayload(fix))
        "Wi-Fi"
    }
    override suspend fun clear() = requests.withLock {
        if (sent && sameSession()) wifi.link.updateLocation(nikonLocationPayload(null))
        sent = false
    }
}
