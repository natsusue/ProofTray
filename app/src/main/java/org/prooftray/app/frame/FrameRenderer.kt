package org.prooftray.app.frame

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.prooftray.app.domain.flatten
import org.prooftray.app.domain.FrameAlign
import org.prooftray.app.domain.FrameBackgroundKind
import org.prooftray.app.domain.FrameDecoration
import org.prooftray.app.domain.FrameDesign
import org.prooftray.app.domain.FrameLayer
import org.prooftray.app.domain.FrameLayerKind
import org.prooftray.app.domain.FrameRepeat
import org.prooftray.app.domain.FrameSnapshot
import org.prooftray.app.domain.imageIds

internal data class FrameRenderPlan(val geometry: FrameGeometry, val design: FrameDesign, val layers: List<FramePlacement>,
    val texts: Map<String, StaticLayout>) {
    val placements: List<FramePlacement> get() = layers.flatMap { it.flatten() }
    fun hit(x: Float, y: Float, tolerance: Float = 8f): FramePlacement? {
        val visibleRoots = layers.filter { it.layer.z >= 0 || !geometry.photo.contains(x, y) }
        return visibleRoots.flatMap { it.flatten() }.asReversed().firstOrNull { !it.locked && it.hit(x, y, tolerance) }
    }
}
internal data class FrameRendered(val bitmap: Bitmap, val plan: FrameRenderPlan)

/** One source image per session. Changing text/geometry reuses the developed photo and cached background. Caller owns source. */
internal class FrameRenderSession(private val source: Bitmap, private val repository: FrameRepository) : AutoCloseable {
    private val pictures = mutableMapOf<String, Bitmap>()
    private var backgroundKey: String? = null
    private var background: Bitmap? = null
    private var paper: Bitmap? = null
    private var closed = false

