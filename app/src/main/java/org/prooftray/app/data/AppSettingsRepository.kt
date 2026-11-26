package org.prooftray.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.prooftray.app.domain.AppSettings
import org.prooftray.app.domain.SavedCameras
import org.prooftray.app.domain.PowerProfile
import org.prooftray.app.domain.BluetoothRole
import org.prooftray.app.domain.AutoDownloadKind
import org.prooftray.app.domain.DownloadVariant
import org.prooftray.app.domain.ExportPreferences

class AppSettingsRepository internal constructor(private val preferences: SharedPreferences) {
    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val previous = mutableState.value
        val changed = change(previous)
        val value = changed.cameras.project(changed)
        preferences.edit().putString("manualVariant", value.manualVariant.name).putBoolean("autoDownload", value.autoDownload)
            .putString("autoKind", value.autoKind.name).putString("powerProfile", value.powerProfile.name)
            .putBoolean("locationSync", value.locationSync).putBoolean("pairingTimeSync", value.pairingTimeSync)
            .putBoolean("requestedTimeSync", value.requestedTimeSync)
            .putString("bluetoothRole", value.bluetoothRole.name).also { editor ->
                if (value.cameras != previous.cameras) editor.writeCameras(value)
                if (value.exportPreferences != mutableState.value.exportPreferences) value.exportPreferences.toPreferences().forEach { (key, item) ->
                    when (item) {
                        is Int -> editor.putInt(key, item)
                        is String -> editor.putString(key, item)
                    }
                }
            }.apply()
        mutableState.value = value
    }

    private fun SharedPreferences.Editor.writeCameras(value: AppSettings) {
        putString(SavedCameraCodec.KEY, SavedCameraCodec.encode(value.cameras))
        putString("controlAddress", value.controlAddress).putString("controlName", value.controlName)
            .putString("controlSerial", value.controlSerial).putString("transferAddress", value.transferAddress)
            .putBoolean("sonySelected", value.sonySelected).putString("sonyAddress", value.sonyAddress)
            .putString("sonyName", value.sonyName)
    }

    fun changeCameras(change: (SavedCameras) -> SavedCameras) = update {
        check(!it.cameras.readError) { "设备列表读取失败，已保留原记录" }
        it.copy(cameras = change(it.cameras))
    }

    private fun read(): AppSettings {
        val settings = readSettings()
        val cameras = if (preferences.contains(SavedCameraCodec.KEY)) SavedCameraCodec.decode(preferences.all[SavedCameraCodec.KEY])
        else {
            val old = runCatching { readLegacyCameras(settings) }.getOrNull()
                ?: return SavedCameras(readError = true).project(settings)
            SavedCameraCodec.migrate(old).also {
                preferences.edit().also { editor -> editor.writeCameras(it.project(settings)) }.apply()
            }
        }
        return cameras.project(settings)
    }

    private fun readSettings() = AppSettings(
        manualVariant = enumValue("manualVariant", DownloadVariant.ORIGINAL),
        autoDownload = preferences.getBoolean("autoDownload", false),
        autoKind = enumValue("autoKind", AutoDownloadKind.CAMERA_JPEG), powerProfile = enumValue("powerProfile", PowerProfile.BALANCED),
        locationSync = preferences.getBoolean("locationSync", false), pairingTimeSync = preferences.getBoolean("pairingTimeSync", false),
        requestedTimeSync = preferences.getBoolean("requestedTimeSync", true),
        bluetoothRole = enumValue("bluetoothRole", BluetoothRole.AUTOMATIC),
        exportPreferences = ExportPreferences.fromPreferences(preferences.all)
    )

    private fun readLegacyCameras(settings: AppSettings) = settings.copy(
        controlAddress = preferences.getString("controlAddress", "").orEmpty(), controlSerial = preferences.getString("controlSerial", "").orEmpty(),
        transferAddress = preferences.getString("transferAddress", "").orEmpty(),
        controlName = preferences.getString("controlName", "").orEmpty(),
        sonySelected = preferences.getBoolean("sonySelected", false), sonyAddress = preferences.getString("sonyAddress", "").orEmpty(),
        sonyName = preferences.getString("sonyName", "").orEmpty()
    )

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(preferences.getString(key, fallback.name)!!) }.getOrDefault(fallback)

    companion object {
        @Volatile private var instance: AppSettingsRepository? = null
        fun get(context: Context): AppSettingsRepository = instance ?: synchronized(this) {
            instance ?: AppSettingsRepository(context.applicationContext.getSharedPreferences("app-settings", Context.MODE_PRIVATE))
                .also { instance = it }
        }
    }
}
