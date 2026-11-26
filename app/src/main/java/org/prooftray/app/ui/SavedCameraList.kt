package org.prooftray.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.prooftray.app.R
import org.prooftray.app.camera.*
import org.prooftray.app.domain.CameraBrand
import org.prooftray.app.domain.SavedCamera

internal data class DeviceLinks(val bluetooth: Boolean, val wifi: Boolean, val otherLive: Boolean,
                                val awaiting: Boolean, val attempting: Boolean, val failure: String?)

internal fun deviceLinks(device: SavedCamera, camera: CameraState, ble: BleState): DeviceLinks {
    val sony = camera.sonyControlAddress
    val nikon = ble.connectedAddress.takeIf { ble.authenticated && it.isNotBlank() }
    val bluetooth = device.address.isNotBlank() && when (device.brand) {
        CameraBrand.NIKON -> device.address.equals(nikon, true)
        CameraBrand.SONY -> device.address.equals(sony, true)
    }
    val wifi = camera.connected && camera.connectedId == device.id
    val other = (camera.connected && !wifi) ||
        (sony != null && !(device.brand == CameraBrand.SONY && bluetooth)) ||
        (nikon != null && !(device.brand == CameraBrand.NIKON && bluetooth))
    return DeviceLinks(bluetooth, wifi, other, camera.accessRequest?.targetId == device.id,
        camera.connecting && camera.attemptId == device.id, camera.deviceError.takeIf { camera.failedId == device.id })
}

@Composable
internal fun SavedCameraList(state: LibraryUiState, actions: LibraryActions, onAdd: (String?) -> Unit, onTasks: () -> Unit) {
    val registry = state.settings.cameras
    val camera = state.camera
    val busy = transferBlocksCameraChange(camera, state.automatic)
    val operating = camera.connecting || camera.accessRequest != null || camera.controlAttempt != null || state.ble.pairingInProgress
    val connection = state.cameraChangeContext()
    var confirmId by remember(connection) { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf(false) }
    var confirmChannel by remember { mutableStateOf(SavedCameraChannel.WIFI) }
    val enabled = !registry.readError
    SectionCard(stringResource(R.string.saved_cameras)) {
        if (!enabled) Text(stringResource(R.string.camera_registry_damaged), color = MaterialTheme.colorScheme.error)
        if (registry.devices.isEmpty() && enabled) Text(stringResource(R.string.no_saved_cameras))
        Column {
            registry.devices.forEachIndexed { index, device -> key(device.id) {
                if (index > 0) HorizontalDivider()
                val links = deviceLinks(device, camera, state.ble)
                val similar = registry.devices.filter { it.title == device.title || (it.model.isNotBlank() && it.model == device.model) }
                val suffix = if (similar.size > 1) {
                    val tail = device.serial.ifBlank { device.address }.takeLast(6)
                    if (tail.isBlank() || similar.count { it.serial.ifBlank { it.address }.takeLast(6) == tail } > 1)
                        device.id.takeLast(8) else tail
                } else null
                var menu by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppIcon(AppGlyph.CAMERA)
                        Column(Modifier.weight(1f)) {
                            Text(listOfNotNull(device.title, suffix).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                            if (device.model.isNotBlank() && device.model != device.title) Text(device.model, style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = enabled) {
                                AppIcon(AppGlyph.MORE, stringResource(R.string.camera_menu, device.title))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.camera_remove)) },
                                    enabled = device.id != registry.selectedId || (!busy && !operating),
                                    onClick = { menu = false; remove = true; confirmId = device.id })
                            }
                        }
                    }
                    if (device.pending) Text(stringResource(R.string.camera_legacy), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(if (links.bluetooth) R.string.camera_ble_ready else R.string.camera_ble_off),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (links.wifi) R.string.camera_wifi_ready else R.string.camera_wifi_off),
                        style = MaterialTheme.typography.bodySmall)
                    val awaitingControl = links.awaiting && camera.accessRequest?.channel == SavedCameraChannel.BLUETOOTH
                    val connectingControl = camera.controlAttempt?.targetId == device.id
                    if (awaitingControl) Text(stringResource(R.string.camera_wait_ble_permission))
                    else if (connectingControl) Text(stringResource(R.string.camera_connecting_ble))
                    else if (links.awaiting || links.attempting || (links.wifi && camera.connecting))
                        Text(if (links.awaiting) stringResource(R.string.camera_wait_access) else camera.stage)
                    links.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    fun connect(channel: SavedCameraChannel) {
                        if (links.otherLive) {
                            remove = false
                            confirmChannel = channel
                            confirmId = device.id
                        } else actions.onConnectSavedCamera(device.id, null, channel)
                    }
                    if (!device.pending) {
                        val cancelControl = awaitingControl || connectingControl
                        TextButton(enabled = enabled && !busy && (!operating || cancelControl), onClick = {
                            if (cancelControl || links.bluetooth) actions.onDisconnectSavedControl(device.id)
                            else connect(SavedCameraChannel.BLUETOOTH)
                        }) {
                            Text(stringResource(when {
                                cancelControl -> R.string.camera_cancel_ble
                                links.bluetooth -> R.string.camera_disconnect_ble
                                else -> R.string.camera_connect_ble
                            }))
                        }
                    }
                    val cancel = links.attempting || (links.awaiting && !awaitingControl)
                    TextButton(enabled = enabled && !busy && (!operating || cancel), onClick = {
                        when {
                            cancel || links.wifi -> actions.onDisconnectSavedCamera(device.id)
                            device.pending && (!device.hasControlAddress || device.brand == CameraBrand.SONY) -> onAdd(device.id)
                            else -> connect(SavedCameraChannel.WIFI)
                        }
                    }) {
                        Text(stringResource(when {
                            cancel -> R.string.camera_cancel
                            links.wifi -> R.string.camera_disconnect_wifi
                            device.pending -> R.string.camera_confirm_legacy
                            links.failure != null && camera.failedChannel == SavedCameraChannel.WIFI -> R.string.camera_retry_wifi
                            else -> R.string.camera_connect_wifi
                        }))
                    }
                }
            } }
        }
        if (busy) {
            Text(stringResource(R.string.camera_busy))
            TextButton(onClick = onTasks) { Text(stringResource(R.string.camera_tasks)) }
        }
        TextButton(onClick = { onAdd(null) }, enabled = enabled && !busy && !operating && !camera.connected) {
            AppIcon(AppGlyph.ADD)
            Text(stringResource(R.string.camera_add))
        }
    }
    registry.devices.find { it.id == confirmId }?.let { device ->
        val dismiss = { confirmId = null }
        val label = if (remove) R.string.camera_remove else R.string.camera_switch
        AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(label)) },
            text = { Text(stringResource(if (remove) R.string.camera_remove_detail else R.string.camera_switch_detail, device.title)) },
            confirmButton = { TextButton(onClick = {
                dismiss()
                if (remove) actions.onRemoveSavedCamera(device.id) else actions.onConnectSavedCamera(device.id, connection, confirmChannel)
            }) { Text(stringResource(label)) } },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.camera_cancel)) } })
    }
}
