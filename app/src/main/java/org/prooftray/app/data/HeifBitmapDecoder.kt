package org.prooftray.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.prooftray.app.domain.MAX_ARGB_8888_PIXELS
import org.prooftray.app.domain.PreviewDimensions

internal object NativeHeif {
    init { System.loadLibrary("prooftray_heif") }

    external fun probeBytes(bytes: ByteArray): IntArray?
    external fun probeFile(fd: Int): IntArray?
    external fun decodeFile(fd: Int, bitmap: Bitmap, allowThumbnail: Boolean)
}

internal object HeifBitmapDecoder {
    // Serialize software decoding: a small output can still require a full-resolution HEVC frame.
    private val decodeLock = Mutex()

    fun probe(bytes: ByteArray): PreviewImageInfo? =
        if (hasFileTypeBox(bytes.inputStream())) probeInfo { NativeHeif.probeBytes(bytes) } else null

    fun probe(file: File): PreviewImageInfo? =
        if (file.inputStream().use(::hasFileTypeBox)) probeInfo {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { NativeHeif.probeFile(it.fd) }
        } else null

    fun probe(context: Context, uri: Uri): PreviewImageInfo? =
        if (context.contentResolver.openInputStream(uri)?.use(::hasFileTypeBox) == true) probeInfo {
            withSeekablePhotoFile(context, uri) { NativeHeif.probeFile(it) }
        } else null

    private fun hasFileTypeBox(stream: InputStream): Boolean = try {
        DataInputStream(stream).readLong().toInt() == 0x66747970 // "ftyp"; libheif identifies the actual image codec.
    } catch (_: EOFException) { false }

    private fun probeInfo(read: () -> IntArray?): PreviewImageInfo? = try {
        read()?.let { PreviewImageInfo(PreviewDimensions(it[0], it[1]), "image/heif") }
    } catch (_: LinkageError) { null }

    suspend fun decode(context: Context, uri: Uri, maxEdge: Int, maxPixels: Long = MAX_ARGB_8888_PIXELS,
        allowThumbnail: Boolean, platform: () -> Bitmap): Bitmap {
        val platformError = try {
            return platform()
        } catch (error: IOException) { error }
        val coroutine = currentCoroutineContext()
        try {
            return decodeLock.withLock {
                coroutine.ensureActive()
                withSeekablePhotoFile(context, uri) { fd ->
                    val source = NativeHeif.probeFile(fd) ?: throw IOException("无法读取 HEIF 主图尺寸")
                    val size = renderDimensions(source[0], source[1], maxEdge, maxPixels)
                    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                    try {
                        NativeHeif.decodeFile(fd, bitmap, allowThumbnail)
                        coroutine.ensureActive()
                        bitmap
                    } catch (failure: Throwable) {
                        bitmap.recycle()
                        throw failure
                    }
                }
            }
        } catch (failure: LinkageError) {
            throw IOException("无法加载 HEIF 软件解码库", failure).apply { addSuppressed(platformError) }
        } catch (failure: Exception) {
            failure.addSuppressed(platformError)
            throw failure
        }
    }
}

internal fun renderDimensions(width: Int, height: Int, maxEdge: Int, maxPixels: Long = MAX_ARGB_8888_PIXELS): PreviewDimensions {
    require(width > 0 && height > 0) { "图片尺寸无效" }
    val ratio = if (maxEdge > 0) minOf(1.0, maxEdge.toDouble() / maxOf(width, height)) else 1.0
    val size = PreviewDimensions((width * ratio).roundToInt().coerceAtLeast(1), (height * ratio).roundToInt().coerceAtLeast(1))
    require(size.pixels <= maxPixels) { "位图尺寸过大，请选择较小的输出长边" }
    return size
}
