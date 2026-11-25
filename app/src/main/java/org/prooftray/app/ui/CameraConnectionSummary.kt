package org.prooftray.app.ui

import org.prooftray.app.camera.ConnectionPhase
import org.prooftray.app.camera.CameraMake
import org.prooftray.app.camera.DownloadStatus
import org.prooftray.app.domain.AutoDownloadKind

internal data class CameraConnectionSummary(val title: String, val detail: String, val busy: Boolean, val bluetoothReady: Boolean)

internal val LibraryUiState.connectionCameraName: String get() = camera.pairingTarget?.name ?: when {
    camera.connected && (camera.activeCameraId == null || camera.activeCameraId != settings.selectedCameraId) -> camera.cameraName
    else -> settings.selectedName
}

internal val LibraryUiState.cameraTransferRunning: Boolean get() = automatic.transferring || camera.tasks.any { it.status == DownloadStatus.RUNNING }

internal val LibraryUiState.hasCameraConnection: Boolean get() = camera.connected || camera.connecting || ble.authenticated ||
    camera.sonyControlConnected || ble.pairingInProgress || automatic.checking

internal val LibraryUiState.cameraSelectionBlock: String? get() = when {
    cameraTransferRunning -> "请先完成或停止当前传图"
    camera.connectionPhase == ConnectionPhase.DISCONNECTING -> "正在断开相机"
    else -> null
}

internal val LibraryUiState.hasSelectedNikon: Boolean get() = camera.pairingTarget == null && settings.selectedAddress.isNotBlank() &&
    !settings.sonySelected && camera.make != CameraMake.SONY

internal fun cameraConnectionSummary(state: LibraryUiState): CameraConnectionSummary {
    val camera = state.camera
    val ble = state.ble
    val ready = state.hasSelectedNikon && ble.authenticated && ble.systemPaired && !ble.pairingInProgress &&
        ble.connectedAddress.equals(state.settings.controlAddress, true)
    return when {
        camera.connectionPhase == ConnectionPhase.DISCONNECTING -> CameraConnectionSummary("正在断开相机", "", true, false)
        camera.pairingTarget != null -> {
            val sony = camera.pairingTarget.make == CameraMake.SONY
            val brand = if (sony) "索尼" else "尼康"
            if (camera.connecting) CameraConnectionSummary("正在配对${brand}相机", if (sony || !ble.pairingInProgress) camera.stage else ble.message,
                true, false)
            else CameraConnectionSummary("${brand}蓝牙配对未完成", camera.message.orEmpty(), false, false)
        }
        camera.manuallyDisconnected && !camera.connected && !camera.connecting ->
            CameraConnectionSummary("相机已断开", "", false, false)
        camera.connecting -> CameraConnectionSummary("正在连接相机", if (ble.pairingInProgress) ble.message else camera.stage, true, ready)
        camera.connected -> CameraConnectionSummary("Wi-Fi 原片通道已就绪", camera.stage, false, ready)
        camera.connectionPhase == ConnectionPhase.FAILED -> CameraConnectionSummary(
            when { ready -> "蓝牙已连接 · Wi-Fi 未连接"; camera.stage == "连接已断开" -> "相机连接已断开"; else -> "相机连接未完成" },
            camera.message.orEmpty(), false, ready)
        !ble.adapterEnabled -> CameraConnectionSummary("手机蓝牙已关闭", "开启手机蓝牙后可连接相机；已有 Wi-Fi 连接仍可使用", false, false)
        state.settings.sonySelected -> CameraConnectionSummary(if (camera.sonyControlConnected) "索尼蓝牙已连接" else "已配对 · 相机未连接",
            "需要浏览和导入原片时，再连接相机 Wi-Fi", false, false)
        ble.pairingInProgress -> CameraConnectionSummary("正在连接蓝牙", ble.message, true, false)
        state.automatic.checking -> CameraConnectionSummary("正在检查待传照片", state.automatic.message, true, ready)
        state.automatic.transferring -> CameraConnectionSummary("蓝牙传图中", state.automatic.message, false, ready)
        ready -> CameraConnectionSummary("蓝牙已就绪", if (state.settings.autoDownload) state.automatic.message
            else "可接收便携 JPEG，或连接 Wi-Fi 浏览原片", false, true)
        state.automatic.watching && state.settings.autoKind == AutoDownloadKind.CAMERA_JPEG ->
            CameraConnectionSummary("蓝牙待机监听", state.automatic.message, false, false)
        state.settings.selectedCamera != null -> CameraConnectionSummary("已保存相机", ble.message, false, false)
        state.settings.cameras.isNotEmpty() -> CameraConnectionSummary("请选择相机", "", false, false)
        else -> CameraConnectionSummary("还未添加相机", "先配对一次，再选择蓝牙便携图或 Wi-Fi 原片", false, false)
    }
}

