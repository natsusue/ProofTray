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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.prooftray.app.R
import org.prooftray.app.camera.BleCamera
import org.prooftray.app.camera.CatalogBatchSize
import org.prooftray.app.camera.CameraMake
import org.prooftray.app.camera.importHistoryBlockReason
import org.prooftray.app.domain.BluetoothRole
import org.prooftray.app.domain.PowerProfile
import org.prooftray.app.domain.AutoDownloadKind
import org.prooftray.app.domain.DownloadVariant

private enum class SettingsSection(val title: String, val glyph: AppGlyph) {
    DEVICES("相机设备", AppGlyph.CAMERA), AUTO("自动导入", AppGlyph.REFRESH), IMPORT("导入", AppGlyph.DOWNLOAD),
    LOCATION("位置同步", AppGlyph.LOCATION), POWER("功耗与响应", AppGlyph.BATTERY)
}

@Composable
internal fun SettingsScreen(
    state: LibraryUiState, actions: LibraryActions, onCamera: (CameraPanel?) -> Unit, onAbout: () -> Unit, onManage: () -> Unit
) {
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    val settings = state.settings
    val autoKind = if (settings.sonySelected && settings.autoKind == AutoDownloadKind.CAMERA_JPEG) AutoDownloadKind.JPEG else settings.autoKind
    val connection = cameraConnectionSummary(state).title
    LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "camera-header") { SettingsHeader("相机与导入") }
        SettingsSection.entries.forEach { option ->
            item(key = option.name) {
                val value = when (option) {
                    SettingsSection.DEVICES -> settings.selectedName.takeIf { it.isNotBlank() }?.let { "$it · $connection" }
                        ?: if (settings.cameras.isEmpty()) "未添加" else "待选择 · ${settings.cameras.size} 台相机"
                    SettingsSection.AUTO -> if (settings.automaticEnabled) autoKind.label() else "关闭"
                    SettingsSection.IMPORT -> settings.effectiveManualVariant.label()
                    SettingsSection.LOCATION -> if (settings.locationSync) "开启" else "关闭"
                    SettingsSection.POWER -> settings.powerProfile.label()
                }
                SettingsRow(option.title, option.glyph, value) { section = option }
            }
        }
        item(key = "general-header") { SettingsHeader("通用") }
        item(key = "color") {
            SettingsRow("调色与边框", AppGlyph.FRAME, "${state.luts.size + state.removedLuts.size} 个 LUT · ${state.colorPresets.size} 个预设", onManage)
        }
        item(key = "about") { SettingsRow("关于 ${stringResource(R.string.app_name)}", AppGlyph.INFO, onClick = onAbout) }
    }
    section?.let { selected ->
        val dismiss = { section = null }
        val manageCamera = { section = null; onCamera(null) }
        AppSheet(selected.title, dismiss, actions = { TextButton(onClick = dismiss) { Text("完成") } }, compact = true) {
            LibraryErrorNotice(state, actions)
            when (selected) {
                SettingsSection.DEVICES -> DevicesSettings(state, actions) {
                    section = null
                    onCamera(if (settings.cameras.isEmpty()) CameraPanel.SEARCH else CameraPanel.DEVICES)
                }
                SettingsSection.AUTO -> {
                    SettingSwitch("自动导入", settings.automaticEnabled) { enabled ->
                        if (enabled && autoKind != settings.autoKind) actions.onSetAutoKind(autoKind)
                        actions.onSetAutoDownload(enabled)
                    }
                    val kinds = AutoDownloadKind.entries.filter { !settings.sonySelected || it != AutoDownloadKind.CAMERA_JPEG }
                    ChoiceRow(kinds, autoKind, AutoDownloadKind::label, actions.onSetAutoKind)
                    val status = if (state.automatic.checking && state.ble.pairingInProgress) state.ble.message else state.automatic.message
                    val showStatus = settings.automaticEnabled || state.automatic.checking || state.automatic.transferring
                    if (showStatus) Text(status)
                    if (autoKind == AutoDownloadKind.CAMERA_JPEG) {
                        Text("相机需开启“自动选择发送”。关机接收还需开启“关机后发送”。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = actions.onCheckBluetoothTransfers, enabled = !state.automatic.checking) { Text("检查待传 JPEG") }
                        val result = state.automatic.lastCheckResult
                        if (!state.automatic.checking && result.isNotBlank() && (!showStatus || result != status)) {
                            Text(result, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text("自动接收连接后新拍的原片，之前的照片可手动导入。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = manageCamera) { Text("相机连接") }
                    }
                }
                SettingsSection.IMPORT -> {
                    if (settings.sonySelected) Text("导入相机原片")
                    else ChoiceRow(DownloadVariant.entries, settings.manualVariant, DownloadVariant::label, actions.onSetManualVariant)
                    Text("每批加载与导入上限", style = MaterialTheme.typography.labelLarge)
                    CatalogBatchOptions(state.camera.catalogBatchSize, actions.onSetBatchSize)
                    ImportHistoryControl(state, actions.onClearImportHistory)
                }
                SettingsSection.LOCATION -> {
                    SettingSwitch("使用手机位置", settings.locationSync, actions.onSetLocationSync)
                    Text("仅用于之后拍摄的照片。", style = MaterialTheme.typography.bodySmall)
                    if (settings.sonySelected) {
                        Text("通过蓝牙同步，无需连接 Wi-Fi。请先关闭其他手机或 Creators’ App 的位置关联。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(state.location.message)
                    if (settings.locationSync) TextButton(onClick = { actions.onSetLocationSync(true) }) {
                        Text(if (settings.sonySelected || !state.location.running) "恢复同步" else "立即同步")
                    }
                }
                SettingsSection.POWER -> {
                    val profile = settings.powerProfile
                    ChoiceRow(PowerProfile.entries, profile, PowerProfile::label, actions.onSetPowerProfile)
                    val locationSeconds = if (settings.sonySelected) 10 else profile.locationSeconds
                    Text("检查间隔约 ${profile.pollSeconds} 秒 · 定位间隔约 $locationSeconds 秒",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = actions.onOpenAppSettings) { Text("系统权限与电池管理") }
                }
            }
        }
    }
}

@Composable
private fun ImportHistoryControl(state: LibraryUiState, onClear: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val reason = if (state.clearingImportHistory) "正在清除导入历史" else importHistoryBlockReason(state.camera.tasks, state.automatic)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { confirming = true }, enabled = !state.clearingImportHistory,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除导入历史") }
        Text("${state.importHistoryCount} 条", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("清除 ${state.importHistoryCount} 条导入历史？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("不在手机上的相机照片会重新显示为未导入，再次导入可能产生重复文件。")
            Text("手机文件、图库条目和相机文件都不会删除。此操作无法撤销。")
            if (reason != null) Text(reason, color = MaterialTheme.colorScheme.error)
        } }, confirmButton = {
            TextButton(onClick = { confirming = false; onClear() }, enabled = reason == null,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除历史") }
        }, dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } })
}

@Composable
private fun SettingsHeader(title: String) {
    Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingsRow(title: String, glyph: AppGlyph, value: String? = null, onClick: () -> Unit) {
    ListItem(modifier = Modifier.clickable(onClick = onClick), headlineContent = { Text(title) },
        supportingContent = value?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { AppIcon(glyph, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { AppIcon(AppGlyph.FORWARD, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
}

@Composable
private fun DevicesSettings(state: LibraryUiState, actions: LibraryActions, onCamera: () -> Unit) {
    val settings = state.settings
    var advanced by rememberSaveable(settings.selectedCameraId) { mutableStateOf(false) }
    val hasCameras = settings.cameras.isNotEmpty()
    Text(settings.selectedName.ifBlank { if (hasCameras) "请选择相机" else "尚未添加相机" }, style = MaterialTheme.typography.titleMedium)
    TextButton(onClick = onCamera) { Text(if (hasCameras) "我的相机（${settings.cameras.size}）" else "添加相机") }
    if (settings.sonySelected || (hasCameras && settings.selectedCameraId == null)) return
    val otherDevices = settings.cameras.filter { it.id != settings.selectedCameraId }
    fun available(device: BleCamera) = device.make != CameraMake.SONY && otherDevices.none {
        device.address.equals(it.controlAddress, true) || device.address.equals(it.transferAddress, true)
    }
    val devices = (state.ble.cameras + state.pairedDevices).filter(::available).distinctBy { it.address }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置") }
    AnimatedVisibility(advanced) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingSwitch("配对时同步时间", state.settings.pairingTimeSync, actions.onSetPairingTimeSync)
            Text("配对时主动同步；相机请求时的同步看下面的开关", style = MaterialTheme.typography.bodySmall)
            SettingSwitch("相机请求时同步时间", state.settings.requestedTimeSync, actions.onSetRequestedTimeSync)
            TextButton(onClick = actions.onRefreshPairedDevices) { Text("读取系统配对设备") }
            DeviceChoices("蓝牙控制设备", devices, state.settings.controlAddress, actions.onChooseControlDevice)
            val transfers = state.pairedDevices.filter { it.classic && available(it) }
            DeviceChoices("蓝牙传图设备", transfers, settings.transferAddress, actions.onChooseTransferDevice)
            Text("连接方向", style = MaterialTheme.typography.labelLarge)
            ChoiceRow(BluetoothRole.entries, state.settings.bluetoothRole, { when (it) {
                BluetoothRole.AUTOMATIC -> "自动"
                BluetoothRole.CONNECT_TO_CAMERA -> "手机连接相机"
                BluetoothRole.WAIT_FOR_CAMERA -> "相机连接手机"
            } }, actions.onSetBluetoothRole)
            if (state.ble.authenticated) Text("控制地址：${state.ble.connectedAddress}", style = MaterialTheme.typography.bodySmall)
            if (state.ble.serialNumber.isNotBlank()) Text("机身序列号：${state.ble.serialNumber}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = actions.onOpenBluetoothSettings) { Text("系统蓝牙设置") }
            TextButton(onClick = actions.onDisconnectControl) { Text("断开蓝牙") }
        }
    }
}

@Composable
private fun DeviceChoices(title: String, devices: List<BleCamera>, selected: String, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (devices.isEmpty()) Text(if (selected.isBlank()) "尚未读取或发现设备" else "已保存地址：$selected", style = MaterialTheme.typography.bodySmall)
    devices.forEach { device ->
        FilterChip(selected = device.address == selected, onClick = { onSelect(device.address) },
            label = { Text("${device.name} · ${device.address.takeLast(8)}") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun CatalogBatchOptions(size: Int?, onChange: (Int?) -> Unit) {
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    val custom = size != null && size !in CatalogBatchSize.presets
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CatalogBatchSize.presets.forEach { count ->
            FilterChip(selected = size == count, onClick = { onChange(count) }, label = { Text("$count 张") })
        }
        FilterChip(selected = custom, onClick = { draft = size?.toString().orEmpty() },
            label = { Text(if (custom) "自定义 · $size" else "自定义") })
        FilterChip(selected = size == null, onClick = { onChange(null) }, label = { Text("不限") })
    }
    draft?.let { input ->
        val number = input.trim().toIntOrNull()
        val valid = number != null && CatalogBatchSize.valid(number)
        AlertDialog(onDismissRequest = { draft = null }, title = { Text("自定义每批数量") }, text = {
            OutlinedTextField(input, { draft = it }, singleLine = true, isError = !valid, label = { Text("张数") },
                supportingText = { if (!valid) Text("请输入 1–5000 的整数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }, confirmButton = {
            TextButton(enabled = valid, onClick = { if (valid) { onChange(number); draft = null } }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = { draft = null }) { Text("取消") } })
    }
}

@Composable
private fun <T> ChoiceRow(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label(value)) }) }
    }
}

internal fun AutoDownloadKind.label(): String = when (this) {
    AutoDownloadKind.CAMERA_JPEG -> "便携 JPEG · 蓝牙"
    AutoDownloadKind.JPEG -> "JPEG 原片 · Wi-Fi"
    AutoDownloadKind.RAW -> "RAW 原片 · Wi-Fi"
    AutoDownloadKind.BOTH -> "RAW + JPEG · Wi-Fi"
}
internal fun DownloadVariant.label(): String = if (this == DownloadVariant.ORIGINAL) "所选原格式" else "便携 JPEG（约 2MP）"
private fun PowerProfile.label(): String = when (this) {
    PowerProfile.SAVER -> "省电"
    PowerProfile.BALANCED -> "均衡"
    PowerProfile.RESPONSIVE -> "及时"
}
