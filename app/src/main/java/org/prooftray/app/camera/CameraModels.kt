package org.prooftray.app.camera

import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.domain.DownloadVariant

enum class CameraMode { AP, STA }
enum class CameraMake { NIKON, SONY, OTHER }

internal fun ptpCameraMake(manufacturer: String, vendor: Int): CameraMake = when {
    manufacturer.trim().startsWith("Sony", true) -> CameraMake.SONY
    manufacturer.trim().startsWith("Nikon", true) -> CameraMake.NIKON
    vendor == 17 -> CameraMake.SONY
    vendor == 10 -> CameraMake.NIKON
    else -> CameraMake.OTHER
}

enum class ConnectionPhase { IDLE, BLUETOOTH, CAMERA_WIFI, PHONE_WIFI, PTP, READY, DISCONNECTING, FAILED }
data class CameraConnection(
    val mode: CameraMode = CameraMode.AP, val host: String = "", val port: Int = 15740, val ssid: String = "", val password: String = "",
    val security: CameraWifiSecurity = if (password.isEmpty()) CameraWifiSecurity.OPEN else CameraWifiSecurity.WPA2,
    val sonyAccess: SonyAccess? = null, val sonyCamera: Boolean = false, val bssid: String? = null
) {
    override fun toString(): String = "CameraConnection(mode=$mode, port=$port, security=$security)"
}
data class CameraState(
    val connected: Boolean = false, val busy: Boolean = false, val cameraName: String = "",
    val stage: String = "相机未连接", val indexed: Int = 0, val total: Int = 0,
    val message: String? = null, val tasks: List<CameraDownload> = emptyList(), val paused: Boolean = false,
    val pairingCode: String? = null, val sourceId: String = "", val generation: Int = 0,
    val catalogBatchSize: Int? = CatalogBatchSize.DEFAULT, val hasMore: Boolean = false, val catalogIds: Set<String> = emptySet(),
    val catalogStopped: Boolean = false, val catalogError: String? = null, val catalogReadId: Long = 0,
    val catalogDates: List<SonyCaptureDay> = emptyList(), val catalogStartDay: SonyCaptureDay? = null, val transmissionPaused: Boolean = false,
    val connecting: Boolean = false, val connectionPhase: ConnectionPhase = ConnectionPhase.IDLE, val bluetoothAssisted: Boolean = false,
    val make: CameraMake = CameraMake.OTHER, val protocolDiagnostic: String? = null, val manuallyDisconnected: Boolean = false,
    val sonyControlConnected: Boolean = false, val pairingTarget: BleCamera? = null, val activeCameraId: String? = null
) {
    val canConnect: Boolean get() = !connecting
    val readingCatalog: Boolean get() = connected && !connecting && busy
    val canAutoLoadCatalog: Boolean get() = connected && !connecting && !busy && hasMore && !catalogStopped && catalogError == null
}
data class RemotePhoto(val asset: PhotoAsset, val handle: Long, val storage: Long, val cameraIdentity: String, val captured: String)
enum class DownloadStatus { QUEUED, RUNNING, WAITING, COMPLETED, CANCELLED }
enum class DownloadChannel { WIFI, BLUETOOTH }
data class CameraDownload(
    val id: String, val photo: RemotePhoto, val status: DownloadStatus = DownloadStatus.QUEUED,
    val received: Long = 0, val outputUri: String? = null, val message: String = "等待导入",
    val variant: DownloadVariant = DownloadVariant.ORIGINAL, val automatic: Boolean = false, val payloadBytes: Long = 0,
    val channel: DownloadChannel = DownloadChannel.WIFI
) {
    val expectedBytes: Long get() = if (variant == DownloadVariant.ORIGINAL) photo.asset.sizeBytes else payloadBytes
    val pending: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING || status == DownloadStatus.WAITING
    val canClearHistory: Boolean get() = status == DownloadStatus.COMPLETED ||
        (status in setOf(DownloadStatus.WAITING, DownloadStatus.CANCELLED) && outputUri == null)
}

data class AutoDownloadState(
    val watching: Boolean = false, val message: String = "自动导入未开启", val lastCheckMillis: Long = 0,
    val checking: Boolean = false, val lastCheckResult: String = "", val transferring: Boolean = false
)
