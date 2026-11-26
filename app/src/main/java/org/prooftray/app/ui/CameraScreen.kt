package org.prooftray.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.prooftray.app.camera.CameraConnection
import org.prooftray.app.camera.BleCamera
import org.prooftray.app.camera.CameraMake
import org.prooftray.app.camera.CameraMode
import org.prooftray.app.camera.ConnectionPhase
import org.prooftray.app.camera.SonyAccess
import org.prooftray.app.domain.CameraBrand
import org.prooftray.app.R

internal enum class CameraPanel { SEARCH, MANUAL, DIAGNOSTICS }

@Composable
internal fun CameraScreen(
    state: LibraryUiState, actions: LibraryActions, onBrowse: () -> Unit, onRegister: () -> Unit,
    modifier: Modifier = Modifier, initialPanel: CameraPanel? = null, recoveryId: String? = null
) {
    var panel by rememberSaveable { mutableStateOf(initialPanel) }
    var manualMode by rememberSaveable { mutableStateOf(CameraMode.AP) }
    var menu by remember { mutableStateOf(false) }
    val connection = state.cameraChangeContext()
    val recovery = state.settings.cameras.devices.find { it.id == recoveryId && it.pending }
    var pairingChoice by remember(connection, recovery) { mutableStateOf<BleCamera?>(null) }
    var switchToBluetooth by remember(connection) { mutableStateOf(false) }
    fun openPanel(next: CameraPanel?) {
        menu = false
        if (panel == CameraPanel.SEARCH && next != panel) actions.onStopBleScan()
        panel = next
        if (next == CameraPanel.SEARCH && !state.ble.scanning) actions.onScanBle()
    }
    BackHandler(enabled = panel != null) { openPanel(null) }
    val summary = cameraConnectionSummary(state)
    val saved = state.settings.selectedAddress.isNotBlank()
    val nikon = state.hasSelectedNikon
    val pairing = state.camera.pairingTarget
    val showNikonDiagnostics = if (pairing != null) pairing.make == CameraMake.NIKON else !state.settings.sonySelected
    LaunchedEffect(initialPanel) {
        if (initialPanel == CameraPanel.SEARCH && !state.ble.scanning) actions.onScanBle()
    }
    val primary = cameraPrimaryAction(state, summary)
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(state.connectionCameraName.ifBlank { "我的相机" }, style = MaterialTheme.typography.headlineSmall)
                        Text(summary.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { AppIcon(AppGlyph.MORE, "连接选项") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val changeReason = when {
                                state.camera.connected -> "先断开当前相机"
                                summary.busy -> "等待当前连接操作完成"
                                !state.ble.adapterEnabled -> "先打开手机蓝牙"
                                state.automatic.transferring -> "先停止蓝牙接收"
                                else -> null
                            }
                            ConnectionMenuItem("添加或切换相机", changeReason) { openPanel(CameraPanel.SEARCH) }
                            ConnectionMenuItem("手动连接相机热点", "等待当前连接操作完成".takeIf { summary.busy }) {
                                manualMode = CameraMode.AP
                                openPanel(CameraPanel.MANUAL)
                            }
                            DropdownMenuItem(text = { Text("连接诊断") }, onClick = { openPanel(CameraPanel.DIAGNOSTICS) })
                        }
                    }
                }
                val failed = state.camera.connectionPhase == ConnectionPhase.FAILED && !summary.busy && !state.camera.connected
                if (summary.busy || state.automatic.transferring || (saved && !summary.bluetoothReady && !state.camera.connected)) {
                    if (summary.detail.isNotBlank() && summary.detail != state.camera.message) {
                        Text(summary.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (failed) {
                    ErrorNotice(state.camera.message ?: summary.detail.ifBlank { "Wi-Fi 连接未完成" }) {
                        if (nikon && !state.camera.bluetoothAssisted) TextButton(onClick = actions.onRetryConnection) { Text("重试上次连接") }
                        TextButton(onClick = { openPanel(CameraPanel.DIAGNOSTICS) }) { Text("诊断") }
                    }
                } else state.camera.message?.let { ErrorNotice(it) }
                if (summary.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (primary.action != CameraAction.NONE && (primary.action != CameraAction.ADD || panel != CameraPanel.SEARCH)) {
                    PrimaryAction(primary.label, {
                        performCameraAction(primary.action, actions, onBrowse, ::openPanel)
                    }, enabled = primary.action != CameraAction.NONE, modifier = Modifier.fillMaxWidth(), glyph = primary.glyph)
                }
                val lanOpen = panel == CameraPanel.MANUAL && manualMode == CameraMode.STA
                OutlinedButton(onClick = {
                    if (lanOpen) openPanel(null) else {
                        manualMode = CameraMode.STA
                        openPanel(CameraPanel.MANUAL)
                        actions.onDiscoverCamera()
                    }
                }, enabled = !summary.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (lanOpen) "收起局域网连接" else "通过同一局域网连接")
                }
                ManualConnection(state, actions, visible = panel == CameraPanel.MANUAL, mode = manualMode,
                    onModeChange = { manualMode = it }, onRegister = onRegister)
                if (nikon) {
                    val wifi = state.camera.connected
                    val bluetooth = state.ble.authenticated && state.ble.systemPaired && !state.ble.pairingInProgress
                    val reconnectBle = failed && bluetooth
                    val canSwitchBluetooth = wifi || (!state.automatic.transferring && (!bluetooth || reconnectBle))
                    val label = when {
                        wifi -> "切换到蓝牙"
                        !state.ble.adapterEnabled -> "打开手机蓝牙"
                        state.ble.pairingInProgress -> "正在连接蓝牙"
                        state.automatic.transferring -> "蓝牙传图中"
                        reconnectBle -> "重新连接蓝牙"
                        bluetooth -> "蓝牙已连接"
                        else -> "连接蓝牙"
                    }
                    val statusOnly = !wifi && state.ble.adapterEnabled &&
                        (state.ble.pairingInProgress || state.automatic.transferring || (bluetooth && !reconnectBle))
                    if (statusOnly) Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppIcon(AppGlyph.BLUETOOTH)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val enabled = !state.camera.connecting && !state.ble.pairingInProgress &&
                            (!state.ble.adapterEnabled || canSwitchBluetooth)
                        OutlinedButton(onClick = {
                            val target = state.settings.cameras.selected
                            when {
                                !state.ble.adapterEnabled -> actions.onOpenBluetoothSettings()
                                target != null && connection.switchesDevice(target.id, CameraMake.NIKON, target.address) ->
                                    switchToBluetooth = true
                                else -> actions.onSwitchToBluetooth(null)
                            }
                        },
                            enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
                        if (!enabled) Text("等待当前连接操作完成", style = MaterialTheme.typography.bodySmall)
                    }
                }
                val canDisconnect = state.camera.connected || state.camera.sonyControlConnected ||
                    state.ble.authenticated || state.automatic.transferring
                if (!summary.busy && canDisconnect) {
                    OutlinedButton(onClick = actions.onDisconnectCamera, modifier = Modifier.fillMaxWidth()) { Text("断开相机") }
                }
                if (nikon && !summary.busy && state.ble.adapterEnabled && !state.camera.connected) {
                    if (state.automatic.transferring) TextButton(onClick = actions.onDisconnectControl) { Text("停止蓝牙接收") }
                    else if (summary.bluetoothReady) TextButton(onClick = actions.onCheckBluetoothTransfers) { Text("接收待传 JPEG") }
                }
            }
        }
        item {
            AnimatedVisibility(panel == CameraPanel.SEARCH) {
                SectionCard("添加 / 切换相机", "在机身的智能手机连接菜单中开始配对。", AppGlyph.BLUETOOTH) {
                    if (state.ble.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.ble.message, style = MaterialTheme.typography.bodySmall)
                    if (!state.ble.scanning && state.ble.cameras.isEmpty()) Text("未发现相机")
                    state.ble.cameras.filter {
                        val brand = if (it.make == CameraMake.SONY) CameraBrand.SONY else CameraBrand.NIKON
                        recoveryId == null || recovery?.brand == brand
                    }.forEach { camera ->
                        key(camera.address) {
                            OutlinedButton(onClick = {
                                val brand = if (camera.make == CameraMake.SONY) CameraBrand.SONY else CameraBrand.NIKON
                                val id = state.settings.cameras.devices.find {
                                    it.brand == brand && it.address.equals(camera.address, true)
                                }?.id
                                if (recoveryId != null || connection.switchesDevice(id, camera.make, camera.address)) pairingChoice = camera
                                else {
                                    panel = null
                                    actions.onConnectBle(camera, null, null)
                                }
                            },
                                enabled = camera.pairingAvailable && !summary.busy && !state.camera.connected, modifier = Modifier.fillMaxWidth()) {
                                Text(if (camera.pairingAvailable) camera.name else "${camera.name} · 请在机身打开「智能手机连接」")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = actions.onScanBle, enabled = !state.ble.scanning && !summary.busy && !state.camera.connected) {
                            Text("重新搜索")
                        }
                        TextButton(onClick = { openPanel(null) }) { Text("停止搜索") }
                    }
                    Text("手机和机身出现配对码时，请核对后确认。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            AnimatedVisibility(panel == CameraPanel.DIAGNOSTICS) {
                SectionCard("连接诊断", glyph = AppGlyph.SETTINGS) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (pairing == null) Text(state.camera.stage)
                            else if (!showNikonDiagnostics) Text("蓝牙配对 · ${state.camera.stage}")
                            state.camera.protocolDiagnostic?.let { Text(it) }
                            if (pairing == null) {
                                state.camera.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                TextButton(onClick = actions.onOpenWifi) { Text("系统 Wi-Fi 面板") }
                            }
                            if (showNikonDiagnostics) {
                                Text("蓝牙：${state.ble.message}")
                                state.ble.history.forEachIndexed { index, message ->
                                    Text("${index + 1}. $message", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showNikonDiagnostics && pairing == null) {
                            TextButton(onClick = actions.onDiscoverClassic, enabled = saved && !summary.busy && !state.camera.connected) {
                                Text("重新检查配对")
                            }
                        }
                        TextButton(onClick = actions.onOpenBluetoothSettings) { Text("系统蓝牙设置") }
                    }
                    if (state.camera.connected && state.camera.make == CameraMake.NIKON) {
                        TextButton(onClick = actions.onDisconnectWifi) { Text("仅断开 Wi-Fi") }
                    }
                }
            }
        }
        if (panel == CameraPanel.MANUAL || panel == CameraPanel.DIAGNOSTICS) item {
            TextButton(onClick = { openPanel(null) }) { Text("收起") }
        }
    }
    pairingChoice?.let { target ->
        val message = if (recovery != null) {
            val id = state.settings.cameras.devices.find { it.address.equals(target.address, true) }?.id
            stringResource(if (connection.switchesDevice(id, target.make, target.address))
                R.string.camera_switch_rebind else R.string.camera_rebind, target.name, recovery.title)
        } else stringResource(R.string.camera_switch_detail, target.name)
        CameraChangeDialog(message, { pairingChoice = null }) {
            pairingChoice = null
            panel = null
            actions.onConnectBle(target, connection, recoveryId)
        }
    }
    if (switchToBluetooth) {
        val message = stringResource(R.string.camera_switch_detail, state.settings.selectedName)
        CameraChangeDialog(message, { switchToBluetooth = false }) {
            switchToBluetooth = false
            actions.onSwitchToBluetooth(connection)
        }
    }
}

@Composable
internal fun CameraChangeDialog(message: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, text = { Text(message) },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(R.string.camera_confirm_change)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.camera_cancel)) } })
}

