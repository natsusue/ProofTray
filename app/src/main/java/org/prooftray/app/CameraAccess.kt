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
import androidx.compose.runtime.LaunchedEffect
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
import org.prooftray.app.camera.SavedCameraChannel
import org.prooftray.app.camera.CameraChangeContext
import org.prooftray.app.data.AppSettingsRepository
import org.prooftray.app.domain.CameraBrand

/** Runs camera actions once the runtime permissions and the phone Wi-Fi they depend on are available. */
internal interface CameraAccess {
    fun withPermissions(bluetooth: Boolean, scanning: Boolean = false, wifi: Boolean = !bluetooth, action: () -> Unit)
    fun withPhoneWifi(action: () -> Unit)
    fun openWifiPanel()
    /** Connects to the selected camera's Wi-Fi, first turning on the phone's Wi-Fi if needed. */
    fun switchToCameraWifi()
    fun connectSavedCamera(id: String, approval: CameraChangeContext?, channel: SavedCameraChannel)
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
    var savedRequestToken by rememberSaveable { mutableStateOf<String?>(null) }
    var returnedWifiToken by rememberSaveable { mutableStateOf<String?>(null) }

    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun runCameraAction(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            model.showError(error.message ?: "无法启动相机操作，请在前台重试", needsSettings = error is SecurityException)
        }
    }
    fun finishRequest(error: String? = null) {
        val token = savedRequestToken ?: return
        savedRequestToken = null
        model.camera.finishCameraAccess(token, error)
    }
    fun connectRequestedCamera() = finishRequest()
    suspend fun awaitPhoneWifi() = withTimeoutOrNull(4000) {
        while (phoneWifi?.isWifiEnabled != true) delay(150)
        true
    } == true

    LaunchedEffect(returnedWifiToken) {
        val token = returnedWifiToken ?: return@LaunchedEffect
        val enabled = awaitPhoneWifi()
        if (savedRequestToken == token) finishRequest(if (enabled) null else "手机 Wi-Fi 尚未开启")
        returnedWifiToken = null
    }
    val wifiPanel = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val action = pendingWifiAction
        pendingWifiAction = null
        if (savedRequestToken != null && model.camera.state.value.accessRequest?.channel == SavedCameraChannel.WIFI)
            returnedWifiToken = savedRequestToken
        else if (action != null) scope.launch {
            if (awaitPhoneWifi()) runCameraAction(action) else model.showError("手机 Wi-Fi 尚未开启，可以再次点「切换到 Wi-Fi」")
        }
    }
    fun showWifiPanel() {
        try { wifiPanel.launch(Intent(Settings.Panel.ACTION_WIFI)) }
        catch (_: ActivityNotFoundException) {
            try { wifiPanel.launch(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            catch (_: ActivityNotFoundException) {
                pendingWifiAction = null
                finishRequest("手机未提供系统 Wi-Fi 面板")
                model.showError("手机未提供系统 Wi-Fi 面板")
            }
        }
    }
    fun ensurePhoneWifi(action: () -> Unit) {
        if (phoneWifi?.isWifiEnabled == true) action()
        else if (pendingWifiAction == null) { pendingWifiAction = action; showWifiPanel() }
    }
    fun continueSavedRequest() {
        val request = model.camera.state.value.accessRequest
        if (request == null || request.token != savedRequestToken) { savedRequestToken = null; return }
        if (request.channel == SavedCameraChannel.BLUETOOTH) finishRequest() else ensurePhoneWifi(::connectRequestedCamera)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pendingCameraAction
        pendingCameraAction = null
        if (requiredPermissions.all(::granted)) {
            if (action != null) runCameraAction(action)
            else if (savedRequestToken != null) continueSavedRequest()
        } else {
            val bluetoothOnly = model.camera.state.value.accessRequest?.channel == SavedCameraChannel.BLUETOOTH
            finishRequest("未获得相机连接权限")
            model.showError(if (bluetoothOnly) "请允许蓝牙连接所需的权限" else
                "连接功能需要附近设备权限；Android 12 及以下的 Wi‑Fi 连接还需要定位权限。", needsSettings = true)
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
        pendingCameraAction = action
        val notifications = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        permissions.launch((required + notifications).toTypedArray())
    }

    return object : CameraAccess {
        private fun guarded(action: () -> Unit): () -> Unit {
            val generation = model.camera.state.value.generation
            val selected = settings.state.value.cameras.selectedId
            return {
                if (generation == model.camera.state.value.generation && selected == settings.state.value.cameras.selectedId &&
                    model.camera.state.value.accessRequest == null && model.camera.state.value.controlAttempt == null) action()
            }
        }

        override fun withPermissions(bluetooth: Boolean, scanning: Boolean, wifi: Boolean, action: () -> Unit) {
            if (savedRequestToken == null) requestPermissions(bluetooth, scanning, wifi, guarded(action))
        }
        override fun withPhoneWifi(action: () -> Unit) = ensurePhoneWifi(guarded(action))
        override fun openWifiPanel() = showWifiPanel()
        override fun switchToCameraWifi() {
            settings.state.value.cameras.selectedId?.let { connectSavedCamera(it, null, SavedCameraChannel.WIFI) }
        }
        override fun connectSavedCamera(id: String, approval: CameraChangeContext?, channel: SavedCameraChannel) {
            if (savedRequestToken != null || pendingCameraAction != null || pendingWifiAction != null) return
            runCameraAction {
                savedRequestToken = model.camera.requestSavedCamera(id, approval, channel) ?: return@runCameraAction
                try {
                    val wifi = channel == SavedCameraChannel.WIFI
                    val nikon = settings.state.value.cameras.devices.any { it.id == id && it.brand == CameraBrand.NIKON }
                    requestPermissions(bluetooth = true, scanning = wifi || nikon, wifi = wifi, action = ::continueSavedRequest)
                } catch (error: Exception) {
                    finishRequest(error.message ?: "无法申请连接权限")
                    throw error
                }
            }
        }
    }
}
