package org.prooftray.app.camera

import org.prooftray.app.data.AppSettingsRepository

enum class SavedCameraChannel { WIFI, BLUETOOTH }

data class CameraChangeContext(
    val generation: Int, val selectedId: String?, val wifi: Boolean, val connectedId: String?,
    val sonyAddress: String?, val nikonAddress: String?
) {
    val hasLiveConnection get() = wifi || sonyAddress != null || nikonAddress != null

    fun switchesDevice(id: String?, make: CameraMake, address: String): Boolean =
        (wifi && (id == null || connectedId != id)) ||
            (sonyAddress != null && (make != CameraMake.SONY || !address.equals(sonyAddress, true))) ||
            (nikonAddress != null && (make != CameraMake.NIKON || !address.equals(nikonAddress, true)))

    fun requireApproval(required: Boolean, approval: CameraChangeContext?) {
        check(!required || approval == this) { "当前连接已变化，请确认切换相机" }
    }
}

internal fun CameraState.changeContext(ble: BleState, selectedId: String?) =
    CameraChangeContext(generation, selectedId, connected, connectedId, sonyControlAddress,
        ble.connectedAddress.takeIf { ble.authenticated && it.isNotBlank() })

/** Called with the transfer gate held, so no import can start between validation and disconnect. */
internal fun AppSettingsRepository.applyClassicDeviceChange(
    address: String, connection: CameraChangeContext, approval: CameraChangeContext?,
    isBonded: (String) -> Boolean, disconnect: () -> Unit
) {
    val id = checkNotNull(connection.selectedId) { "请先选择尼康相机" }
    var changed = false
    changeCameras { registry ->
        val updated = registry.chooseTransferDevice(id, address)
        changed = updated != registry
        if (changed) {
            check(address.isEmpty() || isBonded(address)) { "所选传图设备已从系统配对中移除" }
            connection.requireApproval(connection.hasLiveConnection, approval)
        }
        updated
    }
    if (changed && connection.hasLiveConnection) disconnect()
}

/** Captures the user's choice before leaving the app for a permission or Wi-Fi dialog. */
data class SavedCameraRequest(val token: String, val targetId: String, val previousId: String?, val generation: Int,
                              val channel: SavedCameraChannel = SavedCameraChannel.WIFI,
                              val approval: CameraChangeContext? = null) {
    fun current(token: String, selectedId: String?, generation: Int) =
        this.token == token && previousId == selectedId && this.generation == generation
}

internal fun CameraState.canRequestSaved(id: String, channel: SavedCameraChannel = SavedCameraChannel.WIFI) =
    accessRequest == null && controlAttempt == null && !connecting && (channel == SavedCameraChannel.BLUETOOTH || connectedId != id)

internal fun verifiedWifiTarget(expectedSerial: String?, actualSerial: String?, boundSonyRoute: Boolean): Boolean {
    if (expectedSerial != null) return actualSerial != null && cameraSerialMatches(expectedSerial, actualSerial)
    return boundSonyRoute
}

/** Only a newly confirmed bond or an explicitly associated classic address can complete registration. */
internal fun confirmedClassicAddress(address: String, selected: String?, previouslyBonded: Set<String>) =
    address.equals(selected, true) || previouslyBonded.none { it.equals(address, true) }

internal fun requireNikonIdentity(originalAddress: String, verifiedAddress: String, expectedSerial: String?, actualSerial: String?) {
    val sameAddress = originalAddress.isNotBlank() && originalAddress.equals(verifiedAddress, true)
    val sameSerial = expectedSerial != null && actualSerial != null && cameraSerialMatches(expectedSerial, actualSerial)
    check(if (expectedSerial != null) sameSerial else sameAddress) { "无法核对所选尼康机身身份，请重新确认相机" }
}

internal fun requireNikonClassicIdentity(bleSerial: String?, classicSerial: String?) {
    val control = bleSerial?.let(::cameraSerial)
    val data = classicSerial?.let(::cameraSerial)
    check(control != null && data != null && cameraSerialMatches(control, data)) {
        "无法确认经典蓝牙传图设备与 BLE 控制属于同一机身，已保留旧关联"
    }
}
