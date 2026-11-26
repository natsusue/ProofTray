package org.prooftray.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.GradeWheel
import org.prooftray.app.domain.HslBand

internal val LocalColorGesture = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
internal fun AdvancedColorControls(tab: Int, look: ColorLookSettings, onChange: (ColorLookSettings) -> Unit,
    curveHistogram: @Composable (Boolean) -> RgbHistogram?) {
    val change: (ColorLookSettings) -> Unit = { onChange(it.copy(name = "自定义")) }
    when (tab) {
        1 -> BasicColorControls(look, change)
        2 -> CurveColorControls(look.adjustments.curves, curveHistogram) { change(look.copy(adjustments = look.adjustments.copy(curves = it))) }
        3 -> HslColorControls(look, change)
        4 -> GradingColorControls(look, change)
    }
}

@Composable
private fun BasicColorControls(look: ColorLookSettings, change: (ColorLookSettings) -> Unit) {
    var group by rememberSaveable { mutableStateOf(0) }
    ChoiceChips(listOf("明暗", "色彩"), group) { group = it }
    val e = look.adjustments
    if (group == 0) {
        ColorSlider("曝光", look.exposure, -3f..3f, 0f, ev = true) { change(look.copy(exposure = it)) }
        ColorSlider("对比度", look.contrast) { change(look.copy(contrast = it)) }
        ColorSlider("高光", e.highlights) { change(look.copy(adjustments = e.copy(highlights = it))) }
        ColorSlider("阴影", e.shadows) { change(look.copy(adjustments = e.copy(shadows = it))) }
        ColorSlider("白色色阶", e.whites) { change(look.copy(adjustments = e.copy(whites = it))) }
        ColorSlider("黑色色阶", e.blacks) { change(look.copy(adjustments = e.copy(blacks = it))) }
        TextButton(onClick = { change(look.copy(exposure = 0f, contrast = 0f,
            adjustments = e.copy(highlights = 0f, shadows = 0f, whites = 0f, blacks = 0f))) }, modifier = Modifier.padding(start = 8.dp)) {
            Text("重置明暗")
        }
    } else {
        ColorSlider("冷暖", look.warmth, colors = listOf(Color(0xFF6098DA), Color(0xFFDEDCD6), Color(0xFFE6B959))) {
            change(look.copy(warmth = it))
        }
        ColorSlider("色调", look.tint, colors = listOf(Color(0xFF6CBF8B), Color(0xFFDEDCD6), Color(0xFFC975B8))) {
            change(look.copy(tint = it))
        }
        ColorSlider("鲜艳度", e.vibrance) { change(look.copy(adjustments = e.copy(vibrance = it))) }
        ColorSlider("饱和度", look.saturation, 0f..2f, 1f) { change(look.copy(saturation = it)) }
        TextButton(onClick = { change(look.copy(warmth = 0f, tint = 0f, saturation = 1f, adjustments = e.copy(vibrance = 0f))) },
            modifier = Modifier.padding(start = 8.dp)) { Text("重置色彩") }
    }
}

@Composable
private fun HslColorControls(look: ColorLookSettings, change: (ColorLookSettings) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(0) }
    HslBandSelector(selected, look.adjustments.hsl.map { it != HslBand() }) { selected = it }
    val band = look.adjustments.hsl[selected]
    val swatch = hslSwatches[selected]
    val hue = (swatch.hue + band.hue * 30f + 360f) % 360f
    fun update(value: HslBand) {
        change(look.copy(adjustments = look.adjustments.copy(hsl = look.adjustments.hsl.mapIndexed { i, old ->
            if (i == selected) value else old
        })))
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(swatch.color, CircleShape))
                Text("${swatch.name}色", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { update(HslBand()) }, enabled = band != HslBand()) { Text("重置") }
            }
            val hues = List(7) { Color.hsl((swatch.hue - 30f + it * 10f + 360f) % 360f, .8f, .55f) }
            EditorSlider("色相", band.hue, -1f..1f, "${(band.hue * 30).roundToInt()}°",
                onReset = if (band.hue != 0f) ({ update(band.copy(hue = 0f)) }) else null, trackColors = hues) {
                update(band.copy(hue = it))
            }
            ColorSlider("饱和度", band.saturation,
                colors = listOf(Color.hsl(hue, 0f, .55f), Color.hsl(hue, .55f, .55f), Color.hsl(hue, 1f, .55f))) {
                update(band.copy(saturation = it))
            }
            ColorSlider("明亮度", band.luminance,
                colors = listOf(Color.hsl(hue, .55f, .12f), Color.hsl(hue, .7f, .5f), Color.hsl(hue, .3f, .94f))) {
                update(band.copy(luminance = it))
            }
        }
    }
}

