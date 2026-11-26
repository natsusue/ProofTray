package org.prooftray.app.domain

import org.junit.Assert.*
import org.junit.Test
import org.prooftray.app.color.LookCodec
import org.prooftray.app.data.RecordCodec

class AdvancedColorTest {
    private val edits = ColorAdjustments(highlights = -.4f, shadows = .3f, vibrance = .2f,
        curves = listOf(ToneCurve().insert(.3f, .4f), ToneCurve(), ToneCurve(), ToneCurve()),
        hsl = List(8) { if (it == 5) HslBand(.2f, .1f, -.2f) else HslBand() },
        grading = List(4) { if (it == 0) GradeWheel(220f, .2f, .1f) else GradeWheel() }, balance = -.2f)

    @Test fun advancedEditsRoundTripThroughPresetsAndExportTasks() {
        val look = ColorLookSettings(tint = .2f, exposure = .4f, adjustments = edits)
        val envelope = LookCodec.presetEnvelope("测试", look)
        assertEquals(2, envelope.getInt("version"))
        assertEquals(look, LookCodec.decodePresetLook(LookCodec.checkedJson(envelope.toString())))
        val task = ExportTask("task", PhotoAsset("photo", "content:photo", "a.nef", MediaFormat.RAW), ExportOptions(look = look), "")
        assertEquals(task, RecordCodec.decodeTask(RecordCodec.encodeTask(task)))
    }

    @Test fun onlyVersionTwoPresetsAndRecipesAreAccepted() {
        val look = ColorLookSettings(exposure = 1f, strength = 0f)
        val envelope = LookCodec.presetEnvelope("测试", look).put("schema", "org.openbridge.look")
        assertEquals(look, LookCodec.decodePresetLook(envelope))
        for (version in listOf(1, 2.5, 3, "2")) {
            assertThrows(IllegalArgumentException::class.java) { LookCodec.decodePresetLook(envelope.put("version", version)) }
        }
        envelope.put("version", 2)
        val recipe = envelope.getJSONObject("look")
        recipe.remove("renderVersion")
        assertThrows(IllegalArgumentException::class.java) { LookCodec.decodePresetLook(envelope) }
    }

    @Test fun zeroLutStrengthDoesNotEraseAdvancedOrBasicEdits() {
        assertFalse(ColorLookSettings(strength = 0f, adjustments = edits).isIdentity)
        assertFalse(ColorLookSettings(strength = 0f, exposure = 1f).isIdentity)
        assertFalse(ColorLookSettings(strength = 0f, tint = .3f).isIdentity)
        assertTrue(ColorLookSettings(lutId = "a".repeat(64), strength = 0f).isIdentity)
        assertFalse(ColorLookSettings(lutId = "a".repeat(64), strength = 0f, usage = LutUsage.nlog).isIdentity)
    }

    @Test fun selectingAndRemovingLutsPreservesCurrentAdjustments() {
        val current = ColorLookSettings(exposure = 1f, tint = .3f, adjustments = edits)
        val chosen = current.withLut(ColorLookSettings("LUT", "a".repeat(64), .5f))
        assertEquals(edits, chosen.adjustments)
        assertEquals(1f, chosen.exposure, 0f)
        assertEquals(.3f, chosen.tint, 0f)
        assertNull(chosen.withoutLut().lutId)
        assertEquals(edits, chosen.withoutLut().adjustments)
        assertEquals(1f, chosen.withoutLut().exposure, 0f)
    }

    @Test fun wireParametersAreBoundedAndCarryTheRecipeVersion() {
        assertEquals(63, ColorAdjustments().parameters().size)
        assertEquals(65, edits.parameters().size)
        val look = ColorLookSettings(tint = .2f, adjustments = edits)
        assertEquals(7, look.parameters().size)
        assertEquals(2f, look.parameters()[5], 0f)
        assertEquals(.2f, look.parameters()[6], 0f)
        assertThrows(IllegalArgumentException::class.java) { edits.copy(hsl = listOf(HslBand())).validate() }
        assertThrows(IllegalArgumentException::class.java) { edits.copy(shadows = Float.NaN).validate() }
    }

    @Test fun invalidPresetVersionsAndMalformedCurvesAreRejected() {
        val json = LookCodec.encode(ColorLookSettings(adjustments = edits))
        for (version in listOf(1, 3, 1.5, "2")) {
            assertThrows(IllegalArgumentException::class.java) { LookCodec.decode(json.put("renderVersion", version)) }
        }
        json.put("renderVersion", 2)
        json.getJSONObject("adjustments").getJSONArray("curves").getJSONArray(0).getJSONArray(1).put(0, 0)
        assertThrows(IllegalArgumentException::class.java) { LookCodec.decode(json) }
    }