    @Synchronized
    fun render(snapshot: FrameSnapshot, maxEdge: Int, opaque: Boolean = false, cancelled: () -> Unit = {}): FrameRendered {
        check(!closed)
        snapshot.unavailableReason?.let { error(it) }
        FrameCodec.validate(snapshot.template)
        repository.problem(snapshot.template)?.let { error(it) }
        val needed = snapshot.template.imageIds() + listOfNotNull(snapshot.logoId)
        pictures.keys.filterNot { it in needed }.forEach { pictures.remove(it)?.recycle() }
        val design = snapshot.template.forPhoto(source.width, source.height)
        val plan = prepare(snapshot, design)
        val output = plan.geometry.outputSize(maxEdge)
        val bitmap = Bitmap.createBitmap(output.width, output.height, Bitmap.Config.ARGB_8888, !opaque,
            ColorSpace.get(ColorSpace.Named.SRGB)).apply { density = Bitmap.DENSITY_NONE }
        try {
            cancelled()
            draw(Canvas(bitmap), bitmap.width, bitmap.height, plan, snapshot.logoId, opaque, cancelled)
            cancelled()
            return FrameRendered(bitmap, plan)
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }

    private fun prepare(snapshot: FrameSnapshot, design: FrameDesign): FrameRenderPlan {
        val geometry = FrameGeometry.create(source.width, source.height, design)
        val texts = mutableMapOf<String, StaticLayout>()
        val layout = FrameLayout(geometry, snapshot.template, snapshot.values, snapshot.logoId != null) { layer, width ->
            val text = textLayout(layer, width, snapshot)
            if (text == null) FrameSize(0f, 0f) else {
                texts[layer.id] = text
                FrameSize(text.width.toFloat(), text.height.toFloat())
            }
        }
        return FrameRenderPlan(geometry, design, layout.place(design.layers), texts)
    }

    private fun textLayout(layer: FrameLayer, maxWidth: Float, snapshot: FrameSnapshot): StaticLayout? {
        val spec = layer.text
        val runs = FrameTextFormatter.runs(spec, snapshot.values, snapshot.template)
        if (runs.isEmpty()) return null
        val text = SpannableStringBuilder()
        runs.forEachIndexed { index, run ->
            if (index > 0) text.append(spec.separator)
            val start = text.length
            text.append(run.text)
            run.color?.let { color ->
                text.setSpan(object : CharacterStyle() {
                    override fun updateDrawState(paint: TextPaint) {
                        val alpha = paint.alpha
                        paint.color = Color.parseColor(color); paint.alpha = alpha
                    }
                }, start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            run.weight?.let { weight ->
                val face = repository.typeface(spec.font, weight, spec.italic)
                text.setSpan(object : MetricAffectingSpan() {
                    override fun updateDrawState(paint: TextPaint) { paint.typeface = face }
                    override fun updateMeasureState(paint: TextPaint) { paint.typeface = face }
                }, start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = repository.typeface(spec.font, spec.weight, spec.italic)
            color = Color.parseColor(spec.color); textSize = spec.size * FRAME_UNIT
            letterSpacing = spec.letterSpacing / spec.size
        }
        val desired = Layout.getDesiredWidth(text, paint)
        if (spec.maxLines == 1 && desired > maxWidth) paint.textSize *= max(spec.minScale, maxWidth / desired)
        val width = max(1, ceil(min(maxWidth, if (layer.width != null || layer.fillWidth) maxWidth else Layout.getDesiredWidth(text, paint))).toInt())
        val alignment = when (spec.align) {
            FrameAlign.START -> Layout.Alignment.ALIGN_NORMAL; FrameAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(alignment).setIncludePad(false)
            .setLineSpacing(0f, 1.2f).setMaxLines(spec.maxLines).setEllipsize(TextUtils.TruncateAt.END)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
    }

    private fun draw(canvas: Canvas, width: Int, height: Int, plan: FrameRenderPlan, logo: String?, opaque: Boolean, cancelled: () -> Unit) {
        if (opaque) canvas.drawColor(Color.WHITE)
        canvas.scale(width / plan.geometry.canvas.width, height / plan.geometry.canvas.height)
        drawBackground(canvas, plan, cancelled)
        var draws = 0
        fun layer(placement: FramePlacement) {
            cancelled()
            val node = placement.layer
            val count = if (node.repeat == FrameRepeat.NONE) 1 else {
                val region = plan.geometry.region(node.region)
                ((if (node.repeat == FrameRepeat.ROW) region.width else region.height) / (node.repeatStep * FRAME_UNIT)).toInt().coerceIn(1, 256)
            }
            repeat(count) { index ->
                require(++draws <= 1024) { "模板重复图形过多" }
                canvas.save()
                val dx = if (node.repeat == FrameRepeat.ROW) index * node.repeatStep * FRAME_UNIT else 0f
                val dy = if (node.repeat == FrameRepeat.COLUMN) index * node.repeatStep * FRAME_UNIT else 0f
                canvas.translate(dx, dy)
                canvas.concat(placement.matrix.toAndroid())
                val rect = RectF(0f, 0f, placement.width, placement.height)
                node.decoration?.let { decoration(canvas, rect, it, placement.opacity) }
                when (node.kind) {
                    FrameLayerKind.TEXT -> plan.texts[node.id]?.let {
                        val originalAlpha = it.paint.alpha
                        it.paint.alpha = (placement.opacity * 255).roundToInt()
                        canvas.translate(0f, max(0f, (placement.height - it.height) / 2))
                        it.draw(canvas)
                        it.paint.alpha = originalAlpha
                    }
                    FrameLayerKind.IMAGE -> (if (node.autoLogo) logo ?: node.imageId else node.imageId)?.let { id ->
                        val picture = pictures.getOrPut(id) { repository.readImage(id) }
                        val fit = min(rect.width() / picture.width, rect.height() / picture.height)
                        val w = picture.width * fit; val h = picture.height * fit
                        val target = RectF((rect.width() - w) / 2, (rect.height() - h) / 2, (rect.width() + w) / 2, (rect.height() + h) / 2)
                        canvas.drawBitmap(picture, null, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                            alpha = (placement.opacity * 255).roundToInt()
                        })
                    }
                    FrameLayerKind.LINE -> if (node.decoration == null) canvas.drawRect(rect, Paint().apply {
                        color = Color.GRAY; alpha = (placement.opacity * 255).roundToInt()
                    })
                    else -> Unit
                }
                canvas.restore()
            }
            placement.children.forEach(::layer)
        }
        plan.layers.filter { it.layer.z < 0 }.forEach(::layer)
        drawPhoto(canvas, plan)
        plan.layers.filter { it.layer.z >= 0 }.forEach(::layer)
    }

    private fun drawPhoto(canvas: Canvas, plan: FrameRenderPlan) {
        val photo = plan.geometry.photo.toRect()
        val p = plan.design.photo
        val radius = min(p.radius * FRAME_UNIT, min(photo.width(), photo.height()) / 2)
        if (p.shadow > 0f) {
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                setShadowLayer(p.shadow * FRAME_UNIT, 0f, p.shadow * FRAME_UNIT / 3, Color.argb((p.shadowOpacity * 255).roundToInt(), 0, 0, 0))
            }
            canvas.drawRoundRect(photo, radius, radius, shadow)
        }
        // Antialiased corners without a full-resolution offscreen saveLayer.
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply {
            setRectToRect(RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), photo, Matrix.ScaleToFit.FILL)
        })
        canvas.drawRoundRect(photo, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        if (p.stroke > 0f) canvas.drawRoundRect(photo, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(p.strokeColor); style = Paint.Style.STROKE; strokeWidth = p.stroke * FRAME_UNIT
        })
    }

    private fun drawBackground(canvas: Canvas, plan: FrameRenderPlan, cancelled: () -> Unit) {
        val bg = plan.design.canvas.background
        val rect = plan.geometry.canvas.toRect()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color = Color.parseColor(bg.color); alpha = (bg.opacity * 255).roundToInt()
        }
        when (bg.kind) {
            FrameBackgroundKind.SOLID -> canvas.drawRect(rect, paint)
            FrameBackgroundKind.GRADIENT -> {
                paint.shader = LinearGradient(0f, 0f, rect.right, rect.bottom, Color.parseColor(bg.color),
                    Color.parseColor(bg.secondColor), Shader.TileMode.CLAMP)
                canvas.drawRect(rect, paint)
            }
            FrameBackgroundKind.BLUR -> {
                // The source is already color graded. Only this photo-derived background follows the look; frame colors do not.
                val key = rect.width().toString() + ":" + rect.height() + ":" + bg.blur
                if (key != backgroundKey) {
                    background?.recycle(); background = null
                    background = FrameBackgroundEffects.blur(source, rect.width(), rect.height(), bg.blur, cancelled)
                    backgroundKey = key
                }
                canvas.drawBitmap(checkNotNull(background), null, rect, paint)
                canvas.drawRect(rect, Paint().apply { color = Color.argb((bg.dim * 255).roundToInt(), 0, 0, 0) })
            }
            FrameBackgroundKind.TEXTURE -> {
                canvas.drawRect(rect, paint)
                val grain = paper ?: FrameBackgroundEffects.texture().also { paper = it }
                val shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                shader.setLocalMatrix(Matrix().apply {
                    setScale(bg.textureSize * FRAME_UNIT / grain.width, bg.textureSize * FRAME_UNIT / grain.height)
                })
                canvas.drawRect(rect, Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader; alpha = (bg.opacity * 255).roundToInt() })
            }
            FrameBackgroundKind.IMAGE -> {
                canvas.drawRect(rect, paint)
                bg.imageId?.let { id ->
                    val image = pictures.getOrPut(id) { repository.readImage(id) }
                    val fit = max(rect.width() / image.width, rect.height() / image.height)
                    val dx = (rect.width() - image.width * fit) / 2; val dy = (rect.height() - image.height * fit) / 2
                    canvas.drawBitmap(image, null, RectF(dx, dy, dx + image.width * fit, dy + image.height * fit), paint)
                }
            }
        }
    }

