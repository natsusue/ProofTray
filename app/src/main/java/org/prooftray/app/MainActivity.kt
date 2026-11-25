package org.prooftray.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.prooftray.app.ui.LibraryActions
import org.prooftray.app.ui.LibraryScreen
import org.prooftray.app.ui.TaskQueueActions
import org.prooftray.app.ui.TransferScreenActions
import org.prooftray.app.domain.ExportDestination
import org.prooftray.app.domain.ExportOptions
import org.prooftray.app.domain.AutoDownloadKind
import org.prooftray.app.camera.CameraController
import org.prooftray.app.camera.CameraLocationService
import org.prooftray.app.camera.CameraService
import org.prooftray.app.data.AppSettingsRepository

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        if (!AppSettingsRepository.get(this).state.value.automaticEnabled) return
        if (CameraController.get(this).state.value.manuallyDisconnected) return
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.all(::granted)) {
            runCatching { CameraService.start(this) }.onFailure { Log.w("ProofTray.Bluetooth", "automatic foreground service unavailable", it) }
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: LibraryViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val access = rememberCameraAccess(model)
            val deletePhotos = rememberPhotoDeletion(model)
            val editInSystem = rememberSystemEditor(model)
            val exportPreset = rememberPresetExport(model)
            fun startLocationSync() {
                try {
                    model.setLocationSync(true)
                    CameraLocationService.startFromForeground(this@MainActivity)
                } catch (error: Exception) {
                    model.setLocationSync(false)
                    model.showError("无法启动位置同步：${error.message}")
                }
            }
            val locationPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) startLocationSync()
                else model.showError("位置同步需要精确位置授权；未启动定位。", needsSettings = true)
            }
            val colorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> model.importColor(uris) }
            val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                model.importPhotos(uris)
            }
            val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                model.exportTo(uri)
            }
            fun startExport(options: ExportOptions) {
                if (options.destination == ExportDestination.GALLERY) model.exportToGallery() else exportPicker.launch(null)
            }
            LibraryScreen(
                state,
                LibraryActions(
                    onImport = { importPicker.launch(arrayOf("*/*")) },
                    onFilterChange = model.browser::setFilter,
                    onSaveFilter = model::saveFilter,
                    onDeleteSavedFilter = model::deleteSavedFilter,
                    onToggleSelection = model.browser::toggleSelection,
                    onSelectVisible = model.browser::selectVisible,
                    onClearSelection = model.browser::clearSelection,
                    onGroupSelection = model::groupSelection,
                    onUngroupSelection = model::ungroupSelection,
                    onSetCollapseStacks = model.browser::setCollapseStacks,
                    onSetRating = model::setRating,
                    onSetPickState = model::setPickState,
                    onRatePhoto = { id, rating -> model.setRating(rating, setOf(id)) },
                    onPickPhoto = { id, pick -> model.setPickState(pick, setOf(id)) },
                    onSetTags = model::setTags,
                    onExportPhotos = { ids, options -> if (model.prepareExport(options, ids)) startExport(options) },
                    transfers = TransferScreenActions(
                        downloads = TaskQueueActions(model.camera::pause, model.camera::retry, model.camera::cancel, model::clearDownloadHistory),
                        exports = TaskQueueActions(model::pauseExports, model::retryTask, model::cancelTask, model::clearExportHistory),
                        onOpenOutput = { openOutput(Uri.parse(it), model) }),
                    onDismissError = model::dismissError,
                    onConnectCamera = { config ->
                        access.withPermissions(state.settings.sonySelected && !config.sonyCamera, wifi = true) {
                            access.withPhoneWifi { model.camera.connect(config) }
                        }
                    },
                    onDisconnectCamera = model::disconnectCamera,
                    onDisconnectWifi = model.camera::disconnectWifiSession,
                    onRetryConnection = {
                        if (state.camera.pairingTarget != null) access.withPermissions(true, scanning = true, action = model.camera::retryConnection)
                        else {
                            val bluetooth = state.camera.bluetoothAssisted
                            access.withPermissions(bluetooth, scanning = bluetooth, wifi = true) {
                                access.withPhoneWifi { model.camera.retryConnection() }
                            }
                        }
                    },
                    onRefreshCamera = model.camera::refresh,
                    onDiscoverCamera = { access.withPermissions(false) { model.camera.discoverSta() } },
                    onOpenWifi = access::openWifiPanel,
                    onScanBle = { access.withPermissions(true, scanning = true) { model.camera.scanBle() } },
                    onConnectBle = { camera -> access.withPermissions(true, scanning = true) { model.camera.connectBle(camera) } },
                    onDiscoverClassic = { access.withPermissions(true, scanning = true) { model.camera.discoverClassic() } },
                    onStopBleScan = model.camera::stopBleScan,
                    onSelectCamera = model::selectCamera,
                    onRemoveCamera = model::removeCamera,
                    onRenameCamera = model::renameCamera,
                    onRepairSelectedCamera = { access.withPermissions(true, scanning = true) { model.camera.repairSelectedCamera() } },
                    onWakeWifi = access::switchToCameraWifi,
                    onSwitchToBluetooth = { access.withPermissions(true, scanning = true) { model.camera.switchToNikonBluetooth() } },
                    onDownloadSelection = model::downloadSelection,
                    onDownloadPhoto = model::downloadPhoto,
                    onRequestPreview = model.camera::requestPreview,
                    onPreviewGeometry = model::updatePreviewGeometry,
                    onSetCollection = model.browser::setCollection,
                    onSetLibrarySection = model.browser::setLibrarySection,
                    onPromoteExports = model::promoteExports,
                    onSetImportVisibility = model.browser::setImportVisibility,
                    onSetOfflinePreviews = model.browser::setOfflinePreviews,
                    onSetBatchSize = model.camera::setCatalogBatchSize,
                    onLoadMoreCamera = model.camera::loadMore,
                    onAutoLoadMoreCamera = model.camera::autoLoadMore,
                    onStopCatalogRead = model.camera::stopCatalogRead,
                    onJumpToDay = model.camera::jumpToDay,
                    onClearImportList = model::clearImportList,
                    onClearImportHistory = model::clearImportHistory,
                    onRemoveFromImport = model::removeFromImport,
                    onRemoveFromLibrary = model::removeFromLibrary,
                    onDeletePhotos = deletePhotos,
                    onBeginRegistration = model.camera::beginRegistration,
                    onConfirmRegistration = model.camera::confirmRegistration,
                    onSetAutoDownload = { enabled ->
                        if (enabled) access.withPermissions(state.settings.autoKind == AutoDownloadKind.CAMERA_JPEG,
                            scanning = true) { model.setAutoDownload(true) } else model.setAutoDownload(false)
                    },
                    onSetAutoKind = { kind ->
                        if (kind != state.settings.autoKind && state.settings.autoDownload) {
                            access.withPermissions(kind == AutoDownloadKind.CAMERA_JPEG, scanning = true) {
                                model.setAutoKind(kind); model.camera.resumeAutomaticDownloads()
                            }
                        } else model.setAutoKind(kind)
                    },
                    onSetManualVariant = model::setManualVariant,
                    onSetPowerProfile = model::setPowerProfile,
                    onSetLocationSync = { enabled ->
                        if (!enabled) { model.setLocationSync(false); CameraLocationService.stop(this@MainActivity) }
                        else access.withPermissions(state.settings.sonySelected, wifi = false) {
                            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) startLocationSync()
                            else locationPermissions.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    },
                    onRefreshPairedDevices = { access.withPermissions(true) { model.refreshPairedDevices() } },
                    onChooseControlDevice = model::chooseControlDevice,
                    onChooseTransferDevice = model::chooseTransferDevice,
                    onSetBluetoothRole = model::setBluetoothRole,
                    onDisconnectControl = model.camera::disconnectControl,
                    onCheckBluetoothTransfers = { access.withPermissions(true, scanning = true) { model.camera.checkBluetoothTransfers() } },
                    onOpenBluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    onOpenAppSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    onImportColor = { colorPicker.launch(arrayOf("*/*")) },
                    onSetLutDefault = model::setLutDefault,
                    onRenameLut = model::renameLut,
                    onSetLutRemoved = model::setLutRemoved,
                    onDeleteLut = model::deleteLut,
                    onDeleteColorPreset = model::deleteColorPreset,
                    onSetLook = model::setLook,
                    onSetFrame = model::setFrame,
                    onSetEditor = model::setEditor,
                    onSetPairingTimeSync = model::setPairingTimeSync,
                    onSetRequestedTimeSync = model::setRequestedTimeSync,
                    onSetExportSelection = model::setExportSelection,
                    onDeleteFrameTemplate = model::deleteFrameTemplate,
                    onSaveColorPreset = model::saveColorPreset,
                    onExportColorPreset = exportPreset,
                    onOpenColorSource = { url ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        catch (_: ActivityNotFoundException) { model.showError("没有找到可以打开网页的浏览器。") }
                    },
                    onSystemEdit = {
                        val selected = state.assets.filter { it.id in state.selectedIds }
                        if (selected.size == 1) editInSystem(selected.single()) else model.showError("系统编辑目前一次处理一张照片。")
                    }
                ), showAboutOnStart = intent.getBooleanExtra("open_about", false)
            )
        }
    }

    private fun openOutput(uri: Uri, model: LibraryViewModel) {
        lifecycleScope.launch {
            try {
                val mime = withContext(Dispatchers.IO) { contentResolver.getType(uri) ?: "application/octet-stream" }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("导出文件", uri)
                }
                startActivity(Intent.createChooser(intent, "打开导出文件"))
            } catch (_: ActivityNotFoundException) {
                model.showError("未找到可以打开这种文件格式的应用。")
            } catch (error: SecurityException) {
                model.showError("无法访问导出文件：${error.message}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                model.showError("无法打开导出文件：${error.message ?: "文件提供方暂不可用"}")
            }
        }
    }
}
