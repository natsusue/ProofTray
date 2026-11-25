package org.prooftray.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.prooftray.app.camera.CameraMake
import org.prooftray.app.camera.cameraSerial
import org.prooftray.app.camera.cameraSerialMatches
import org.prooftray.app.domain.AppSettings
import org.prooftray.app.domain.PowerProfile
import org.prooftray.app.domain.BluetoothRole
import org.prooftray.app.domain.AutoDownloadKind
import org.prooftray.app.domain.DownloadVariant
import org.prooftray.app.domain.ExportPreferences

class AppSettingsRepository private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val previous = mutableState.value
        val value = change(previous)
        if (value == previous) return
        preferences.edit().putString("manualVariant", value.manualVariant.name).putBoolean("autoDownload", value.autoDownload)
            .putString("autoKind", value.autoKind.name).putString("powerProfile", value.powerProfile.name)
            .putBoolean("locationSync", value.locationSync).putBoolean("pairingTimeSync", value.pairingTimeSync)
            .putBoolean("requestedTimeSync", value.requestedTimeSync)
            .putString("bluetoothRole", value.bluetoothRole.name).also { editor ->
                if (previous.cameras != value.cameras) editor.putString("savedCameras", encodeCameras(value.cameras))
                if (previous.selectedCameraId != value.selectedCameraId) editor.putString("selectedCameraId", value.selectedCameraId)
                if (value.exportPreferences != previous.exportPreferences) value.exportPreferences.toPreferences().forEach { (key, item) ->
                    when (item) {
                        is Int -> editor.putInt(key, item)
                        is String -> editor.putString(key, item)
                    }
                }
            }.apply()
        mutableState.value = value
    }

    fun selectCamera(id: String) = update { value ->
        check(value.cameras.any { it.id == id }) { "所选相机已移除" }
        value.copy(selectedCameraId = id)
    }

    fun removeCamera(id: String) = update { value ->
        value.copy(cameras = value.cameras.filterNot { it.id == id }, selectedCameraId = value.selectedCameraId?.takeUnless { it == id })
    }

    fun renameCamera(id: String, alias: String) = update { value ->
        value.copy(cameras = value.cameras.map { if (it.id == id) it.copy(alias = alias.trim()) else it })
    }

    fun markConnected(id: String, serial: String?, sourceId: String) = update { value ->
        val time = System.currentTimeMillis()
        value.copy(cameras = value.cameras.map { camera ->
            if (camera.id != id) return@map camera
            check(cameraSerialMatches(cameraSerial(camera.serial), serial)) { "Wi-Fi 连接的机身与所选相机不符，请重新选择相机" }
            val source = if (sourceId.startsWith("session:")) camera.wifiSourceId else sourceId
            camera.copy(lastConnectedAt = time, serial = serial ?: camera.serial, wifiSourceId = source)
        })
    }

    fun savePairedCamera(make: CameraMake, name: String, controlAddress: String, transferAddress: String = "",
        serial: String = "", knownId: String? = null) = update { value ->
        value.withPairedCamera(make, name, controlAddress, transferAddress, serial, knownId, System.currentTimeMillis())
    }

    private fun read(): AppSettings {
        val settings = AppSettings(
            manualVariant = enumValue("manualVariant", DownloadVariant.ORIGINAL), autoDownload = preferences.getBoolean("autoDownload", false),
            autoKind = enumValue("autoKind", AutoDownloadKind.CAMERA_JPEG), powerProfile = enumValue("powerProfile", PowerProfile.BALANCED),
            locationSync = preferences.getBoolean("locationSync", false), pairingTimeSync = preferences.getBoolean("pairingTimeSync", false),
            requestedTimeSync = preferences.getBoolean("requestedTimeSync", true),
            bluetoothRole = enumValue("bluetoothRole", BluetoothRole.AUTOMATIC),
            exportPreferences = ExportPreferences.fromPreferences(preferences.all))
        val saved = preferences.getString("savedCameras", null)
        if (saved != null) return settings.copy(cameras = decodeCameras(saved), selectedCameraId = preferences.getString("selectedCameraId", null))
        val values = preferences.all
        if (legacyCameraKeys.none(values::containsKey)) return settings
        val migrated = settings.migrateLegacyCameras(values)
        val editor = preferences.edit().putString("savedCameras", encodeCameras(migrated.cameras))
            .putString("selectedCameraId", migrated.selectedCameraId)
        legacyCameraKeys.forEach(editor::remove)
        editor.apply()
        return migrated
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(preferences.getString(key, fallback.name)!!) }.getOrDefault(fallback)

    companion object {
        @Volatile private var instance: AppSettingsRepository? = null
        fun get(context: Context): AppSettingsRepository = instance ?: synchronized(this) {
            instance ?: AppSettingsRepository(context.applicationContext).also { instance = it }
        }
    }
}
