package org.prooftray.app.frame

import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import org.prooftray.app.domain.flatten
import org.prooftray.app.domain.FrameAlign
import org.prooftray.app.domain.FrameAspect
import org.prooftray.app.domain.FrameCondition
import org.prooftray.app.domain.FrameDesign
import org.prooftray.app.domain.FrameFlow
import org.prooftray.app.domain.FrameLayer
import org.prooftray.app.domain.FrameLayerKind
import org.prooftray.app.domain.FrameRegion
import org.prooftray.app.domain.FrameTemplate
import org.prooftray.app.domain.MAX_ARGB_8888_PIXELS

internal const val FRAME_UNIT = 1000f
internal data class FrameSize(val width: Float, val height: Float)
internal data class FrameOutputSize(val width: Int, val height: Int)
internal data class FrameRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/** Affine transform shared by rendering, hit-testing and gesture conversion; coordinates stay in design space. */
internal data class FrameMatrix(
    val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f, val tx: Float = 0f, val ty: Float = 0f
) {
    fun x(x: Float, y: Float): Float = a * x + c * y + tx
    fun y(x: Float, y: Float): Float = b * x + d * y + ty
    fun then(local: FrameMatrix): FrameMatrix = FrameMatrix(a * local.a + c * local.b, b * local.a + d * local.b,
        a * local.c + c * local.d, b * local.c + d * local.d, x(local.tx, local.ty), y(local.tx, local.ty))
    fun inverse(): FrameMatrix {
        val det = a * d - b * c
        require(det.isFinite() && kotlin.math.abs(det) > 0.000001f) { "图层变换无效" }
        return FrameMatrix(d / det, -b / det, -c / det, a / det, (c * ty - d * tx) / det, (b * tx - a * ty) / det)
    }
    companion object {
        fun translation(x: Float, y: Float) = FrameMatrix(tx = x, ty = y)
        fun rotation(degrees: Float, scale: Float): FrameMatrix {
            val angle = Math.toRadians(degrees.toDouble())
            return FrameMatrix(cos(angle).toFloat() * scale, sin(angle).toFloat() * scale,
                -sin(angle).toFloat() * scale, cos(angle).toFloat() * scale)
        }
    }
}

internal data class FrameGeometry(private val photoShortEdge: Int, val canvas: FrameRect, val photo: FrameRect) {
    fun outputSize(maxEdge: Int): FrameOutputSize {
        val sourceScale = photoShortEdge / FRAME_UNIT
        val fullWidth = canvas.width * sourceScale
        val fullHeight = canvas.height * sourceScale
        val scale = if (maxEdge > 0) min(1f, maxEdge / max(fullWidth, fullHeight)) else 1f
        val width = max(1, (fullWidth * scale).roundToInt())
        val height = max(1, (fullHeight * scale).roundToInt())
        require(width <= 32768 && height <= 32768 && width.toLong() * height <= MAX_ARGB_8888_PIXELS) {
            "含边框的尺寸过大，请减小导出长边"
        }
        return FrameOutputSize(width, height)
    }

    fun region(region: FrameRegion): FrameRect = when (region) {
        FrameRegion.CANVAS -> canvas
        FrameRegion.PHOTO -> photo
        FrameRegion.TOP -> FrameRect(photo.left, 0f, photo.width, max(0f, photo.top))
        FrameRegion.BOTTOM -> FrameRect(photo.left, photo.bottom, photo.width, max(0f, canvas.bottom - photo.bottom))
        FrameRegion.LEFT -> FrameRect(0f, photo.top, max(0f, photo.left), photo.height)
        FrameRegion.RIGHT -> FrameRect(photo.right, photo.top, max(0f, canvas.right - photo.right), photo.height)
    }

    companion object {
        fun create(width: Int, height: Int, design: FrameDesign): FrameGeometry {
            require(width > 0 && height > 0) { "照片尺寸无效" }
            val shortEdge = min(width, height)
            val unit = FRAME_UNIT / shortEdge
            val w = width * unit; val h = height * unit
            val p = design.canvas.insets
            val baseW = w + (p.left + p.right) * FRAME_UNIT
            val baseH = h + (p.top + p.bottom) * FRAME_UNIT
            val ratio = if (design.canvas.aspect == FrameAspect.PHOTO) w / h else design.canvas.aspect.ratio
            val canvasW = if (ratio == null) baseW else max(baseW, baseH * ratio)
            val canvasH = if (ratio == null) baseH else max(baseH, baseW / ratio)
            val photoW = w * design.photo.scale; val photoH = h * design.photo.scale
            return FrameGeometry(shortEdge, FrameRect(0f, 0f, canvasW, canvasH), FrameRect(
                (canvasW - baseW + w - photoW) / 2 + (p.left + design.photo.x) * FRAME_UNIT,
                (canvasH - baseH + h - photoH) / 2 + (p.top + design.photo.y) * FRAME_UNIT, photoW, photoH))
        }
    }
}

