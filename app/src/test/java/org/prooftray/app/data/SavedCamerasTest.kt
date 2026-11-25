package org.prooftray.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.prooftray.app.camera.CameraMake
import org.prooftray.app.domain.AppSettings

class SavedCamerasTest {
    @Test fun migrationPreservesBothSlotsAndSelectionWithoutInventingConnectionTimes() {
        val legacy = mapOf("controlAddress" to "nikon", "transferAddress" to "classic", "controlName" to "Z6III", "controlSerial" to "123",
            "sonyName" to "α7 V", "sonyAddress" to "sony", "sonySelected" to true)
        val migrated = AppSettings().migrateLegacyCameras(legacy)
        val saved = decodeCameras(encodeCameras(migrated.cameras))
        assertEquals(migrated.cameras, saved)
        assertEquals("sony", migrated.selectedAddress)
        val nikon = saved.single { it.make == CameraMake.NIKON }
        assertNull(nikon.lastConnectedAt)
        assertEquals("123", nikon.serial)
        assertEquals("classic", nikon.transferAddress)
    }

    @Test fun pairingSameModelTwiceAndRecoveringARotatedAddressRetainsBothBodies() {
        val first = AppSettings().withPairedCamera(CameraMake.NIKON, "Z6III", "ble-A", "classic-A", "0123", null, 1)
        val cameraA = requireNotNull(first.selectedCamera).copy(alias = "主机")
        val second = first.withCamera(cameraA).withPairedCamera(CameraMake.NIKON, "Z6III", "ble-B", "classic-B", "456", null, 2)
        val cameraB = requireNotNull(second.selectedCamera)
        val otherModel = second.withPairedCamera(CameraMake.NIKON, "Zf", "ble-C", "classic-C", "123", null, 2)
        assertEquals(3, otherModel.cameras.size)
        val restored = second.withPairedCamera(CameraMake.NIKON, "Z6III", "rotated", "classic-A", "123", cameraA.id, 3)
        assertEquals(2, restored.cameras.size)
        assertEquals(cameraB, restored.cameras.single { it.id == cameraB.id })
        assertEquals(cameraA.id, restored.selectedCameraId)
        assertEquals("主机", restored.selectedName)
        assertEquals("Z6III", restored.controlName)
        assertThrows(IllegalStateException::class.java) {
            restored.withPairedCamera(CameraMake.NIKON, "Z6III", "ble-B", "classic-B", "456", cameraA.id, 4)
        }
    }
}
