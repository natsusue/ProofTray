package org.prooftray.app.domain

import org.junit.Assert.*
import org.junit.Test

class PhotoEditSessionTest {
    private val raw = PhotoAsset("raw", "content:raw", "a.NEF", MediaFormat.RAW)
    private val jpeg = PhotoAsset("jpeg", "content:jpeg", "b.JPG", MediaFormat.JPEG)
    private val frame = FrameTemplate(id = "user.test", name = "测试")
    private val look = ColorLookSettings(name = "测试调色", exposure = 1f)

    @Test fun bothEffectsCanDefaultToEnabled() {
        assertEquals(ExportEffects(true, true), defaultExportEffects(look, frame, listOf(jpeg)))
        assertEquals(ExportEffects(false, false), defaultExportEffects(ColorLookSettings(), null, listOf(jpeg)))
        assertEquals(ExportEffects(true, false), defaultExportEffects(look, null, listOf(jpeg)))
        assertEquals(ExportEffects(false, true), defaultExportEffects(ColorLookSettings(), frame, listOf(jpeg)))
    }
    @Test fun mixedSelectionDisablesOnlyTheLogDefault() {
        val log = ColorLookSettings(lutId = "a".repeat(64), usage = LutUsage.nlog)
        assertEquals(ExportEffects(false, true), defaultExportEffects(log, frame, listOf(raw, jpeg)))
        assertEquals(ExportEffects(true, true), defaultExportEffects(log, frame, listOf(raw)))
    }
    @Test fun exportDetourRetainsIndependentExplicitSwitches() {
        val draft = ExportDialogDraft(ExportPreferences(), color = false, frame = true)
        assertEquals(true, draft.copy(color = true).frame)
        assertEquals(false, draft.copy(frame = false).color)
    }

    @Test fun originalCopySuppressesBothEffectsWithoutChangingConversionChoices() {
        for (color in listOf(false, true)) {
            for (border in listOf(false, true)) {
                val choices = ExportEffects(color, border)
                assertEquals(ExportEffects(false, false), choices.forFormat(ExportFormat.ORIGINAL))
                assertEquals(choices, choices.forFormat(ExportFormat.JPEG))
                assertEquals(choices, choices.forFormat(ExportFormat.PNG))
            }
        }
    }
    @Test fun closingCanonicalizesIdentityAndClearsANullFrame() {
        val identity = ColorLookSettings(lutId = "a".repeat(64), strength = 0f)
        assertEquals(PhotoEditSession(null, frame), photoEditSession(identity, frame))
        assertEquals(PhotoEditSession(look, null), photoEditSession(look, null))
        assertEquals(PhotoEditSession(look, frame), photoEditSession(look, frame))
    }
}
