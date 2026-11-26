package org.prooftray.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun CameraStatusCard(state: LibraryUiState, actions: LibraryActions, onOpen: (CameraPanel?) -> Unit) {
    val summary = cameraConnectionSummary(state)
    val camera = state.camera
    val name = state.connectionCameraName.ifBlank { "相机" }
    val primary = cameraPrimaryAction(state, summary)
    Surface(onClick = { onOpen(null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (camera.connected) Text("$name · 已连接", Modifier.weight(1f).padding(vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(summary.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (camera.connected) {
                    if (!camera.readingCatalog) IconButton(onClick = actions.onRefreshCamera, enabled = !camera.busy && !camera.connecting) {
                        AppIcon(AppGlyph.REFRESH, "刷新相机照片")
                    }
                    TextButton(onClick = actions.onDisconnectWifi, enabled = !camera.connecting) { Text("断开 Wi-Fi") }
                } else IconButton(onClick = { onOpen(null) }) { AppIcon(AppGlyph.FORWARD, "相机连接") }
            }
            if (!camera.connected) {
                if (summary.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (primary.action != CameraAction.NONE) TextButton(onClick = {
                    performCameraAction(primary.action, actions, { onOpen(null) }, { onOpen(it) })
                }) { Text(primary.label) }
            }
            if (camera.readingCatalog || (camera.catalogStopped && camera.hasMore)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (camera.total < 0) "已读取 ${camera.indexed} 张" else "已读取 ${camera.indexed} / ${camera.total}",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    if (camera.readingCatalog) TextButton(onClick = actions.onStopCatalogRead, enabled = !camera.catalogStopped) {
                        Text(if (camera.catalogStopped) "正在停止" else "停止读取")
                    } else TextButton(onClick = actions.onLoadMoreCamera) { Text("继续加载") }
                }
            }
        }
    }
}

@Composable
internal fun CameraConnectionPage(
    state: LibraryUiState, actions: LibraryActions, panel: CameraPanel?, recoveryId: String?,
    onBrowse: () -> Unit, onRegister: () -> Unit, onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { AppIcon(AppGlyph.BACK, "返回") }
                    Text("相机连接", style = MaterialTheme.typography.titleLarge)
                }
                LibraryErrorNotice(state, actions)
                CameraScreen(state, actions, onBrowse, onRegister, Modifier.weight(1f), panel, recoveryId)
            }
        }
    }
}

@Composable
internal fun LibraryErrorNotice(state: LibraryUiState, actions: LibraryActions) {
    state.errorMessage?.let { message ->
        ErrorNotice(message, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (state.errorNeedsSettings) TextButton(onClick = actions.onOpenAppSettings) { Text("去设置") }
            IconButton(onClick = actions.onDismissError) { AppIcon(AppGlyph.CLOSE, "关闭提示") }
        }
    }
}

/** Hosted by LibraryContent after every other page/dialog, so a connection request never loses its confirmation on navigation. */
@Composable
internal fun CameraRegistrationDialogs(state: LibraryUiState, actions: LibraryActions, registering: Boolean, onClose: () -> Unit) {
    if (registering) AlertDialog(onDismissRequest = onClose, title = { Text("登记当前手机") },
        text = { Text("首次 STA 连接可能需要登记手机。开始后请核对机身配对码。") },
        confirmButton = { TextButton(onClick = { onClose(); actions.onBeginRegistration() }) { Text("开始登记") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
    state.camera.pairingCode?.let { code ->
        AlertDialog(onDismissRequest = {}, title = { Text("核对机身配对码") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(code, style = MaterialTheme.typography.headlineLarge)
                Text("与相机显示一致，并在机身确认后继续。")
            } }, confirmButton = { TextButton(onClick = actions.onConfirmRegistration) { Text("已核对，完成登记") } },
            dismissButton = { TextButton(onClick = actions.onDisconnectCamera) { Text("取消并断开") } })
    }
}