internal data class FramePlacement(
    val layer: FrameLayer, val width: Float, val height: Float, val matrix: FrameMatrix, val parent: FrameMatrix,
    val opacity: Float, val children: List<FramePlacement> = emptyList(), val locked: Boolean = layer.locked
) {
    fun flatten(): List<FramePlacement> = listOf(this) + children.flatMap { it.flatten() }
    fun hit(x: Float, y: Float, tolerance: Float): Boolean {
        val local = matrix.inverse()
        val padding = tolerance / max(0.0001f, kotlin.math.hypot(matrix.a, matrix.b))
        return FrameRect(-padding, -padding, width + padding * 2, height + padding * 2).contains(local.x(x, y), local.y(x, y))
    }
}

/** Intrinsic sizes come from the same text renderer used for both preview and export. */
internal class FrameLayout(
    private val geometry: FrameGeometry, private val template: FrameTemplate, private val values: Map<String, String>,
    private val hasLogo: Boolean, private val measureText: (FrameLayer, Float) -> FrameSize
) {
    private data class Measured(val layer: FrameLayer, val width: Float, val height: Float, val children: List<Measured> = emptyList())

    fun place(layers: List<FrameLayer>): List<FramePlacement> = layers.sortedBy { it.z }.mapNotNull { layer ->
        val region = geometry.region(layer.region)
        if (region.width < 0.1f || region.height < 0.1f) return@mapNotNull null
        val angle = Math.toRadians(layer.rotation.toDouble())
        val availableW = abs(cos(angle)).toFloat() * region.width + abs(sin(angle)).toFloat() * region.height
        val availableH = abs(sin(angle)).toFloat() * region.width + abs(cos(angle)).toFloat() * region.height
        measure(layer, availableW, availableH)?.let { measured ->
            val anchor = FrameMatrix.translation(region.left + region.width * layer.at.x, region.top + region.height * layer.at.y)
            place(measured, FrameMatrix(), anchor.tx - layer.pivot.x * measured.width,
                anchor.ty - layer.pivot.y * measured.height, 1f)
        }
    }

    private fun visible(layer: FrameLayer): Boolean = layer.visible && (!layer.requiresLogo || hasLogo) &&
        (!layer.autoLogo || hasLogo || layer.imageId != null) && (layer.condition?.let(::condition) ?: true)

    private fun condition(c: FrameCondition): Boolean = when (c.op) {
        "hasLogo" -> hasLogo
        "present" -> when (c.field) {
            "custom" -> template.custom.isNotBlank()
            "time" -> template.showTime && !values["time"].isNullOrBlank()
            "gps" -> template.showGps && !values["gps"].isNullOrBlank()
            else -> !values[c.field].isNullOrBlank()
        }
        "eq" -> c.value == when (c.param) {
            "showTime" -> template.showTime.toString(); "showGps" -> template.showGps.toString(); "custom" -> template.custom; else -> ""
        }
        "all" -> c.conditions.all(::condition)
        "any" -> c.conditions.any(::condition)
        else -> false
    }

    private fun measure(layer: FrameLayer, availableWidth: Float, availableHeight: Float): Measured? {
        if (!visible(layer)) return null
        val limit = min(layer.maxWidth * FRAME_UNIT, max(1f, availableWidth))
        val fixedW = if (layer.fillWidth) availableWidth else layer.width?.times(FRAME_UNIT)
        val fixedH = if (layer.fillHeight) availableHeight else layer.height?.times(FRAME_UNIT)
        if (layer.kind == FrameLayerKind.TEXT) {
            val size = measureText(layer, min(limit, fixedW ?: limit))
            if (size.width <= 0f || size.height <= 0f) return null
            return Measured(layer, min(fixedW ?: size.width, limit), fixedH ?: size.height)
        }
        if (layer.kind != FrameLayerKind.GROUP) {
            if (layer.kind == FrameLayerKind.IMAGE && layer.imageId == null && !layer.autoLogo) return null
            return Measured(layer, fixedW ?: 60f, fixedH ?: if (layer.kind == FrameLayerKind.LINE) 1.5f else 60f)
        }
        val pad = layer.padding * FRAME_UNIT
        val innerW = max(1f, min(fixedW ?: limit, availableWidth) - pad * 2)
        var children = layer.children.sortedBy { it.z }.mapNotNull { measure(it, innerW, max(1f, (fixedH ?: availableHeight) - pad * 2)) }
        if (children.isEmpty() && layer.decoration == null) return null
        val gap = layer.gap * FRAME_UNIT
        if (layer.flow == FrameFlow.ROW && children.isNotEmpty()) {
            val flexible = children.filter { it.layer.kind in setOf(FrameLayerKind.TEXT, FrameLayerKind.GROUP) }
            val rigid = children.filterNot { it in flexible }.sumOf { it.width.toDouble() }.toFloat()
            val free = max(1f, innerW - rigid - gap * (children.size - 1))
            val desired = flexible.sumOf { it.width.toDouble() }.toFloat()
            if (desired > free) children = children.mapNotNull { child ->
                if (child in flexible) measure(child.layer, max(1f, child.width * free / desired), child.height) else child
            }
        }
        val totalW = when (layer.flow) {
            FrameFlow.ROW -> children.sumOf { it.width.toDouble() }.toFloat() + gap * max(0, children.size - 1)
            FrameFlow.COLUMN -> children.maxOfOrNull { it.width } ?: 0f
            FrameFlow.FREE -> fixedW ?: innerW
        }
        val totalH = when (layer.flow) {
            FrameFlow.COLUMN -> children.sumOf { it.height.toDouble() }.toFloat() + gap * max(0, children.size - 1)
            FrameFlow.ROW -> children.maxOfOrNull { it.height } ?: 0f
            FrameFlow.FREE -> fixedH ?: availableHeight
        }
        return Measured(layer, min(fixedW ?: (totalW + pad * 2), availableWidth), fixedH ?: (totalH + pad * 2), children)
    }

    private fun place(node: Measured, parent: FrameMatrix, left: Float, top: Float, alpha: Float, parentLocked: Boolean = false): FramePlacement {
        val layer = node.layer
        // Rotate about the declared pivot. Flow children use the same transform, so gestures need only invert their parent.
        val pivotX = layer.pivot.x * node.width; val pivotY = layer.pivot.y * node.height
        val matrix = parent.then(FrameMatrix.translation(left + pivotX + layer.x * FRAME_UNIT, top + pivotY + layer.y * FRAME_UNIT))
            .then(FrameMatrix.rotation(layer.rotation, layer.scale)).then(FrameMatrix.translation(-pivotX, -pivotY))
        val opacity = alpha * layer.opacity
        val pad = layer.padding * FRAME_UNIT
        val innerW = max(0f, node.width - pad * 2); val innerH = max(0f, node.height - pad * 2)
        val isRow = layer.flow == FrameFlow.ROW
        val used = node.children.sumOf { (if (isRow) it.width else it.height).toDouble() }.toFloat()
        val available = if (isRow) innerW else innerH
        val baseGap = layer.gap * FRAME_UNIT
        val gap = if (layer.align == FrameAlign.SPACE_BETWEEN && node.children.size > 1)
            max(baseGap, (available - used) / (node.children.size - 1)) else baseGap
        val spare = max(0f, available - used - gap * max(0, node.children.size - 1))
        fun alignment(): Float = when (layer.align) { FrameAlign.START -> 0f; FrameAlign.END -> 1f; else -> 0.5f }
        var cursor = pad + if (layer.align == FrameAlign.SPACE_BETWEEN) 0f else spare * alignment()
        val children = node.children.map { child ->
            val x: Float; val y: Float
            when (layer.flow) {
                FrameFlow.ROW -> { x = cursor; y = pad + (innerH - child.height) * alignment(); cursor += child.width + gap }
                FrameFlow.COLUMN -> { x = pad + (innerW - child.width) * alignment(); y = cursor; cursor += child.height + gap }
                FrameFlow.FREE -> {
                    x = pad + innerW * child.layer.at.x - child.width * child.layer.pivot.x
                    y = pad + innerH * child.layer.at.y - child.height * child.layer.pivot.y
                }
            }
            place(child, matrix, x, y, opacity, parentLocked || layer.locked)
        }
        return FramePlacement(layer, node.width, node.height, matrix, parent, opacity, children, parentLocked || layer.locked)
    }
}
