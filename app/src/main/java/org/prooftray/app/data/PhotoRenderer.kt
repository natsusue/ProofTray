package org.prooftray.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.PhotoAsset

/** Shared render path for editing and export. RAW always goes through LibRaw, never through its embedded preview. */
internal object PhotoRenderer {
    suspend fun decode(context: Context, asset: PhotoAsset, maxEdge: Int, mutable: Boolean,
        rawLook: ColorLookSettings = ColorLookSettings()): Bitmap {
        if (asset.format == MediaFormat.RAW) return RawPhotoDecoder.decode(context, asset, maxEdge, rawLook)
        return withContext(Dispatchers.IO) {
            val uri = Uri.parse(asset.uri)
            // Both ImageDecoder and LibRaw apply orientation; no second EXIF rotation is needed.
            fun decodePlatform() = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val size = renderDimensions(info.size.width, info.size.height, maxEdge)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = mutable
                decoder.setTargetSize(size.width, size.height)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
            val decoded = if (asset.format == MediaFormat.HEIF) {
                HeifBitmapDecoder.decode(context, uri, maxEdge, allowThumbnail = false, platform = ::decodePlatform)
            } else decodePlatform()
            val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888 && (!mutable || decoded.isMutable)) decoded else {
                try { decoded.copy(Bitmap.Config.ARGB_8888, mutable) ?: error("无法创建 sRGB 位图") }
                finally { decoded.recycle() }
            }
            if (Build.VERSION.SDK_INT >= 34) bitmap.setGainmap(null)
            bitmap
        }
    }
}
