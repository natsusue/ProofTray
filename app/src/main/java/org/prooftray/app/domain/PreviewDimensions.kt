package org.prooftray.app.domain

// Android bitmap byte counts fit in a signed Int. Each ARGB_8888 pixel occupies four bytes.
internal const val MAX_ARGB_8888_PIXELS = Int.MAX_VALUE / 4L

/** Quality is determined by actual pixels, never by a grid/detail filename or the import source. */
internal data class PreviewDimensions(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
    val valid: Boolean get() = width > 0 && height > 0
    val longestEdge: Int get() = maxOf(width, height)

    fun sufficient(detailed: Boolean): Boolean = valid && longestEdge >= (if (detailed) 1600 else 640) &&
        minOf(width, height) >= (if (detailed) 900 else 360)

    fun covers(edge: Int): Boolean = valid && longestEdge >= edge

    fun improves(previous: PreviewDimensions?): Boolean = valid && (previous == null || !previous.valid || pixels > previous.pixels)
}
