package org.prooftray.app.domain

import java.util.UUID
import org.prooftray.app.camera.CameraMake

enum class PowerProfile(val pollSeconds: Int, val locationSeconds: Int) { SAVER(60, 120), BALANCED(30, 60), RESPONSIVE(10, 30) }
enum class BluetoothRole { AUTOMATIC, CONNECT_TO_CAMERA, WAIT_FOR_CAMERA }

data class SavedCamera(
    val id: String = UUID.randomUUID().toString(), val make: CameraMake, val name: String, val controlAddress: String,
    val transferAddress: String = "", val serial: String = "", val alias: String = "", val lastConnectedAt: Long? = null,
    val wifiSourceId: String = ""
) {
    /** An address set manually for a single Nikon channel still needs the first pairing exchange. */
    val hasScanRegistration: Boolean get() = controlAddress.isNotBlank() &&
        (make == CameraMake.SONY || (make == CameraMake.NIKON && transferAddress.isNotBlank()))

    val displayName: String get() = alias.ifBlank { name }.ifBlank {
        when (make) {
            CameraMake.NIKON -> "尼康相机"
            CameraMake.SONY -> "索尼相机"
            CameraMake.OTHER -> "相机"
        }
    }
}

data class AppSettings(
    val manualVariant: DownloadVariant = DownloadVariant.ORIGINAL,
    val autoDownload: Boolean = false, val autoKind: AutoDownloadKind = AutoDownloadKind.CAMERA_JPEG,
    val powerProfile: PowerProfile = PowerProfile.BALANCED, val locationSync: Boolean = false, val pairingTimeSync: Boolean = false,
    val requestedTimeSync: Boolean = true,
    val bluetoothRole: BluetoothRole = BluetoothRole.AUTOMATIC, val exportPreferences: ExportPreferences = ExportPreferences(),
    val cameras: List<SavedCamera> = emptyList(), val selectedCameraId: String? = null
) {
    val selectedCamera: SavedCamera? get() = cameras.firstOrNull { it.id == selectedCameraId }
    private val nikon: SavedCamera? get() = selectedCamera?.takeIf { it.make == CameraMake.NIKON }
    private val sony: SavedCamera? get() = selectedCamera?.takeIf { it.make == CameraMake.SONY }
    val controlAddress: String get() = nikon?.controlAddress.orEmpty()
    val controlName: String get() = nikon?.name.orEmpty()
    val transferAddress: String get() = nikon?.transferAddress.orEmpty()
    val controlSerial: String get() = nikon?.serial.orEmpty()
    val sonySelected: Boolean get() = sony != null
    val sonyAddress: String get() = sony?.controlAddress.orEmpty()
    val selectedAddress: String get() = selectedCamera?.controlAddress.orEmpty()
    val selectedName: String get() = selectedCamera?.displayName.orEmpty()
    val effectiveManualVariant: DownloadVariant get() = if (sonySelected) DownloadVariant.ORIGINAL else manualVariant
    val automaticEnabled: Boolean get() = autoDownload && (!sonySelected || autoKind != AutoDownloadKind.CAMERA_JPEG)

    fun withCamera(camera: SavedCamera): AppSettings {
        val updated = if (cameras.any { it.id == camera.id }) cameras.map { if (it.id == camera.id) camera else it } else cameras + camera
        return copy(cameras = updated, selectedCameraId = camera.id)
    }
}
