package org.prooftray.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.prooftray.app.data.AppSettingsRepository

/** Runs camera actions once the runtime permissions and the phone Wi-Fi they depend on are available. */
internal interface CameraAccess {
    fun withPermissions(bluetooth: Boolean, scanning: Boolean = false, wifi: Boolean = !bluetooth, action: () -> Unit)
    fun withPhoneWifi(action: () -> Unit)
    fun openWifiPanel()
    /** Connects to the selected camera's Wi-Fi, first turning on the phone's Wi-Fi if needed. */
    fun switchToCameraWifi()
}

@Composable
internal fun rememberCameraAccess(model: LibraryViewModel): CameraAccess {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettingsRepository.get(context) }
    val phoneWifi = remember { context.applicationContext.getSystemService(WifiManager::class.java) }
    var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingWifiAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var requiredPermissions by rememberSaveable { mutableStateOf(emptyArray<String>()) }
    var pairedWifiTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var pairedWifiGeneration by rememberSaveable { mutableStateOf(-1) }

    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun runCameraAction(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            model.showError(error.message ?: "无法启动相机操作，请在前台重试", needsSettings = error is SecurityException)
        }
    }
    fun connectRequestedCamera() {
        val target = pairedWifiTarget ?: return
        pairedWifiTarget = null
        val camera = model.camera.state.value
        if (settings.state.value.selectedCameraId == target && camera.generation == pairedWifiGeneration &&
            !camera.connected && !camera.connecting) model.camera.wakeWifi()
    }
    fun forCurrentCamera(action: () -> Unit): () -> Unit {
        val target = settings.state.value.selectedCameraId
        val generation = model.camera.state.value.generation
        return {
            if (settings.state.value.selectedCameraId == target && model.camera.state.value.generation == generation) action()
            else pairedWifiTarget = null
        }
    }
    val wifiPanel = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val action = pendingWifiAction ?: if (pairedWifiTarget != null) ::connectRequestedCamera else null
        pendingWifiAction = null
        if (action != null) scope.launch {
            val enabled = withTimeoutOrNull(4000) { while (phoneWifi?.isWifiEnabled != true) delay(150); true } == true
            if (enabled) runCameraAction(action) else {
                pairedWifiTarget = null
                model.showError("手机 Wi-Fi 尚未开启，可以再次点「切换到 Wi-Fi」")
            }
        }
    }
    fun showWifiPanel() {
        try { wifiPanel.launch(Intent(Settings.Panel.ACTION_WIFI)) }
        catch (_: ActivityNotFoundException) {
            try { wifiPanel.launch(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            catch (_: ActivityNotFoundException) {
                pendingWifiAction = null
                pairedWifiTarget = null
                model.showError("手机未提供系统 Wi-Fi 面板")
            }
        }
    }
    fun ensurePhoneWifi(action: () -> Unit) {
        if (phoneWifi?.isWifiEnabled == true) action()
        else if (pendingWifiAction == null) { pendingWifiAction = forCurrentCamera(action); showWifiPanel() }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pendingCameraAction
        pendingCameraAction = null
        if (requiredPermissions.all(::granted)) {
            if (action != null) runCameraAction(action)
            else if (pairedWifiTarget != null) ensurePhoneWifi(::connectRequestedCamera)
        } else {
            pairedWifiTarget = null
            model.showError("连接功能需要附近设备权限；Android 12 及以下的 Wi‑Fi 连接还需要定位权限。", needsSettings = true)
        }
    }
    fun requestPermissions(bluetooth: Boolean, scanning: Boolean, wifi: Boolean, action: () -> Unit) {
        if (pendingCameraAction != null) return
        val required = buildList {
            if (bluetooth && Build.VERSION.SDK_INT >= 31) {
                if (scanning) add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (wifi && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            if ((wifi && Build.VERSION.SDK_INT <= 32) || (bluetooth && scanning && Build.VERSION.SDK_INT <= 30)) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (required.all(::granted)) {
            runCameraAction(action)
            return
        }
        requiredPermissions = required.toTypedArray()
        pendingCameraAction = forCurrentCamera(action)
        val notifications = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        permissions.launch((required + notifications).toTypedArray())
    }

    return object : CameraAccess {
        override fun withPermissions(bluetooth: Boolean, scanning: Boolean, wifi: Boolean, action: () -> Unit) =
            requestPermissions(bluetooth, scanning, wifi, action)
        override fun withPhoneWifi(action: () -> Unit) = ensurePhoneWifi(action)
        override fun openWifiPanel() = showWifiPanel()
        override fun switchToCameraWifi() {
            val target = settings.state.value.selectedCameraId ?: return
            if (pairedWifiTarget != null || pendingCameraAction != null || pendingWifiAction != null) return
            pairedWifiTarget = target
            pairedWifiGeneration = model.camera.state.value.generation
            requestPermissions(bluetooth = true, scanning = true, wifi = true) { ensurePhoneWifi(::connectRequestedCamera) }
        }
    }
}
