package org.prooftray.app.camera

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prooftray.app.domain.AppSettings
import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.SavedCamera
import org.prooftray.app.domain.photoMimeType

class SonyImportTest {
    @Test fun pinnedAccessUsesTheBondedCameraKeyAndRejectsIncompleteRecords() {
        val key = byteArrayOf(1, 2, 3)
        val fingerprint = Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        val body = listOf("test-user", "test-pass", "SHA256:$fingerprint").fold(byteArrayOf(0, 0, 2)) { data, value ->
            data + value.length.toByte() + value.toByteArray(Charsets.US_ASCII)
        }
        val record = byteArrayOf(body.size.toByte()) + body
        val access = requireNotNull(SonyControlData.access(record))
        assertTrue(access.accepts(key))
        assertFalse(access.accepts(byteArrayOf(9)))
        assertFalse(access.toString().contains("test-pass"))
        assertThrows(IllegalArgumentException::class.java) { SonyControlData.access(record.copyOf(record.size - 1)) }
        assertThrows(IllegalStateException::class.java) { SonyControlData.access(byteArrayOf(3, 0, 0, 0)) }
        assertNull(SonyControlData.access(byteArrayOf(3, 0, 0, 1)))
    }

    @Test fun bluetoothAcknowledgementAndHotspotReadinessAreSeparate() {
        assertNull(SonyControlData.pairResult(byteArrayOf(7, 0, 0, 1, 0, 0, 0, 1)))
        assertNull(requireNotNull(SonyControlData.pairResult(byteArrayOf(7, 0, 0, 2, 0, 0, 0, 1))).failure)
        fun failure(type: Int, code: Int) = requireNotNull(SonyControlData.pairResult(byteArrayOf(7, 0, 0, type.toByte(), 0, 0, 0, code.toByte())))
        assertTrue(failure(2, 6).failure.orEmpty().contains("已取消"))
        assertTrue(failure(1, 7).failure.orEmpty().contains("经典蓝牙"))
        assertTrue(failure(2, 7).failure.orEmpty().contains("尚未准备好"))
        assertTrue(failure(2, 9).diagnostic.contains("EE03"))
        assertTrue(failure(2, 42).failure.orEmpty().contains("42"))
        assertFalse(requireNotNull(SonyControlData.wifiStatus(byteArrayOf(4, 0, 1, 1, 1))).ready)
        assertTrue(requireNotNull(SonyControlData.wifiStatus(byteArrayOf(3, 0, 8, 1, 4, 0, 1, 2, 0))).ready)
        assertNull(SonyControlData.wifiStatus(byteArrayOf(4, 0, 1, 2)))
        assertTrue(requireNotNull(SonyControlData.wifiStatus(byteArrayOf(4, 0, 1, 0, 5))).terminated)
        assertFalse(requireNotNull(SonyControlData.wifiStatus(byteArrayOf(4, 0, 1, 3, 0))).terminated)
    }

    @Test fun newSonyCameraMustAdvertiseBothPairingFlags() {
        val header = byteArrayOf(3, 0, 0, 0, 'U'.code.toByte(), '1'.code.toByte(), 0x21, 0, 0)
        fun payload(flags: Int) = header + byteArrayOf(0x22, flags.toByte(), 0)
        assertTrue(SonyControlData.advertisesPairing(payload(0xc0), false))
        assertFalse(SonyControlData.advertisesPairing(payload(0x80), true))
        assertFalse(SonyControlData.advertisesPairing(payload(0x40), false))
        assertFalse(SonyControlData.advertisesPairing(header + 0x22.toByte(), false))
        assertTrue(SonyControlData.advertisesPairing(null, true))
    }

    @Test fun sonyControlCapabilitiesDistinguishAccessAuthenticationFromLanDiscovery() {
        val functions = SonyControlData.supportedFunctions(byteArrayOf(6, 0, 8, 1, 0, 10, 0))
        assertTrue(SonyControlData.SSH_INFO in functions)
        assertFalse(SonyControlData.ACCESS_POINT in functions)
        assertEquals(setOf(8), SonyControlData.supportedFunctions(byteArrayOf(6, 0, 8, 1, 0, 10)))
        assertEquals(setOf(8), SonyControlData.supportedFunctions(byteArrayOf(6, 0, 8, 1, 0, 10, 2)))
        assertThrows(IllegalArgumentException::class.java) { SonyControlData.supportedFunctions(byteArrayOf(2, 0, 8)) }
    }

