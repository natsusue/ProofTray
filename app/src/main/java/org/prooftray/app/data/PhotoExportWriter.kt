package org.prooftray.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.OutputStream
import org.prooftray.app.R
import org.prooftray.app.color.LutRepository
import org.prooftray.app.domain.ExportFormat
import org.prooftray.app.domain.ExportTask
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.supportsRendering
import org.prooftray.app.domain.validateLogExport
import org.prooftray.app.frame.FrameCodec
import org.prooftray.app.frame.FrameRenderSession
import org.prooftray.app.frame.FrameRepository
import org.prooftray.app.frame.withFrameInitialization

/** Writes one image. Queue persistence, retries and output ownership belong to BatchExportManager. */
internal class PhotoExportWriter(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val colors = LutRepository.get(appContext)

    fun validate(task: ExportTask) {
        task.options.validateLogExport(listOf(task.asset))
        if (task.options.log != null) return
        val look = task.options.look
        look?.validateForExport(listOf(task.asset))
        task.options.frame?.let { frame ->
            withFrameInitialization {
                require(frame.unavailableReason == null) { frame.unavailableReason.orEmpty() }
                FrameCodec.validate(frame.template)
                require(task.options.format != ExportFormat.ORIGINAL) { "原件复制不应用边框，请选择 JPEG 或 PNG" }
            }
        }
        if (task.options.format == ExportFormat.ORIGINAL) {
            require(look == null || look.isIdentity) { "原格式复制不应用调色，请选择 JPEG 或 PNG" }
            return
        }
        require(task.asset.format.supportsRendering) { "此格式暂不支持转换" }
    }

    suspend fun copyOriginal(task: ExportTask, output: Uri, work: ExportProgress) {
        var copied = 0L
        var lastUpdate = 0L
        work.phase = "读取源文件"
        resolver.openInputStream(Uri.parse(task.asset.uri))?.use { input ->
            work.phase = "打开目标文件"
            resolver.openOutputStream(output, "w")?.use { destination ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    work.ensureActive()
                    work.phase = "读取源文件"
                    val count = input.read(buffer)
                    if (count == -1) break
                    work.phase = "写入目标文件"
                    destination.write(buffer, 0, count)
                    copied += count
                    val now = System.nanoTime()
                    if (now - lastUpdate >= 100_000_000L) {
                        val fraction = if (task.asset.sizeBytes > 0) copied.toDouble() / task.asset.sizeBytes else 0.0
                        val progress = fraction.toFloat().coerceIn(0f, 0.99f)
                        work.report(progress, "正在复制原文件")
                        lastUpdate = now
                    }
                }
                work.phase = "完成保存"
                work.report(0.99f, "复制结束，正在完成保存")
                destination.flush()
            } ?: error("无法打开目标文件")
        } ?: error("源文件无法读取，可能已删除或授权失效")
        work.phase = "校验复制长度"
        require(copied > 0) { "源文件为空" }
        if (task.asset.sizeBytes > 0) require(copied == task.asset.sizeBytes) { "源文件大小已变化，请重新导入后导出" }
    }

    suspend fun convertLog(task: ExportTask, output: Uri, work: ExportProgress): String {
        task.options.validateLogExport(listOf(task.asset))
        val settings = checkNotNull(task.options.log)
        work.ensureActive()
        work.phase = "RAW 显影与 Log 编码"
        work.report(null, "正在生成 16 位 Log 中间文件")
        val rendered = RawPhotoDecoder.exportLog(appContext, task.asset, task.options.maxEdge, settings)
        try {
            work.ensureActive()
            work.phase = "写入 Log TIFF"
            val size = rendered.file.length()
            var copied = 0L
            var lastUpdate = 0L
            rendered.file.inputStream().use { input ->
                resolver.openOutputStream(output, "w")?.use { destination ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        work.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        destination.write(buffer, 0, count)
                        copied += count
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 100_000_000L) {
                            work.report((copied.toDouble() / size).toFloat().coerceIn(0f, 0.99f), "正在保存 Log TIFF")
                            lastUpdate = now
                        }
                    }
                    work.ensureActive()
                    destination.flush()
                } ?: error("无法打开目标文件")
            }
            check(copied == size && size > 0) { "Log 文件复制不完整" }
            // Do not call preserveExif(): its sRGB tag would mislabel these scene Log code values.
            return "已导出 ${rendered.width} × ${rendered.height}，16 位 Log TIFF · ${checkNotNull(settings.encoding).label} · 全范围"
        } finally {
            rendered.file.delete()
        }
    }

    suspend fun convertRaster(task: ExportTask, output: Uri, work: ExportProgress): String {
        require(task.options.format in setOf(ExportFormat.JPEG, ExportFormat.PNG) && task.options.log == null) { "请选择成片导出格式" }
        work.ensureActive()
        val frame = task.options.frame
        val jpeg = task.options.format == ExportFormat.JPEG
        work.phase = "解码源图片"
        work.report(null, "正在解码图片")
        val look = task.options.look?.takeUnless { it.isIdentity }
        var bitmap = if (look != null && look.usesLog) {
            work.phase = "线性 RAW 显影与 Log 调色"
            LogRenderer.export(appContext, task.asset, task.options.maxEdge, colors.canonical(checkNotNull(look.lutId)), look)
        } else {
            PhotoRenderer.decode(appContext, task.asset, task.options.maxEdge, mutable = look != null || (jpeg && frame == null),
                rawLook = look ?: ColorLookSettings())
        }
        val (width, height) = try {
            work.ensureActive()
            if (look != null && !look.usesLog && task.asset.format != MediaFormat.RAW) {
                work.phase = "应用预设与 LUT"
                work.report(0.35f, "正在应用 ${look.name}")
                colors.apply(bitmap, look)
                work.ensureActive()
            }
            if (frame != null) {
                work.phase = "绘制边框与参数"
                work.report(0.45f, "正在绘制边框")
                val framed = FrameRenderSession(bitmap, FrameRepository(appContext)).use { renderer ->
                    renderer.render(frame, task.options.maxEdge, opaque = jpeg, cancelled = work::ensureActive).bitmap
                }
                bitmap.recycle()
                bitmap = framed
            } else if (jpeg && bitmap.hasAlpha()) {
                Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
                bitmap.setHasAlpha(false)
            }
            work.report(0.5f, "正在编码并写入图片")
            work.phase = "打开目标文件"
            resolver.openOutputStream(output, "w")?.use { stream ->
                val cancellable = object : OutputStream() {
                    override fun write(value: Int) { work.ensureActive(); stream.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        work.ensureActive()
                        stream.write(bytes, offset, length)
                    }
                    override fun flush() = stream.flush()
                }
                val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                work.phase = "编码并写入图片"
                check(bitmap.compress(format, task.options.jpegQuality, cancellable)) { "图片编码失败" }
                work.phase = "完成保存"
                stream.flush()
            } ?: error("无法打开目标文件")
            bitmap.width to bitmap.height
        } finally {
            bitmap.recycle()
        }
        work.ensureActive()
        work.phase = "保存基础 EXIF"
        work.report(0.99f, "正在保存 EXIF 并完成导出")
        val preserved = preserveExif(Uri.parse(task.asset.uri), output, width, height)
        return "已导出 $width × $height，sRGB" + (look?.let { " · ${it.name}" } ?: "") +
            (frame?.let { " · " + it.template.name } ?: "") + if (preserved) "；已保留基础 EXIF" else "；未能保留 EXIF"
    }

    private fun preserveExif(source: Uri, output: Uri, width: Int, height: Int): Boolean {
        val original = try { resolver.openInputStream(source)?.use { ExifInterface(it) } } catch (_: Exception) { null } ?: return false
        val descriptor = try { resolver.openFileDescriptor(output, "rw") } catch (_: Exception) { null } ?: return false
        descriptor.use {
            val exif = ExifInterface(it.fileDescriptor)
            for (tag in EXIF_TAGS) original.getAttribute(tag)?.let { value -> exif.setAttribute(tag, value) }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, appContext.getString(R.string.app_name))
            // Once rewriting begins, failures invalidate this new copy and must reach the task's cleanup path.
            exif.saveAttributes()
        }
        return true
    }

    private companion object {
        val EXIF_TAGS = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT
        )
    }
}
