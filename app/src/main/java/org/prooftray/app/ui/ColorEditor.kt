package org.prooftray.app.ui

import android.app.ActivityManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.prooftray.app.color.GlColorView
import org.prooftray.app.data.LinearRawPreview
import org.prooftray.app.color.LutRepository
import org.prooftray.app.color.PreviewColorTable
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.DEFAULT_LUT_STRENGTH
import org.prooftray.app.domain.PhotoAsset
import kotlin.math.roundToInt

private data class ColorPreviewLut(
    val table: PreviewColorTable? = null, val error: String? = null, val loading: Boolean = false, val recipe: ColorLookSettings? = null
)

private data class LookOption(val key: String, val name: String, val look: ColorLookSettings)

private const val ORIGINAL_KEY = "original"
private const val THUMBNAIL_EDGE = 192f

/**
 * Grading panel inside PhotoEditScreen. The parent owns the draft, decode cache and export flow.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColorEditor(
    asset: PhotoAsset, state: LibraryUiState, actions: LibraryActions, look: ColorLookSettings, onLookChange: (ColorLookSettings) -> Unit,
    selectedLookKey: String?, onLookKeyChange: (String) -> Unit, onImport: () -> Unit,
    photos: PhotoEditSource, graded: ColorPreviewSource, allowLog: Boolean, onReady: (Boolean) -> Unit, onRetry: () -> Unit,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, onGesture: (Boolean) -> Unit,
    header: PhotoEditHeaderContent, status: @Composable () -> Unit, pager: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val controlsHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    val repository = remember(context) { LutRepository.get(context) }
    val options = remember(state.colorPresets, state.luts) {
        listOf(LookOption(ORIGINAL_KEY, "无 LUT", ColorLookSettings())) +
            state.luts.filter { it.defaultUsage.unavailableReason == null }.map { lut ->
                lut.look().let { LookOption(checkNotNull(it.lutKey), lut.name, it) }
            } + state.colorPresets.filter { it.look.usage.unavailableReason == null }.map { LookOption("preset:${it.id}", it.name, it.look) }
    }
    val lookKey = selectedLookKey ?: options.firstOrNull { it.look == look }?.key ?: look.lutKey ?: ORIGINAL_KEY
    var tab by rememberSaveable { mutableStateOf(0) }
    var showHistogram by rememberSaveable { mutableStateOf(true) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var original by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var gpuFailed by remember { mutableStateOf(false) }
    var glView by remember { mutableStateOf<GlColorView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val supportsGpu = remember { context.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion >= 0x30000 }
    val linear = photos.linear
    val normal = photos.normal
    val raw = photos.raw
    val glLeases = remember { mutableMapOf<GlColorView, EditBitmap.Lease>() }
    val logLook = if (original || holding) ColorLookSettings() else look
    val source = if (raw) {
        if (linear.error != null) ColorPreviewSource(error = linear.error)
        else if (original || holding) {
            normal.copy(renderedLook = logLook)
        } else graded
    } else normal
    val thumbBase = normal.image
    val thumbnail = rememberEditResult(ColorPreviewSource(), thumbBase) {
        val image = thumbBase ?: return@rememberEditResult ColorPreviewSource()
        withContext(editRenderDispatcher) {
            image.retain().use { lease ->
                val bitmap = lease.bitmap
                val scale = THUMBNAIL_EDGE / maxOf(bitmap.width, bitmap.height)
                ColorPreviewSource(if (scale >= 1f) hold(image) else own(Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)))
            }
        }
    }
    val liveLook by rememberUpdatedState(look)
    val lut by produceState(ColorPreviewLut(), raw) {
        // Keep one bake in flight and then render the newest recipe; continuous drags must not wait for a debounce pause.
        snapshotFlow { liveLook.copy(name = "") }.conflate().collect { recipe ->
            if (recipe.isIdentity || recipe.usesLog || raw) {
                value = ColorPreviewLut(recipe = recipe)
                return@collect
            }
            value = value.copy(loading = true, error = null)
            value = withContext(Dispatchers.IO) {
                try {
                    ColorPreviewLut(table = repository.previewData(recipe), recipe = recipe)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: OutOfMemoryError) { ColorPreviewLut(error = "内存不足，无法载入 LUT", recipe = recipe) }
                catch (error: Exception) { ColorPreviewLut(error = error.message ?: "无法载入 LUT", recipe = recipe) }
            }
        }
    }
    val displayedLook = if (original || holding || lut.error != null) ColorLookSettings() else look
    val displayTable = lut.table.takeUnless { displayedLook.isIdentity }
    val compatible = !raw && !look.usesLog && (gpuFailed || !supportsGpu)
    val cpu = rememberEditResult(ColorPreviewSource(), source.image, displayedLook, compatible) {
        val image = source.image ?: return@rememberEditResult ColorPreviewSource()
        if (!compatible) return@rememberEditResult ColorPreviewSource()
        delay(100)
        renderBitmapLook(image, displayedLook, repository)
    }
    val bitmap = rememberEditBitmap(source.image)
    val cpuBitmap = rememberEditBitmap(cpu.image)
    val histogram = if (showHistogram && previewSize != IntSize.Zero) key(original || holding) {
        when {
            raw -> rememberColorHistogram(source.image, repository, viewport = previewSize)
            compatible -> rememberColorHistogram(cpu.image, repository, viewport = previewSize)
            else -> rememberColorHistogram(source.image, repository, table = displayTable, viewport = previewSize)
        }
    } else null
    DisposableEffect(lifecycle, glView) {
        val view = glView
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) view?.onPause()
            if (event == Lifecycle.Event.ON_RESUME) view?.onResume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view?.onPause() }
    }
    val tableReady = look.isIdentity || lut.recipe == look.copy(name = "")
    val ready = if (raw) (!look.usesLog || allowLog) && source.renderedLook == logLook && source.bitmap != null && source.error == null
        else source.bitmap != null && source.error == null && lut.error == null && !lut.loading && cpu.error == null &&
            tableReady && (!compatible || (cpu.image != null && cpu.renderedLook == displayedLook))
    val savable = look.copy(name = "", strength = 1f) != ColorLookSettings(name = "") &&
        state.colorPresets.none { it.look.copy(name = look.name) == look }
    SideEffect { onReady(ready) }
    CompositionLocalProvider(LocalColorGesture provides onGesture) {
    Column(Modifier.fillMaxSize()) {
        header { _ -> }
        status()
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { previewSize = it }.background(Color(0xFF1F1F1F))
            .pointerInput(Unit) { detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false }) },
            contentAlignment = Alignment.Center) {
            when {
                source.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(source.error.orEmpty(), color = Color.White, modifier = Modifier.padding(24.dp))
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                bitmap == null -> LoadingIndicator(Modifier.size(56.dp))
                raw -> Image(bitmap.asImageBitmap(), asset.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                compatible -> cpuBitmap?.let { Image(it.asImageBitmap(), asset.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                else -> AndroidView(
                    factory = { GlColorView(it) { gpuFailed = true }.also { view ->
                        glView = view; glLeases[view] = checkNotNull(source.image).retain()
                    } },
                    modifier = Modifier.fillMaxSize(),
                    update = { it.update(bitmap, displayTable?.data) },
                    onReset = null,
                    onRelease = { it.releasePreview(); glLeases.remove(it)?.close(); if (glView === it) glView = null }
                )
            }
            if (lut.loading) LoadingIndicator(Modifier.size(48.dp))
            if (histogram != null) PreviewHistogram(histogram, Modifier.align(Alignment.TopStart).padding(8.dp))
            FilterChip(selected = original, onClick = { original = !original }, label = { Text("原图") },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            pager()
        }
        Column(Modifier.heightIn(max = controlsHeight).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.errorMessage?.let { message ->
                ErrorNotice(message, Modifier.padding(horizontal = 20.dp)) {
                    IconButton(onClick = actions.onDismissError) { AppIcon(AppGlyph.CLOSE, "关闭提示") }
                }
            }
            (lut.error ?: cpu.error)?.let { message ->
                ErrorNotice(message, Modifier.padding(horizontal = 20.dp)) { TextButton(onClick = onRetry) { Text("重试") } }
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val sourceLabel = when {
                    !raw -> "照片调色"
                    look.usesLog -> "RAW · Log"
                    else -> "RAW · 标准"
                }
                Text(sourceLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(selected = showHistogram, onClick = { showHistogram = !showHistogram }, label = { Text("预览直方图") })
                IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(40.dp)) { AppIcon(AppGlyph.UNDO, "撤销调色") }
                IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(40.dp)) { AppIcon(AppGlyph.REDO, "重做调色") }
            }
            ColorToolTabs(tab) { onGesture(false); tab = it }
            if (!allowLog && options.any { it.look.usesLog }) Text("所选照片含非 RAW，Log LUT 不可用",
                Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
            if (tab == 0) {
                @Composable
                fun OptionTile(option: LookOption) = LookTile(option.name, lookKey == option.key, onClick = {
                    val selected = when {
                        option.key == ORIGINAL_KEY -> look.withoutLut()
                        option.key.startsWith("lut:") -> look.withLut(option.look)
                        else -> option.look
                    }
                    onLookChange(selected)
                    onLookKeyChange(option.key)
                }, enabled = allowLog || !option.look.usesLog) { LookThumbnail(thumbnail.image, linear.thumb, option.look, repository) }
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = ORIGINAL_KEY) { OptionTile(options.first()) }
                    item(key = "import") {
                        LookTile(if (state.colorBusy) "正在导入" else "导入 LUT", false,
                            onClick = { onImport() }, enabled = !state.colorBusy) {
                            if (state.colorBusy) LoadingIndicator(Modifier.size(32.dp)) else AppIcon(AppGlyph.ADD)
                        }
                    }
                    items(options.drop(1), key = { it.key }) { OptionTile(it) }
                    item(key = "manage") { LookTile("管理", false, onClick = { library = true }) { AppIcon(AppGlyph.MORE) } }
                }
                if (state.luts.isEmpty()) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(".cube 或 HaldCLUT PNG", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { library = true }) { Text("获取 LUT") }
                }
                val lutId = look.lutId
                if (lutId != null) {
                    val warning = state.luts.firstOrNull { it.id == lutId }?.domainWarning(look.usage)
                    LutUsageControls(look, allowLog, warning, onGainChange = { onLookChange(look.copy(logGainEv = it)) }) { usage ->
                        val next = look.withUsage(usage)
                        onLookChange(next)
                        if (lookKey == look.lutKey) {
                            onLookKeyChange(next.lutKey ?: ORIGINAL_KEY)
                            actions.onSetLutDefault(lutId, usage)
                        }
                    }
                    val resetStrength = { onLookChange(look.copy(strength = DEFAULT_LUT_STRENGTH)) }
                    EditorSlider("LUT 强度", look.strength, 0f..1f, "${(look.strength * 100).roundToInt()}%",
                        onReset = resetStrength.takeIf { look.strength != DEFAULT_LUT_STRENGTH }) {
                        onLookChange(look.copy(strength = it))
                    }
                }
            } else {
                AdvancedColorControls(tab, look, onLookChange) { singleChannel ->
                    // The GPU samples the fitted texture before the curve. CPU and RAW develop at bitmap resolution.
                    rememberCurveInputHistogram(photos, look, singleChannel, repository, if (raw || compatible) IntSize.Zero else previewSize)
                }
            }
            if (savable) TextButton(onClick = { saving = true }, enabled = ready, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("存为预设")
            }
        }
    }
    }
    if (library) ColorLibrarySheet(state, actions, allowLog = allowLog, onPick = { picked ->
        val key = options.firstOrNull { it.look == picked }?.key ?: picked.lutKey ?: ORIGINAL_KEY
        onLookChange(if (key.startsWith("lut:")) look.withLut(picked) else picked)
        onLookKeyChange(key)
    }) { library = false }
    if (saving) AlertDialog(onDismissRequest = { saving = false }, title = { Text("存为预设") },
        text = { OutlinedTextField(presetName, { presetName = it }, label = { Text("预设名称") }, singleLine = true) },
        confirmButton = { TextButton(enabled = presetName.isNotBlank(), onClick = {
            actions.onSaveColorPreset(presetName, look); saving = false
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { saving = false }) { Text("取消") } })
}

@Composable
private fun LookTile(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Column(Modifier.width(72.dp).clickable(enabled = enabled, onClick = onClick).semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(72.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
            contentAlignment = Alignment.Center) { content() }
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The look rendered on a small copy of the photo being edited, so styles are chosen by result instead of by name. */
@Composable
private fun LookThumbnail(base: EditBitmap?, linear: LinearRawPreview?, look: ColorLookSettings, repository: LutRepository) {
    if (linear != null) {
        val source = linearLookPreview(linear, look, repository)
        rememberEditBitmap(source.image)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        return
    }
    if (look.usesLog) { AppIcon(AppGlyph.PHOTOS); return }
    val rendered = rememberEditResult(ColorPreviewSource(), base, look) {
        val image = base ?: return@rememberEditResult ColorPreviewSource()
        renderBitmapLook(image, look, repository)
    }
    rememberEditBitmap(rendered.image)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}