    @Test fun sonyPairingRecoveryRequiresEvidenceFromThisBond() {
        assertTrue(usesSonySystemPairing(36, "OnePlus"))
        assertTrue(usesSonySystemPairing(37, "Google"))
        assertFalse(usesSonySystemPairing(36, "Xiaomi"))
        val rejected = sonyGattFailure("写入配对请求（EE01）", 157)
        assertTrue(rejected.diagnostic.contains("157（0x9D）"))
        assertTrue(rejected.attributeRejected)
        assertFalse(sonyGattFailure("读取机身信息", 133).attributeRejected)
        assertTrue(canReconnectSonyPairing(157, confirmed = false, newBond = true))
        assertFalse(canReconnectSonyPairing(157, confirmed = false, newBond = false))
        assertFalse(canReconnectSonyPairing(null, confirmed = false, newBond = true))
        assertTrue(canReconnectSonyPairing(0x12, confirmed = true, newBond = false))
    }

    @Test fun hotspotWireStatesAreIndependentOfErrorCodes() {
        for (state in 0..3) {
            for (error in listOf(0, 1, 5, 9)) {
                val status = requireNotNull(SonyControlData.wifiStatus(byteArrayOf(4, 0, 1, state.toByte(), error.toByte())))
                assertEquals(state, status.state)
                assertEquals(error, status.error)
                assertEquals(state == 2, status.ready)
                assertEquals(state == 1, status.launching)
                assertEquals(state == 3, status.terminating)
                assertEquals(state == 0, status.terminated)
            }
        }
    }

    @Test fun lanIdentityComesFromTheRespondersDeviceDescription() {
        val id = UUID.fromString("00000000-0000-1000-9000-000000000001")
        val host = "192.0.2.2"
        val st = "urn:schemas-sony-com:service:DigitalImaging:1"
        val response = "HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\nLOCATION: http://$host:64321/dd.xml\r\n\r\n"
        assertEquals("/dd.xml", sonyDescriptionLocation(response, host)?.path)
        assertNull(sonyDescriptionLocation(response, "192.0.2.3"))
        val xml = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><UDN>uuid:$id</UDN><friendlyName>Camera</friendlyName>
            <serviceList><service><serviceType>$st</serviceType></service></serviceList></device></root>"""
        assertEquals(SonyLanCamera(id, "Camera", host), sonyLanCamera(xml, host))
        assertNull(sonyLanCamera(xml.replace(st, "other-service"), host))
        assertNull(sonyLanCamera("<!DOCTYPE root>$xml", host))
        val wire = Buffer().writeUtf8("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n<r/>\r\n0\r\n\r\n")
        assertEquals("<r/>", readSonyDescriptionBody(wire))
    }

    @Test fun sonyHotspotIgnoresTheAndroidPlaceholderButKeepsRealLocalAddresses() {
        assertNull(SonyControlData.bssid("02:00:00:00:00:00".toByteArray()))
        assertEquals("02:00:00:00:00:01", SonyControlData.bssid("02:00:00:00:00:01".toByteArray()))
    }

    @Test fun cameraRawAndJpegShareCaptureButNeverFileIdentity() {
        fun photo(name: String, camera: String) = requireNotNull(cameraPhoto(JSONObject().apply {
            put("name", name); put("storage", 1); put("parent", 2); put("size", 5000)
            put("captured", "20261110T120000"); put("width", 6000); put("height", 4000)
        }, 42, camera, camera)).asset
        val arw = photo("DSC00001.ARW", "ILCE-7M5:123")
        val jpeg = photo("DSC00001.JPG", "ILCE-7M5:123")
        assertEquals(MediaFormat.RAW, arw.format)
        assertEquals("image/x-sony-arw", photoMimeType(arw.name))
        assertEquals(arw.cameraCaptureKey, jpeg.cameraCaptureKey)
        assertNotEquals(arw.cameraFileKey, jpeg.cameraFileKey)
        assertNotEquals(arw.id, photo(arw.name, "ILCE-7CM2:456").id)
    }

    @Test fun sonyDoesNotInheritNikonBluetoothDownloadsOrCollidingOperations() {
        assertEquals(CameraMake.SONY, ptpCameraMake("Sony Corporation", 6))
        assertEquals(CameraMake.NIKON, ptpCameraMake("Nikon Corporation", 10))
        val commands = previewCommands(true, setOf(GET_FHD_PICTURE, GET_LSS_IMAGE), false, emptySet())
        assertEquals(listOf(GET_THUMB), commands.map { it.operation })
        assertFalse(AppSettings(autoDownload = true).withCamera(SavedCamera("1", CameraMake.SONY, "Sony", "sony")).automaticEnabled)
        assertTrue(AppSettings(autoDownload = true).automaticEnabled)
    }
}
