package org.prooftray.app.domain

enum class PowerProfile(val pollSeconds: Int, val locationSeconds: Int) { SAVER(60, 120), BALANCED(30, 60), RESPONSIVE(10, 30) }
enum class BluetoothRole { AUTOMATIC, CONNECT_TO_CAMERA, WAIT_FOR_CAMERA }

data class AppSettings(
    val manualVariant: DownloadVariant = DownloadVariant.ORIGINAL,
    val autoDownload: Boolean = false, val autoKind: AutoDownloadKind = AutoDownloadKind.CAMERA_JPEG,
    val powerProfile: PowerProfile = PowerProfile.BALANCED, val locationSync: Boolean = false, val pairingTimeSync: Boolean = false,
    val requestedTimeSync: Boolean = true,
    val controlAddress: String = "", val transferAddress: String = "", val bluetoothRole: BluetoothRole = BluetoothRole.AUTOMATIC,
    val controlName: String = "", val sonySelected: Boolean = false, val sonyAddress: String = "", val sonyName: String = "",
    val exportPreferences: ExportPreferences = ExportPreferences(), val controlSerial: String = "",
    val cameras: SavedCameras = SavedCameras()
) {
    val selectedAddress: String get() = if (sonySelected) sonyAddress else controlAddress
    val selectedName: String get() = if (sonySelected) sonyName else controlName
    val effectiveManualVariant: DownloadVariant get() = if (sonySelected) DownloadVariant.ORIGINAL else manualVariant
    val automaticEnabled: Boolean get() = autoDownload && (!sonySelected || autoKind != AutoDownloadKind.CAMERA_JPEG)
}