internal enum class CameraAction { BROWSE, WAKE_WIFI, RETRY, BLUETOOTH_SETTINGS, ADD, SELECT, MANUAL, CANCEL, NONE }

internal data class CameraPrimaryAction(val action: CameraAction, val label: String, val glyph: AppGlyph = AppGlyph.CAMERA)

/** The import card and connection page share the same action priority; no protocol or retry policy lives here. */
internal fun cameraPrimaryAction(
    state: LibraryUiState, summary: CameraConnectionSummary = cameraConnectionSummary(state)
): CameraPrimaryAction {
    val camera = state.camera
    val saved = state.settings.selectedAddress.isNotBlank()
    val failed = camera.connectionPhase == ConnectionPhase.FAILED
    return when {
        camera.connectionPhase == ConnectionPhase.DISCONNECTING -> CameraPrimaryAction(CameraAction.NONE, "正在断开")
        summary.busy -> CameraPrimaryAction(CameraAction.CANCEL, "取消连接", AppGlyph.CLOSE)
        camera.connected -> CameraPrimaryAction(CameraAction.BROWSE, "浏览照片", AppGlyph.PHOTOS)
        state.settings.selectedCamera == null && state.settings.cameras.isNotEmpty() && camera.pairingTarget == null ->
            CameraPrimaryAction(CameraAction.SELECT, "选择相机", AppGlyph.CAMERA)
        !saved && camera.manuallyDisconnected -> CameraPrimaryAction(CameraAction.MANUAL, "重新连接 Wi-Fi", AppGlyph.WIFI)
        !state.ble.adapterEnabled -> CameraPrimaryAction(CameraAction.BLUETOOTH_SETTINGS, "打开手机蓝牙", AppGlyph.BLUETOOTH)
        failed && camera.pairingTarget != null -> CameraPrimaryAction(CameraAction.RETRY, "重试配对", AppGlyph.REFRESH)
        state.hasSelectedNikon -> CameraPrimaryAction(CameraAction.WAKE_WIFI, "切换到 Wi-Fi", AppGlyph.WIFI)
        failed -> CameraPrimaryAction(CameraAction.RETRY, "重试连接", AppGlyph.REFRESH)
        saved && camera.manuallyDisconnected -> CameraPrimaryAction(CameraAction.WAKE_WIFI, "重新连接相机", AppGlyph.WIFI)
        saved -> CameraPrimaryAction(CameraAction.WAKE_WIFI, "连接相机 Wi-Fi", AppGlyph.WIFI)
        else -> CameraPrimaryAction(CameraAction.ADD, "添加相机", AppGlyph.ADD)
    }
}

internal fun performCameraAction(
    action: CameraAction, actions: LibraryActions, onBrowse: () -> Unit, onPanel: (CameraPanel) -> Unit
) {
    when (action) {
        CameraAction.BROWSE -> onBrowse()
        CameraAction.WAKE_WIFI -> actions.onWakeWifi()
        CameraAction.RETRY -> actions.onRetryConnection()
        CameraAction.BLUETOOTH_SETTINGS -> actions.onOpenBluetoothSettings()
        CameraAction.ADD -> onPanel(CameraPanel.SEARCH)
        CameraAction.SELECT -> onPanel(CameraPanel.DEVICES)
        CameraAction.MANUAL -> onPanel(CameraPanel.MANUAL)
        CameraAction.CANCEL -> actions.onDisconnectCamera()
        CameraAction.NONE -> Unit
    }
}
