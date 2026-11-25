package org.prooftray.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.prooftray.app.camera.CameraConnection
import org.prooftray.app.camera.CameraMake
import org.prooftray.app.camera.CameraMode
import org.prooftray.app.camera.ConnectionPhase
import org.prooftray.app.camera.SonyAccess

internal enum class CameraPanel { DEVICES, SEARCH, MANUAL, DIAGNOSTICS }

@Composable
internal fun CameraScreen(
    state: LibraryUiState, actions: LibraryActions, onBrowse: () -> Unit, onRegister: () -> Unit,
    modifier: Modifier = Modifier, initialPanel: CameraPanel? = null
) {
    var panel by rememberSaveable { mutableStateOf(initialPanel) }
    var manualMode by rememberSaveable(state.settings.selectedCameraId) { mutableStateOf(CameraMode.AP) }
    var menu by remember { mutableStateOf(false) }
    var pendingSwitch by remember(state.settings.selectedCameraId) { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    fun openPanel(next: CameraPanel?) {
        menu = false
        if (panel == CameraPanel.SEARCH && next != panel) actions.onStopBleScan()
        panel = next
        if (next == CameraPanel.SEARCH && !state.ble.scanning) actions.onScanBle()
    }
    BackHandler(enabled = panel != null) { openPanel(null) }
    val summary = cameraConnectionSummary(state)
    val addReason = when {
        state.cameraTransferRunning -> "请先完成或停止当前传图"
        state.camera.connected -> "请先断开当前相机"
        summary.busy -> "等待当前连接操作完成"
        !state.ble.adapterEnabled -> "请先打开手机蓝牙"
        else -> null
    }
    fun switchTo(label: String, change: () -> Unit) {
        if (state.cameraSelectionBlock != null) return
        if (state.hasCameraConnection) pendingSwitch = label to change else change()
    }
    fun selectCamera(id: String) {
        val target = state.settings.cameras.firstOrNull { it.id == id } ?: return
        if (id == state.settings.selectedCameraId && state.camera.pairingTarget == null &&
            (!state.camera.connected || state.camera.activeCameraId == id)) {
            openPanel(null)
        } else switchTo(target.selectionLabel(state.settings.cameras)) {
            openPanel(null)
            actions.onSelectCamera(id)
        }
    }
    val saved = state.settings.selectedAddress.isNotBlank()
    val nikon = state.hasSelectedNikon
    val pairing = state.camera.pairingTarget
    val showNikonDiagnostics = if (pairing != null) pairing.make == CameraMake.NIKON else !state.settings.sonySelected
    LaunchedEffect(initialPanel) {
        if (initialPanel == CameraPanel.SEARCH && panel == CameraPanel.SEARCH && !state.ble.scanning && addReason == null) actions.onScanBle()
    }
    val primary = cameraPrimaryAction(state, summary)
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val canSelect = state.settings.cameras.isNotEmpty()
                    Column(Modifier.weight(1f).clickable(enabled = canSelect, onClickLabel = "查看我的相机") { openPanel(CameraPanel.DEVICES) },
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.connectionCameraName.ifBlank { "我的相机" }, Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (canSelect) AppIcon(AppGlyph.FORWARD, modifier = Modifier.rotate(90f))
                        }
                        Text(summary.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { AppIcon(AppGlyph.MORE, "连接选项") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            ConnectionMenuItem("添加新相机", addReason) { openPanel(CameraPanel.SEARCH) }
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
                key(state.settings.selectedCameraId) {
                    ManualConnection(state, actions, visible = panel == CameraPanel.MANUAL, mode = manualMode,
                        onModeChange = { manualMode = it }, onRegister = onRegister)
                }
                if (nikon) {
                    val wifi = state.camera.connected
                    val bluetooth = summary.bluetoothReady
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
                        OutlinedButton(onClick = if (state.ble.adapterEnabled) actions.onSwitchToBluetooth else actions.onOpenBluetoothSettings,
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
                SectionCard("添加新相机", "在机身的智能手机连接菜单中开始配对。", AppGlyph.BLUETOOTH) {
                    if (state.ble.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.ble.message, style = MaterialTheme.typography.bodySmall)
                    if (!state.ble.scanning && state.ble.cameras.isEmpty()) Text("未发现相机")
                    state.ble.cameras.forEach { camera ->
                        val target = state.settings.cameras.singleOrNull { it.make == camera.make && camera.address.equals(it.controlAddress, true) }
                        OutlinedButton(onClick = {
                            if (target != null && target.hasScanRegistration) selectCamera(target.id)
                            else switchTo(camera.name) { panel = null; actions.onConnectBle(camera) }
                        }, enabled = (target?.hasScanRegistration == true || camera.pairingAvailable) && addReason == null,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(when {
                                target?.hasScanRegistration == true -> "${target.selectionLabel(state.settings.cameras)} · 已保存"
                                target != null && camera.pairingAvailable -> "${target.selectionLabel(state.settings.cameras)} · 完成配对"
                                camera.pairingAvailable -> camera.name
                                else -> "${camera.name} · 请在机身打开「智能手机连接」"
                            })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = actions.onScanBle, enabled = !state.ble.scanning && addReason == null) {
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
    if (panel == CameraPanel.DEVICES) {
        CameraDeviceSheet(state, actions, addReason, ::selectCamera, { openPanel(CameraPanel.SEARCH) }, { openPanel(null) })
    }
    pendingSwitch?.let { (target, change) ->
        val source = state.currentCameraLabel
        AlertDialog(onDismissRequest = { pendingSwitch = null }, title = { Text("断开 $source 并切换到 $target？") },
            text = state.cameraSelectionBlock?.let { reason -> { Text(reason) } },
            confirmButton = { TextButton(onClick = { pendingSwitch = null; change() }, enabled = state.cameraSelectionBlock == null) { Text("切换") } },
            dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text("取消") } })
    }
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
                if (portNumber != null) actions.onConnectCamera(CameraConnection(mode, host, portNumber,
                    if (mode == CameraMode.AP) ssid else "", if (mode == CameraMode.AP) password else "",
                    sonyAccess = access, sonyCamera = manualAuth || (discoveredSony && !state.settings.sonySelected)))
                if (manualAuth) secret = ""
            }, modifier = Modifier.fillMaxWidth(), enabled = enabled && portNumber != null && portNumber in 1..65535 &&
                (!manualAuth || access != null) && (mode == CameraMode.AP || host.isNotBlank()), glyph = AppGlyph.CAMERA)
        }
    }
}
