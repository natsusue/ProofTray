package org.prooftray.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.prooftray.app.domain.PreviewDimensions

class RenderDimensionsTest {
    @Test fun fullSizeExportKeepsHighResolutionPhotos() {
        assertEquals(PreviewDimensions(9504, 6336), renderDimensions(9504, 6336, 0))
    }

    @Test fun limitsApplyToTheOutputAfterResizing() {
        assertThrows(IllegalArgumentException::class.java) { renderDimensions(32768, 16384, 0) }
        assertEquals(PreviewDimensions(4096, 2048), renderDimensions(32768, 16384, 4096))
        assertThrows(IllegalArgumentException::class.java) { renderDimensions(32768, 16384, 4096, maxPixels = 8_000_000L) }
    }
}