@Composable
private fun GradingColorControls(look: ColorLookSettings, change: (ColorLookSettings) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(0) }
    val e = look.adjustments
    val markers = e.grading.mapIndexed { index, value ->
        if (value.saturation > 0f) Color.hsl(value.hue % 360f, value.saturation, .55f)
        else listOf(Color(0xFF555555), Color(0xFF999999), Color(0xFFDDDDDD), MaterialTheme.colorScheme.onSurfaceVariant)[index]
    }
    ChoiceChips(listOf("阴影", "中间调", "高光", "全局"), selected, markers) { selected = it }
    val wheel = e.grading[selected]
    val gesture = LocalColorGesture.current
    fun update(value: GradeWheel) {
        change(look.copy(adjustments = e.withGradingWheel(selected, value)))
    }
    if (selected != 3 && (e.grading[3].saturation != 0f || e.grading[3].luminance != 0f)) {
        Text("全局分级也在生效", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        GradeColorWheel(wheel) { hue, saturation -> update(wheel.copy(hue = hue, saturation = saturation)) }
    }
    EditorSlider("色相", wheel.hue, 0f..360f, "${wheel.hue.roundToInt()}°",
        onReset = if (wheel.hue != 0f) ({ update(wheel.copy(hue = 0f)) }) else null,
        trackColors = List(7) { Color.hsl((it * 60f) % 360f, .8f, .55f) }) {
        update(wheel.copy(hue = it))
    }
    ColorSlider("饱和度", wheel.saturation, 0f..1f,
        colors = listOf(Color(0xFFCBCBCB), Color.hsl(wheel.hue % 360f, 1f, .55f))) { update(wheel.copy(saturation = it)) }
    ColorSlider("亮度", wheel.luminance, colors = listOf(Color(0xFF303030), Color(0xFF999999), Color(0xFFF0F0F0))) {
        update(wheel.copy(luminance = it))
    }
    Row(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { gesture(false); update(GradeWheel()) }, enabled = wheel != GradeWheel()) { Text("重置当前") }
        TextButton(onClick = { gesture(false); change(look.copy(adjustments = e.resetGrading())) },
            enabled = e.grading.any { it != GradeWheel() } || e.blending != .5f || e.balance != 0f) { Text("重置全部分级") }
    }
    if (selected != 3) {
        ColorSlider("混合", e.blending, 0f..1f, .5f) { change(look.copy(adjustments = e.copy(blending = it))) }
        ColorSlider("平衡 · 阴影—高光", e.balance) { change(look.copy(adjustments = e.copy(balance = it))) }
    }
}

@Composable
internal fun ColorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float> = -1f..1f,
    neutral: Float = 0f, ev: Boolean = false, colors: List<Color>? = null, change: (Float) -> Unit) {
    val offset = if (range.start == 0f && range.endInclusive == 1f) 0f else neutral
    val display = if (ev) String.format(Locale.ROOT, "%+.1f EV", value) else "${((value - offset) * 100).roundToInt()}"
    EditorSlider(label, value, range, display, onReset = if (value != neutral) ({ change(neutral) }) else null,
        trackColors = colors, neutral = neutral, onChange = change)
}

@Composable
private fun GradeColorWheel(wheel: GradeWheel, onChange: (Float, Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val gesture = LocalColorGesture.current
    DisposableEffect(Unit) { onDispose { gesture(false) } }
    val colors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
    Canvas(Modifier.size(176.dp)
        .pointerInput(Unit) {
            fun update(position: Offset) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val delta = position - center
                val hue = ((atan2(delta.y, delta.x) * 180f / PI.toFloat()) + 360f) % 360f
                change(hue, (delta.getDistance() / (minOf(size.width, size.height) / 2f)).coerceIn(0f, 1f))
            }
            detectDragGestures(onDragStart = { gesture(true); update(it) }, onDragEnd = { gesture(false) },
                onDragCancel = { gesture(false) }) { event, _ -> event.consume(); update(event.position) }
        }.pointerInput(Unit) {
            detectTapGestures { point ->
                val delta = point - Offset(size.width / 2f, size.height / 2f)
                gesture(true)
                change((atan2(delta.y, delta.x) * 180f / PI.toFloat() + 360f) % 360f,
                    (delta.getDistance() / (minOf(size.width, size.height) / 2f)).coerceIn(0f, 1f))
                gesture(false)
            }
        }) {
        val radius = size.minDimension / 2f
        drawCircle(Brush.sweepGradient(colors, center), radius)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, radius), radius)
        val angle = wheel.hue / 180f * PI.toFloat()
        val point = center + Offset(cos(angle), sin(angle)) * (radius * wheel.saturation)
        drawCircle(Color.Black, 7.dp.toPx(), point, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 7.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
    }
}