    @Test fun curveHistogramUsesOnlyTheAdjustmentsBeforeTheSelectedCurve() {
        val master = edits.curves[0]
        val adjusted = edits.copy(whites = .2f, blacks = -.1f, curves = List(4) { master })
        val look = ColorLookSettings(lutId = "a".repeat(64), usage = LutUsage.nlog, strength = .6f, logGainEv = .5f,
            exposure = .8f, contrast = .3f, warmth = .1f, tint = -.2f, saturation = .5f, adjustments = adjusted)
        val tones = ColorAdjustments(highlights = edits.highlights, shadows = edits.shadows, whites = .2f, blacks = -.1f)
        val beforeMaster = look.copy(name = "", saturation = 1f, adjustments = tones)
        assertEquals(beforeMaster, look.curveInputLook(singleChannel = false))
        assertEquals(beforeMaster.copy(adjustments = tones.copy(curves = listOf(master) + tones.curves.drop(1))),
            look.curveInputLook(singleChannel = true))
    }

    @Test fun curveMovesKeepEndpointsAndPreventCrossing() {
        val curve = ToneCurve().insert(.3f, .4f).insert(.7f, .6f)
        val moved = curve.move(1, .99f, -1f)
        moved.validate()
        assertTrue(moved.points[1].x < moved.points[2].x)
        assertEquals(0f, moved.points[1].y, 0f)
        assertEquals(0f, curve.move(0, .5f, .2f).points[0].x, 0f)
        assertEquals(curve, curve.remove(0))
        assertEquals(curve, curve.remove(curve.points.lastIndex))
    }

    @Test fun curveGraphIsMonotoneAndPassesThroughControlPoints() {
        val curve = ToneCurve().insert(.1f, .3f).insert(.7f, .8f)
        curve.points.forEach { assertEquals(it.y, curve.evaluate(it.x), .00001f) }
        val values = (0..1000).map { curve.evaluate(it / 1000f) }
        assertTrue(values.all { it in 0f..1f })
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a - .000001f })
        assertTrue(ToneCurve().insert(.4f, .4f).isIdentity)
    }

    @Test fun eachGradingWheelKeepsItsOwnNativeAndPresetSlot() {
        val original = edits.copy(grading = List(4) { GradeWheel(it * 60f, it * .1f, it * .05f) })
        val replacement = GradeWheel(220f, .6f, -.2f)
        for (selected in 0..3) {
            val updated = original.withGradingWheel(selected, replacement)
            val restored = LookCodec.decode(LookCodec.encode(ColorLookSettings(adjustments = updated))).adjustments
            val wire = restored.parameters()
            for (index in 0..3) {
                val expected = if (index == selected) replacement else original.grading[index]
                assertEquals(expected, restored.grading[index])
                assertEquals(expected.hue, wire[31 + index * 3], 0f)
                assertEquals(expected.saturation, wire[32 + index * 3], 0f)
                assertEquals(expected.luminance, wire[33 + index * 3], 0f)
            }
            assertEquals(original.curves, updated.curves)
            assertEquals(original.hsl, updated.hsl)
        }
        assertThrows(IllegalArgumentException::class.java) { original.withGradingWheel(4, replacement) }
    }

    @Test fun resettingGradingDoesNotDiscardOtherEditsOrImplicitlyResetOtherWheels() {
        val graded = edits.copy(grading = List(4) { GradeWheel(220f, .5f, .1f) }, blending = .8f, balance = -.4f)
        val oneReset = graded.withGradingWheel(0, GradeWheel())
        assertEquals(GradeWheel(), oneReset.grading[0])
        assertEquals(graded.grading.drop(1), oneReset.grading.drop(1))
        val allReset = graded.resetGrading()
        assertEquals(List(4) { GradeWheel() }, allReset.grading)
        assertEquals(.5f, allReset.blending, 0f)
        assertEquals(0f, allReset.balance, 0f)
        assertEquals(graded.curves, allReset.curves)
        assertEquals(graded.hsl, allReset.hsl)
        assertEquals(graded.highlights, allReset.highlights, 0f)
        assertEquals(graded.shadows, allReset.shadows, 0f)
        assertEquals(graded.vibrance, allReset.vibrance, 0f)
    }
}