@Composable
private fun ConnectionMenuItem(title: String, disabledReason: String?, onClick: () -> Unit) {
    DropdownMenuItem(enabled = disabledReason == null, onClick = onClick, text = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title)
            if (disabledReason != null) Text(disabledReason, style = MaterialTheme.typography.bodySmall)
        }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualConnection(state: LibraryUiState, actions: LibraryActions, visible: Boolean, mode: CameraMode,
    onModeChange: (CameraMode) -> Unit, onRegister: () -> Unit) {
    var apHost by rememberSaveable { mutableStateOf("") }
    var staHost by rememberSaveable { mutableStateOf("") }
    var discoveredSonyHost by rememberSaveable { mutableStateOf("") }
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("15740") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var manualAuth by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    val access = remember(manualAuth, user, secret, fingerprint) {
        if (manualAuth) runCatching { SonyAccess(user.trim(), secret, fingerprint) }.getOrNull() else null
    }
    val host = if (mode == CameraMode.AP) apHost else staHost
    val portNumber = port.toIntOrNull()
    val enabled = !state.camera.connecting && !state.ble.pairingInProgress
    val connection = state.cameraChangeContext()
    var replaceWifi by remember(connection, visible) { mutableStateOf<CameraConnection?>(null) }
    AnimatedVisibility(visible) {
        SectionCard("手动连接", glyph = AppGlyph.WIFI) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(CameraMode.AP to "相机热点", CameraMode.STA to "同一局域网").forEachIndexed { index, (option, label) ->
                    SegmentedButton(selected = mode == option, onClick = { onModeChange(option) }, enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                }
            }
            if (mode == CameraMode.AP) {
                Text("先开启相机热点", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(ssid, { ssid = it }, label = { Text("热点名称（留空使用当前 Wi-Fi）") }, singleLine = true,
                    enabled = enabled, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("热点密码") }, singleLine = true, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                TextButton(onClick = actions.onOpenWifi) { Text("系统 Wi-Fi 设置") }
            } else {
                Text("手机与相机连接同一网络，相机保持等待连接。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(staHost, { staHost = it }, label = { Text("相机 IP") }, singleLine = true, enabled = enabled,
                    modifier = Modifier.fillMaxWidth())
                TextButton(onClick = actions.onDiscoverCamera, enabled = enabled) { Text("搜索局域网相机") }
                state.cameraEndpoints.forEach { endpoint ->
                    OutlinedButton(onClick = {
                        staHost = endpoint.host
                        port = endpoint.port.toString()
                        discoveredSonyHost = if (endpoint.make == CameraMake.SONY) endpoint.host else ""
                        if (endpoint.make != CameraMake.SONY) manualAuth = false
                    }, enabled = enabled) {
                        Text("${endpoint.name} · ${endpoint.host}")
                    }
                }
                if (state.camera.connected && state.camera.make == CameraMake.NIKON) {
                    TextButton(onClick = onRegister) { Text("首次连接：登记手机") }
                }
            }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起端口与地址" else "端口与地址") }
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode == CameraMode.AP) OutlinedTextField(apHost, { apHost = it }, label = { Text("相机 IP（留空使用热点网关）") },
                        singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(port, { port = it }, label = { Text("PTP/IP 端口") }, singleLine = true, enabled = enabled,
                        modifier = Modifier.fillMaxWidth())
                }
            }
            TextButton(onClick = { manualAuth = !manualAuth; if (!manualAuth) secret = "" }, enabled = enabled) {
                Text(if (manualAuth) "收起索尼访问认证" else "手动输入索尼访问认证")
            }
            if (manualAuth) {
                Text("请查看机身「访问认证信息」。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(user, { user = it }, label = { Text("相机用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text("访问密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fingerprint, { fingerprint = it }, label = { Text("机身 SHA-256 指纹") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
            PrimaryAction("连接", onClick = {
                val discoveredSony = mode == CameraMode.STA && discoveredSonyHost.isNotBlank() && host == discoveredSonyHost
                if (portNumber != null) {
                    val config = CameraConnection(mode, host, portNumber,
                        if (mode == CameraMode.AP) ssid else "", if (mode == CameraMode.AP) password else "",
                        sonyAccess = access, sonyCamera = manualAuth || (discoveredSony && !state.settings.sonySelected))
                    if (connection.wifi) replaceWifi = config else actions.onConnectCamera(config, null)
                }
                if (manualAuth) secret = ""
            }, modifier = Modifier.fillMaxWidth(), enabled = enabled && portNumber != null && portNumber in 1..65535 &&
                (!manualAuth || access != null) && (mode == CameraMode.AP || host.isNotBlank()), glyph = AppGlyph.CAMERA)
        }
    }
    replaceWifi?.let { config ->
        CameraChangeDialog(stringResource(R.string.camera_replace_wifi), { replaceWifi = null }) {
            replaceWifi = null
            actions.onConnectCamera(config, connection)
        }
    }
}
