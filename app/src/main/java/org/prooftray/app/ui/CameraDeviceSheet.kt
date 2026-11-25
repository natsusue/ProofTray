package org.prooftray.app.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.prooftray.app.camera.BleState
import org.prooftray.app.camera.CameraMake
import org.prooftray.app.domain.SavedCamera
import java.util.Date

internal fun SavedCamera.selectionLabel(cameras: List<SavedCamera>): String {
    if (cameras.none { it.id != id && it.displayName.equals(displayName, true) }) return displayName
    val number = serial.ifBlank { controlAddress.ifBlank { transferAddress }.replace(":", "") }
    return if (number.isBlank()) displayName else "$displayName · ${number.takeLast(6)}"
}

internal val LibraryUiState.currentCameraLabel: String get() {
    if (camera.connecting && camera.pairingTarget != null) return camera.pairingTarget.name
    val target = if (camera.connected) settings.cameras.firstOrNull { it.id == camera.activeCameraId } else settings.selectedCamera
    return target?.selectionLabel(settings.cameras) ?: connectionCameraName.ifBlank { "当前相机" }
}

private enum class CameraActionForEntry { RENAME, REMOVE, REPAIR }

@Composable
internal fun CameraDeviceSheet(
    state: LibraryUiState, actions: LibraryActions, addReason: String?, select: (String) -> Unit, add: () -> Unit, dismiss: () -> Unit
) {
    val selected = state.settings.selectedCameraId
    val cameras = remember(state.settings.cameras, selected) {
        state.settings.cameras.sortedWith(compareByDescending<SavedCamera> { it.id == selected }.thenByDescending { it.lastConnectedAt })
    }
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    var action by rememberSaveable { mutableStateOf<CameraActionForEntry?>(null) }
    val context = LocalContext.current
    val target = cameras.firstOrNull { it.id == targetId }
    val selectionBlock = state.cameraSelectionBlock
    AppSheet("我的相机", dismiss, actions = {
        TextButton(onClick = add, enabled = addReason == null) { Text("添加新相机") }
        TextButton(onClick = dismiss) { Text("关闭") }
    }, compact = true) {
        if (cameras.isEmpty()) Text("尚未添加相机")
        for (camera in cameras) {
            val label = camera.selectionLabel(cameras)
            val current = camera.id == selected
            val active = current || camera.id == state.camera.activeCameraId
            val canRemove = (!active || selectionBlock == null) && !state.ble.pairingInProgress && !state.camera.connecting
            val status = when {
                state.camera.connected && camera.id == state.camera.activeCameraId -> "Wi-Fi 已连接"
                current && camera.make == CameraMake.SONY && state.camera.sonyControlConnected &&
                    state.camera.pairingTarget == null -> "蓝牙已连接"
                current && camera.make == CameraMake.NIKON && state.ble.bluetoothReadyFor(camera) -> "蓝牙已连接"
                current && !state.camera.connecting -> "未连接"
                else -> null
            }
            val brand = when (camera.make) { CameraMake.NIKON -> "尼康"; CameraMake.SONY -> "索尼"; CameraMake.OTHER -> "相机" }
            val details = listOfNotNull(brand, camera.name.takeIf { camera.alias.isNotBlank() && it.isNotBlank() }, status)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(enabled = current || selectionBlock == null) { select(camera.id) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        camera.lastConnectedAt?.let {
                            val date = Date(it)
                            val time = "${DateFormat.getDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
                            Text("上次连接 $time", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (current) AppIcon(AppGlyph.CHECK, "当前选中")
                }
                Box {
                    var menu by remember(camera.id) { mutableStateOf(false) }
                    fun choose(value: CameraActionForEntry) { menu = false; targetId = camera.id; action = value }
                    IconButton(onClick = { menu = true }) { AppIcon(AppGlyph.MORE, "$label 的选项") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("设置别名") }, onClick = { choose(CameraActionForEntry.RENAME) })
                        if (current) DropdownMenuItem(text = { Text("重新配对") }, enabled = addReason == null && camera.controlAddress.isNotBlank(),
                            onClick = { choose(CameraActionForEntry.REPAIR) })
                        DropdownMenuItem(text = { Text("从列表移除") }, enabled = canRemove, onClick = { choose(CameraActionForEntry.REMOVE) })
                    }
                }
            }
        }
        (selectionBlock ?: addReason?.let { "添加新相机：$it" })?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (target == null || action == null) return
    val name = target.selectionLabel(cameras)
    val cancel = { action = null }
    when (action) {
        CameraActionForEntry.RENAME -> {
            var alias by rememberSaveable(target.id) { mutableStateOf(target.alias) }
            AlertDialog(onDismissRequest = cancel, title = { Text("设置相机别名") },
                text = { OutlinedTextField(alias, { alias = it }, singleLine = true, label = { Text("别名") }, placeholder = { Text(target.name) }) },
                confirmButton = { TextButton(onClick = { actions.onRenameCamera(target.id, alias.trim()); cancel() }) { Text("保存") } },
                dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
        }
        CameraActionForEntry.REMOVE -> {
            val active = target.id == selected || target.id == state.camera.activeCameraId
            AlertDialog(onDismissRequest = cancel, title = { Text("移除 $name？") }, text = {
                Column {
                    if (active && state.hasCameraConnection) Text("将断开 ${state.currentCameraLabel} 的连接。")
                    Text("仅移除本应用中的记录。系统蓝牙配对、照片、评分和导入历史都会保留。")
                }
            }, confirmButton = {
                TextButton(onClick = { actions.onRemoveCamera(target.id); cancel() }, enabled = (!active || selectionBlock == null) &&
                    !state.camera.connecting && !state.ble.pairingInProgress) { Text("移除") }
            }, dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
        }
        CameraActionForEntry.REPAIR -> if (target.id == selected) {
            AlertDialog(onDismissRequest = cancel, title = { Text("重新配对 $name？") }, text = {
                Text("请先在这台机身上打开智能手机配对。完成后会更新现有的相机记录。")
            }, confirmButton = {
                TextButton(onClick = { cancel(); dismiss(); actions.onRepairSelectedCamera() }, enabled = addReason == null) { Text("开始配对") }
            }, dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
        }
        null -> Unit
    }
}

private fun BleState.bluetoothReadyFor(camera: SavedCamera): Boolean =
    authenticated && systemPaired && !pairingInProgress && connectedAddress.equals(camera.controlAddress, true)
