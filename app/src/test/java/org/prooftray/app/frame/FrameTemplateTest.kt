package org.prooftray.app.frame

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prooftray.app.data.RecordCodec
import org.prooftray.app.domain.ExportOptions
import org.prooftray.app.domain.ExportTask
import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.domain.FrameAspect
import org.prooftray.app.domain.FrameCanvas
import org.prooftray.app.domain.FrameDesign
import org.prooftray.app.domain.FrameFieldFormat
import org.prooftray.app.domain.FrameFlow
import org.prooftray.app.domain.FrameInsets
import org.prooftray.app.domain.FrameLayer
import org.prooftray.app.domain.FrameLayerKind
import org.prooftray.app.domain.FramePhoto
import org.prooftray.app.domain.FrameRegion
import org.prooftray.app.domain.FrameSnapshot
import org.prooftray.app.domain.FrameTemplate
import org.prooftray.app.domain.FrameText
import org.prooftray.app.domain.FrameTextPart
import org.prooftray.app.domain.imageIds

class FrameTemplateTest {
    @Test fun builtinsAreEditableDataAndRoundTripWithoutLoss() {
        assertEquals(14, FrameTemplates.all.size)
        FrameTemplates.all.forEach { template ->
            FrameCodec.validate(template)
            assertEquals(template.name, template, FrameCodec.decode(FrameCodec.checkedJson(FrameCodec.encode(template).toString())))
            assertTrue(template.imageIds().isEmpty())
            val frame = FrameSnapshot(template, mapOf("camera" to "Z6 II", "iso" to "100"))
            val task = ExportTask("task", PhotoAsset("photo", "content://photos/1", "DSC0001.NEF", MediaFormat.RAW),
                ExportOptions(frame = frame), "")
            assertEquals(frame, RecordCodec.decodeTask(RecordCodec.encodeTask(task)).options.frame)
        }
    }

    @Test fun fieldsPreservePrecisionAndHideMissingSegments() {
        assertEquals("f/1.67", FrameTextFormatter.format("aperture", "1.67"))
        assertEquals("1.67", FrameTextFormatter.format("aperture", "1.67", FrameFieldFormat.VALUE))
        assertEquals("1/250s", FrameTextFormatter.format("shutter", "0.004"))
        assertEquals("0.3s", FrameTextFormatter.format("shutter", "0.3"))
        assertEquals("2.5s", FrameTextFormatter.format("shutter", "2.5"))
        val text = FrameText(listOf(FrameTextPart("ISO: {iso}"), FrameTextPart("{lens}")), separator = " · ")
        val runs = FrameTextFormatter.runs(text, mapOf("lens" to "NIKKOR Z 40mm"), FrameTemplates.all.first())
        assertEquals(listOf("NIKKOR Z 40mm"), runs.map { it.text })
        assertEquals("0.0000°N  0.0000°E", FrameTextFormatter.format("gps", "0,0"))
        val literal = FrameText(listOf(FrameTextPart("{not_a_field}", literal = true)))
        assertEquals("{not_a_field}", FrameTextFormatter.runs(literal, emptyMap(), FrameTemplates.all.first()).single().text)
    }

    @Test fun singleRemainingRowStaysVerticallyCentered() {
        val template = FrameTemplate("test", "测试", design = FrameDesign(layers = listOf(
            FrameLayer(id = "group", kind = FrameLayerKind.GROUP, flow = FrameFlow.COLUMN, children = listOf(
                FrameLayer(id = "camera", text = FrameText(listOf(FrameTextPart("{camera}")))),
                FrameLayer(id = "lens", text = FrameText(listOf(FrameTextPart("{lens}"))))
            ))
        )))
        val values = mapOf("camera" to "Z6 II")
        val geometry = FrameGeometry.create(3000, 4000, template.design)
        val result = place(template, values).single()
        assertEquals(1, result.children.size)
        val center = result.matrix.y(result.width / 2, result.height / 2)
        val footer = geometry.region(FrameRegion.BOTTOM)
        assertEquals(footer.top + footer.height / 2, center, 0.001f)
        val locked = template.copy(design = template.design.copy(layers = template.design.layers.map { it.copy(locked = true) }))
        assertTrue(place(locked, values).single().children.single().locked)
    }