    private fun decoration(canvas: Canvas, rect: RectF, style: FrameDecoration, opacity: Float) {
        val radius = min(style.radius * FRAME_UNIT, min(rect.width(), rect.height()) / 2)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(style.color); alpha = (opacity * 255).roundToInt()
            if (style.shadow > 0f) setShadowLayer(style.shadow * FRAME_UNIT, 0f, style.shadow * FRAME_UNIT / 3, 0x40000000)
        }
        fun shape() { if (style.circle) canvas.drawOval(rect, paint) else canvas.drawRoundRect(rect, radius, radius, paint) }
        if (style.fill) shape()
        if (style.stroke > 0f) {
            paint.clearShadowLayer(); paint.style = Paint.Style.STROKE; paint.strokeWidth = style.stroke * FRAME_UNIT
            paint.color = Color.parseColor(style.strokeColor); paint.alpha = (opacity * 255).roundToInt()
            if (style.dash > 0f) paint.pathEffect = DashPathEffect(floatArrayOf(style.dash * FRAME_UNIT, style.dash * FRAME_UNIT), 0f)
            shape()
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        background?.recycle(); background = null
        paper?.recycle(); paper = null
        pictures.values.forEach(Bitmap::recycle); pictures.clear()
    }
}

private fun FrameRect.toRect() = RectF(left, top, right, bottom)
private fun FrameMatrix.toAndroid() = Matrix().apply { setValues(floatArrayOf(a, c, tx, b, d, ty, 0f, 0f, 1f)) }
