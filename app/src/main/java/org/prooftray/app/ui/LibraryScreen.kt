package org.prooftray.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.prooftray.app.domain.FilterSpec
import org.prooftray.app.domain.DownloadState
import org.prooftray.app.domain.DownloadVariant
import org.prooftray.app.domain.ExportVersions
import org.prooftray.app.domain.LibrarySection
import org.prooftray.app.domain.PhotoCollection
import org.prooftray.app.domain.canEdit
import org.prooftray.app.domain.PhotoEditTab
import org.prooftray.app.domain.ExportDialogDraft
import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.domain.hasConditions
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.selectedPhotos
import org.prooftray.app.domain.visiblePhotoIds

private fun PhotoAsset.deletable(): Boolean = downloadState == DownloadState.DOWNLOADED && uri.startsWith("content:")

@Composable
fun LibraryScreen(state: LibraryUiState, actions: LibraryActions, showAboutOnStart: Boolean = false) {
    // Only tasks the user just asked for are announced; restored journals and automatic downloads stay silent.
    val requested = remember { mutableStateOf(false) }
    val tracked = actions.copy(
        onExportPhotos = { ids, options -> requested.value = true; actions.onExportPhotos(ids, options) },
        onDownloadSelection = { requested.value = true; actions.onDownloadSelection() },
        onDownloadPhoto = { id -> requested.value = true; actions.onDownloadPhoto(id) })
    CompositionLocalProvider(LocalCameraPreviewRequest provides actions.onRequestPreview,
        LocalCameraSessionGeneration provides state.camera.generation, LocalPhotoGeometryUpdate provides actions.onPreviewGeometry) {
        ProofTrayTheme { LibraryContent(state, tracked, showAboutOnStart, requested) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryContent(state: LibraryUiState, actions: LibraryActions, showAboutOnStart: Boolean, requested: MutableState<Boolean>) {
    var tab by rememberSaveable(stateSaver = MainTabSaver) { mutableStateOf(MainTab.LIBRARY) }
    var taskExports by rememberSaveable { mutableStateOf(false) }
    var showingAbout by rememberSaveable { mutableStateOf(showAboutOnStart) }
    var showingFilters by remember { mutableStateOf(false) }
    var showingSaveFilter by remember { mutableStateOf(false) }
    var showingSavedFilters by remember { mutableStateOf(false) }
    val exportIds = state.exportIds
    var showingCamera by rememberSaveable { mutableStateOf(false) }
    var cameraPanel by rememberSaveable { mutableStateOf<CameraPanel?>(null) }
    var cameraRecoveryId by rememberSaveable { mutableStateOf<String?>(null) }
    var registering by rememberSaveable { mutableStateOf(false) }
    fun openCamera(panel: CameraPanel? = null, recoveryId: String? = null) {
        cameraPanel = panel
        cameraRecoveryId = recoveryId
        showingCamera = true
    }
    val view = LocalView.current
    val connectionBusy = cameraConnectionSummary(state).busy
    DisposableEffect(view, connectionBusy) {
        val previous = view.keepScreenOn
        view.keepScreenOn = previous || connectionBusy
        onDispose { view.keepScreenOn = previous }
    }
    var deleting by remember { mutableStateOf<List<PhotoAsset>?>(null) }
    var cleanup by remember { mutableStateOf<CatalogCleanup?>(null) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    val importing = state.browser.collection == PhotoCollection.CAMERA_IMPORT
    var showingColorLibrary by remember { mutableStateOf(false) }
    val editing = state.editor
    val snackbar = remember { SnackbarHostState() }
    val noticeScope = rememberCoroutineScope()
    var showingTags by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf<List<String>>(emptyList()) }
    var expandedGroupId by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    // Armed by the "选择" button before the first photo is picked; afterwards the selection itself keeps the mode.
    var selectMode by rememberSaveable { mutableStateOf(false) }
    val page = tab
    val exports = page == MainTab.LIBRARY && state.browser.librarySection == LibrarySection.EXPORTS
    val exportSource = state.browser.exportSourceId.takeIf { exports }
    val listName = if (exportSource != null) "这张照片的导出" else state.browser.librarySection.label
    fun browseLibrary(section: LibrarySection, source: String? = null) {
        previewId = null
        expandedGroupId = null
        selectMode = false
        tab = MainTab.LIBRARY
        actions.onSetLibrarySection(section, source)
    }
    fun navigate(destination: MainTab) {
        if (destination == MainTab.LIBRARY) { browseLibrary(LibrarySection.ORIGINALS); return }
        destination.collection?.let(actions.onSetCollection)
        selectMode = false
        tab = destination
    }
    LaunchedEffect(state.browser.collection) {
        if (tab.collection != null) {
            tab = if (state.browser.collection == PhotoCollection.LIBRARY) MainTab.LIBRARY else MainTab.IMPORT
        }
    }
    LaunchedEffect(state.selectedIds.isNotEmpty()) { if (state.selectedIds.isNotEmpty()) selectMode = false }
    val holder = rememberSaveableStateHolder()
    val originalOf = rememberOriginalState(state)
    val selected = remember(state.assets, state.selectedIds) { state.assets.filter { it.id in state.selectedIds } }
    val canEditSelection = selected.isNotEmpty() && selected.all { it.canEdit }
    fun openEditor(
        target: PhotoEditTab, preferred: PhotoAsset? = null, export: ExportDialogDraft? = null, targets: List<PhotoAsset>? = null
    ) {
        val photos = targets ?: preferred?.let { listOf(it) } ?: selected
        if (photos.isEmpty() || photos.any { !it.canEdit }) return
        val first = preferred ?: photos.firstOrNull { it.canEdit } ?: return
        previewId = null
        val look = state.look.takeIf { !it.usesLog || photos.all { photo -> photo.format == MediaFormat.RAW } } ?: ColorLookSettings()
        actions.onSetEditor(PhotoEditorState(photos.map { it.id }, first.id, target, look, state.frame, export, state.deletedFrameTemplateIds))
    }

    val tiles = remember(state.catalog) { state.catalog.groups.map { it.members.first() } }
    val visibleIds = remember(state.catalog) { state.catalog.visiblePhotoIds() }
    val visibleSelected = remember(state.catalog, state.selectedIds) { state.catalog.selectedPhotos(state.selectedIds) }
    val hiddenCount = selected.count { it.id !in visibleIds }
    val canDelete = selected.isNotEmpty() && selected.all { it.deletable() }
    val compareAssets = remember(comparing, state.assets) { comparing.mapNotNull { id -> state.assets.find { it.id == id } } }
    val cached = remember(state.assets) { state.assets.count { it.inImportList } }
    val filtering = state.filter.hasConditions()
    val hasPhotos = state.catalog.groups.isNotEmpty()
    val pendingTasks = state.tasks.count { it.status.taskView() == TaskView.ACTIVE } +
        state.camera.tasks.count { it.status.taskView() == TaskView.ACTIVE }
    val failedTasks = state.tasks.count { it.status.taskView() == TaskView.FAILED } +
        state.camera.tasks.count { it.status.taskView() == TaskView.FAILED }
    if (showingAbout) {
        AboutScreen(onBack = { showingAbout = false; navigate(MainTab.SETTINGS) })
    }
    val selecting = page.collection != null && (selected.isNotEmpty() || selectMode)
    val exitSelection = { selectMode = false; actions.onClearSelection() }
    BackHandler(enabled = page != MainTab.LIBRARY && !selecting && !showingAbout && !showingCamera) { navigate(MainTab.LIBRARY) }
    BackHandler(enabled = exportSource != null && !selecting && previewId == null && editing == null) {
        val source = state.assets.find { it.id == exportSource && !it.hiddenFromLibrary && it.downloadState == DownloadState.DOWNLOADED }
        browseLibrary(if (source != null) LibrarySection.ORIGINALS else LibrarySection.EXPORTS)
        previewId = source?.id
    }
    BackHandler(enabled = selecting && !showingAbout && !showingCamera, onBack = exitSelection)
    QueuedTaskNotice(state, requested, snackbar, announce = page != MainTab.TASKS) { exports ->
        taskExports = exports
        navigate(MainTab.TASKS)
    }
    if (!showingAbout) Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (page.collection != null) TopAppBar(
                navigationIcon = { if (selecting) IconButton(onClick = exitSelection) { AppIcon(AppGlyph.CLOSE, "清除选择") } },
                title = {
                    val count = visibleIds.size
                    Column {
                        when {
                            selecting -> {
                                Text(if (selected.isEmpty()) "选择照片" else "已选 ${selected.size} 张", fontWeight = FontWeight.SemiBold)
                                if (hiddenCount > 0) Text("含筛选外 $hiddenCount 张", style = MaterialTheme.typography.labelSmall)
                            }
                            hasPhotos || filtering -> Text("$count 张照片", style = MaterialTheme.typography.titleMedium)
                            else -> Text(page.title, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    if (selecting) {
                        TextButton(onClick = { cleanup = if (importing) CatalogCleanup.IMPORT_SELECTED else CatalogCleanup.LIBRARY_SELECTED },
                            enabled = selected.isNotEmpty()) { Text("移出列表") }
                        if (!importing || selected.any { it.deletable() }) TextButton(onClick = { deleting = selected.toList() }, enabled = canDelete,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除文件") }
                    } else {
                        if (hasPhotos) TextButton(onClick = { selectMode = true }) { Text("选择") }
                        if (hasPhotos || filtering) IconButton(onClick = { showingFilters = true }) {
                            BadgedBox(badge = { if (filtering) Badge() }) {
                                AppIcon(AppGlyph.FILTER, if (filtering) "调整已应用的筛选" else "筛选照片")
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { AppIcon(AppGlyph.MORE, "照片选项") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val grouped = state.catalog.groups.any { group -> group.members.any { it.stackId != null } }
                            val offline = importing && !state.camera.connected && cached > 0
                            if (grouped) DropdownMenuItem(text = { Text(if (state.collapseStacks) "展开分组" else "折叠分组") }, onClick = {
                                menu = false; actions.onSetCollapseStacks(!state.collapseStacks)
                            })
                            if (offline) DropdownMenuItem(
                                text = { Text(if (state.browser.showOfflinePreviews) "收起离线预览" else "查看 $cached 张离线预览") },
                                onClick = { menu = false; actions.onSetOfflinePreviews(!state.browser.showOfflinePreviews) })
                            if (grouped || offline) HorizontalDivider()
                            if (selecting) DropdownMenuItem(text = { Text("筛选照片") }, onClick = { menu = false; showingFilters = true })
                            DropdownMenuItem(text = { Text("已保存的筛选") }, onClick = { menu = false; showingSavedFilters = true })
                            DropdownMenuItem(text = { Text("保存当前筛选") }, enabled = filtering,
                                onClick = { menu = false; showingSaveFilter = true })
                            HorizontalDivider()
                            if (page == MainTab.LIBRARY) {
                                DropdownMenuItem(text = { Text("添加手机照片") }, onClick = { menu = false; actions.onImport() })
                            }
                            DropdownMenuItem(enabled = visibleIds.isNotEmpty(), text = { Text("全选当前结果") }, onClick = {
                                menu = false; actions.onSelectVisible()
                            })
                            if (page == MainTab.LIBRARY) DropdownMenuItem(text = { Text("调色与边框管理") },
                                onClick = { menu = false; showingColorLibrary = true })
                            HorizontalDivider()
                            DropdownMenuItem(text = {
                                Text(if (importing) "清空本次选片列表" else "清空${listName}列表", color = MaterialTheme.colorScheme.error)
                            }, onClick = {
                                menu = false
                                cleanup = if (importing) CatalogCleanup.IMPORT_LIST else CatalogCleanup.LIBRARY_ALL
                            })
                        }
                    }
                }
            ) else TopAppBar(title = { Text(page.title, style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            if (selecting) {
                Column {
                    if (!importing) EditSessionBar(state, actions, canEditSelection) { openEditor(it) }
                    SelectionBar(selected.size, visibleSelected, actions, importing, state.camera.connected, state.camera.catalogBatchSize,
                    state.settings.effectiveManualVariant, canGrade = canEditSelection, canUngroup = selected.any { it.stackId != null },
                    onPromote = if (exports) ({ actions.onPromoteExports(state.selectedIds) }) else null,
                    onCompare = { if (selected.size == 1) previewId = selected.single().id else comparing = selected.map { it.id } },
                    onExport = { actions.onSetExportSelection(selected.map { it.id }) },
                    onTags = { showingTags = true }, onGrade = { openEditor(PhotoEditTab.COLOR) },
                    onFrame = { openEditor(PhotoEditTab.FRAME) },
                    onCamera = { openCamera() })
                }
            } else NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                MainTab.entries.forEach { destination ->
                    NavigationBarItem(selected = page == destination, onClick = { navigate(destination) },
                        label = { Text(destination.title) }, icon = {
                        val tasks = destination == MainTab.TASKS
                        val retry = tasks && pendingTasks == 0 && failedTasks > 0
                        BadgedBox(badge = { if (tasks && pendingTasks > 0) Badge { Text(pendingTasks.toString()) } else if (retry) Badge() }) {
                            AppIcon(destination.glyph, if (retry) "$failedTasks 个任务需重试" else null)
                        }
                    })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LibraryErrorNotice(state, actions)
            holder.SaveableStateProvider(tab.name) {
                when (page) {
                    MainTab.LIBRARY, MainTab.IMPORT -> PhotoBrowserScreen(state, actions, selecting, originalOf,
                        onLibrarySection = { browseLibrary(it) },
                        onCamera = { openCamera(it) }, onRegister = { registering = true }, onFilters = { showingFilters = true },
                        onExpand = { expandedGroupId = it }, onPreview = { previewId = it })
                    MainTab.TASKS -> TransferScreen(state.transferState(), actions.transfers, taskExports) { taskExports = it }
                    MainTab.SETTINGS -> SettingsScreen(state, actions, onCamera = { openCamera() },
                        onAdd = { openCamera(CameraPanel.SEARCH, it) }, onTasks = { taskExports = false; navigate(MainTab.TASKS) },
                        onAbout = { showingAbout = true }, onManage = { showingColorLibrary = true })
                }
            }
        }
    }
    if (showingFilters) FilterDialog(state.filter, actions.onFilterChange) { showingFilters = false }
    if (showingSaveFilter) TextEntryDialog("保存筛选", "名称", onConfirm = actions.onSaveFilter) { showingSaveFilter = false }
    if (showingSavedFilters) SavedFiltersDialog(state, actions) { showingSavedFilters = false }
    if (showingTags) TextEntryDialog("编辑标签", "逗号分隔，将替换所选照片的标签", allowEmpty = true,
        onConfirm = { actions.onSetTags(it.split(',', '，').map(String::trim).filter(String::isNotEmpty).toSet()) }) { showingTags = false }
    exportIds?.let { ids ->
        val photos = ids.mapNotNull { id -> state.assets.find { it.id == id } }
        ExportDialog(photos, state.look, state.frame, state.settings.exportPreferences,
            onChange = { target, choices -> actions.onSetExportSelection(null); openEditor(target, export = choices, targets = photos) },
            onExport = { actions.onExportPhotos(ids.toSet(), it) }, onDismiss = { actions.onSetExportSelection(null) })
    }
    if (showingColorLibrary) ColorManagementScreen(state, actions) { showingColorLibrary = false }
    state.assets.find { it.id == previewId }?.let { photo ->
        val versions = remember(state.assets) { ExportVersions(state.assets) }
        val groupPhotos = state.catalog.groups.find { it.id == expandedGroupId }?.members?.takeIf { members -> members.any { it.id == photo.id } }
        val viewing = groupPhotos ?: if (tiles.any { it.id == photo.id }) tiles else listOf(photo)
        PhotoViewer(viewing, photo.id, state.selectedIds, actions.onToggleSelection,
            detailOf = { originalOf(it).detail }, importing = importing,
            canImport = { state.camera.connected || (state.settings.effectiveManualVariant == DownloadVariant.ORIGINAL &&
                it.downloadState == DownloadState.DOWNLOADED) },
            onRate = { photo, rating -> actions.onRatePhoto(photo.id, rating) },
            onPick = { photo, pick -> actions.onPickPhoto(photo.id, pick) },
            onExport = { actions.onSetExportSelection(listOf(it.id)); previewId = null }, onImport = { actions.onDownloadPhoto(it.id) },
            onCamera = { previewId = null; openCamera() }, onCompare = { comparing = selected.map { it.id } },
            versionCount = { asset -> if (page == MainTab.LIBRARY && asset.derivedFromId == null) versions.forSource(asset.id).size else 0 },
            onShowVersions = { browseLibrary(LibrarySection.EXPORTS, it.id) },
            onPromote = if (exports) ({ asset -> actions.onPromoteExports(setOf(asset.id)); previewId = null }) else null,
            canEdit = { page == MainTab.LIBRARY && it.canEdit }, onEdit = { asset ->
                openEditor(PhotoEditTab.COLOR, asset)
            }, onFrame = { asset ->
                openEditor(PhotoEditTab.FRAME, asset)
            }, onDismiss = { previewId = null })
    }
    if (compareAssets.size in 2..4) CompareDialog(compareAssets, state.selectedIds, actions.onToggleSelection) { comparing = emptyList() }
    editing?.let { request ->
        val photos = request.ids.mapNotNull { id -> state.assets.find { it.id == id } }
        if (photos.isEmpty()) LaunchedEffect(request) {
            actions.onSetEditor(null)
            noticeScope.launch { snackbar.showSnackbar("照片已移除，编辑已关闭") }
        } else key(request) { PhotoEditScreen(photos, request, state, actions) { actions.onSetEditor(null) } }
    }
    cleanup?.let { action ->
        CatalogCleanupDialog(action, listName, onConfirm = {
            if (action == CatalogCleanup.IMPORT_LIST) actions.onClearImportList()
            else if (action == CatalogCleanup.IMPORT_SELECTED) actions.onRemoveFromImport()
            else actions.onRemoveFromLibrary(action == CatalogCleanup.LIBRARY_ALL)
            cleanup = null
        }) { cleanup = null }
    }
    deleting?.let { assets ->
        DeletePhotosDialog(assets, onConfirm = { actions.onDeletePhotos(assets); deleting = null }) { deleting = null }
    }
    if (previewId == null && editing == null && exportIds == null && comparing.isEmpty()) {
        state.catalog.groups.find { it.id == expandedGroupId }?.let { group ->
            GroupDialog(group, state.selectedIds, actions.onToggleSelection, originalOf, onPreview = { previewId = it }) { expandedGroupId = null }
        }
    }
    if (showingCamera) CameraConnectionPage(state, actions, cameraPanel, cameraRecoveryId,
        onBrowse = { showingCamera = false; navigate(MainTab.IMPORT) }, onRegister = { registering = true },
        onDismiss = { showingCamera = false; actions.onStopBleScan() })
    CameraRegistrationDialogs(state, actions, registering) { registering = false }

}

/** Confirms tasks queued after a user request; [onOpen] receives whether the export list should be shown. */
@Composable
private fun QueuedTaskNotice(
    state: LibraryUiState, requested: MutableState<Boolean>, snackbar: SnackbarHostState, announce: Boolean, onOpen: (Boolean) -> Unit
) {
    val exports = remember(state.tasks) {
        state.tasks.filter { it.status.taskView() == TaskView.ACTIVE }.mapTo(hashSetOf()) { it.id }
    }
    val imports = remember(state.camera.tasks) {
        state.camera.tasks.filter { !it.automatic && it.status.taskView() == TaskView.ACTIVE }
            .mapTo(hashSetOf()) { it.id }
    }
    var seen by remember { mutableStateOf(exports + imports) }
    val visible by rememberUpdatedState(announce)
    val open by rememberUpdatedState(onOpen)
    // Shown outside the effect: a quick task finishing must not cancel its own confirmation.
    val scope = rememberCoroutineScope()
    LaunchedEffect(exports, imports) {
        val addedImports = (imports - seen).size
        val addedExports = (exports - seen).size
        seen = seen + exports + imports
        if (addedImports + addedExports == 0 || !requested.value) return@LaunchedEffect
        requested.value = false
        if (!visible) return@LaunchedEffect
        val added = listOfNotNull("导入 $addedImports 张".takeIf { addedImports > 0 }, "导出 $addedExports 张".takeIf { addedExports > 0 })
        scope.launch {
            if (snackbar.showSnackbar("已加入任务：${added.joinToString("，")}", "查看") == SnackbarResult.ActionPerformed) {
                open(addedImports == 0)
            }
        }
    }
}