    @Test fun sideRotationHasRoomAlongItsLongAxis() {
        val template = FrameTemplates.all.first { it.id == "builtin.mi-side" }
        val result = place(template, mapOf("focal" to "23", "aperture" to "1.67", "shutter" to "0.3", "iso" to "3200", "camera" to "Z6 II"))
        assertTrue(result.first().width > 105f)
        assertEquals(90f, result.first().layer.rotation, 0f)
        val geometry = FrameGeometry.create(3000, 4000, template.design)
        assertEquals(0.75f, geometry.photo.width / geometry.photo.height, 0.0001f)
    }

    @Test fun finalSizeIncludesFrameAndFixedAspectKeepsTheWholePhoto() {
        val design = FrameDesign(FrameCanvas(FrameInsets(0.03f, 0.03f, 0.12f, 0.03f)))
        val geometry = FrameGeometry.create(6000, 4000, design)
        assertEquals(FrameOutputSize(6240, 4600), geometry.outputSize(0))
        assertEquals(4096, geometry.outputSize(4096).width)
        val large = FrameGeometry.create(9000, 6000, design)
        assertEquals(FrameOutputSize(9360, 6900), large.outputSize(0))
        val square = FrameGeometry.create(4000, 6000, design.copy(canvas = design.canvas.copy(aspect = FrameAspect.SQUARE)))
        assertEquals(square.canvas.width, square.canvas.height, 0f)
        assertEquals(2f / 3, square.photo.width / square.photo.height, 0.0001f)
    }

    @Test fun unknownVersionAndLayerAreRejectedWithoutBreakingTaskRecovery() {
        val json = FrameCodec.encode(FrameTemplates.all.first()).put("schemaVersion", 99)
        val task = JSONObject().put("template", json).put("values", JSONObject())
        val snapshot = FrameCodec.decodeSnapshot(task)
        assertNotNull(snapshot.unavailableReason)
        assertEquals(99, FrameCodec.encodeSnapshot(snapshot).getJSONObject("template").getInt("schemaVersion"))
        val layer = FrameCodec.encode(FrameTemplates.all.first())
        layer.getJSONArray("layers").getJSONObject(0).put("type", "SCRIPT")
        assertTrue(runCatching { FrameCodec.decode(layer) }.isFailure)
    }

    @Test fun invalidGeometryAndUnknownFieldsAreRejected() {
        val template = FrameTemplates.all.first()
        assertTrue(runCatching { FrameCodec.validate(template.copy(design = template.design.copy(photo = FramePhoto(scale = Float.NaN)))) }.isFailure)
        assertTrue(runCatching { FrameTextFormatter.validate("{unrecognized}") }.isFailure)
        val many = template.copy(design = FrameDesign(layers = List(33) { FrameLayer() }))
        assertTrue(runCatching { FrameCodec.validate(many) }.isFailure)
        val future = FrameCodec.encode(template)
        future.getJSONArray("layers").getJSONObject(0).put("execute", "anything")
        assertTrue(runCatching { FrameCodec.decode(future) }.isFailure)
    }

    @Test fun coordinateInverseSupportsRotatedParentGroups() {
        val matrix = FrameMatrix.translation(140f, 280f).then(FrameMatrix.rotation(90f, 1.4f))
        val inverse = matrix.inverse()
        val x = matrix.x(27f, -81f); val y = matrix.y(27f, -81f)
        assertEquals(27f, inverse.x(x, y), 0.001f)
        assertEquals(-81f, inverse.y(x, y), 0.001f)
        val scaled = FramePlacement(FrameLayer(), 10f, 10f, FrameMatrix.rotation(0f, 4f), FrameMatrix(), 1f)
        assertTrue(scaled.hit(-9f, 20f, 10f))
        assertFalse(scaled.hit(-11f, 20f, 10f))
    }

    private fun place(template: FrameTemplate, values: Map<String, String>): List<FramePlacement> {
        val geometry = FrameGeometry.create(3000, 4000, template.design)
        return FrameLayout(geometry, template, values, false) { layer, width ->
            val content = FrameTextFormatter.runs(layer.text, values, template).joinToString(" ") { it.text }
            if (content.isEmpty()) FrameSize(0f, 0f) else FrameSize(minOf(width, content.length * 10f), 20f)
        }.place(template.design.layers)
    }
}
