package org.prooftray.app.data

import org.junit.Assert.*
import org.junit.Test
import org.prooftray.app.domain.*

class SavedCameraCodecTest {
    private val a = "01:02:03:04:05:06"
    private val b = "11:12:13:14:15:16"
    private val c = "21:22:23:24:25:26"
    private fun reload(registry: SavedCameras) = SavedCameraCodec.decode(SavedCameraCodec.encode(registry))

    @Test fun emptyInstall() = assertEquals(SavedCameras(), reload(SavedCameraCodec.migrate(AppSettings())))
    @Test fun migrateBothAndPreserveSelection() {
        val registry = SavedCameraCodec.migrate(AppSettings(controlAddress = a, transferAddress = b, controlSerial = "1234",
            sonySelected = true, sonyAddress = c))
        assertEquals(2, registry.devices.size)
        assertTrue(registry.devices.first().pending)
        assertEquals("1234", registry.devices.first().serial)
        assertEquals(c, registry.selected?.address)
        assertEquals(registry, reload(reload(registry)))
    }
    @Test fun incompleteNikonNeedsConfirmation() {
        assertTrue(SavedCameraCodec.migrate(AppSettings(transferAddress = b)).devices.single().pending)
        assertTrue(SavedCameraCodec.migrate(AppSettings(controlAddress = a, transferAddress = b, controlSerial = "unknown")).devices.single().pending)
    }
    @Test fun migrationCannotTrustABondFromAnotherBody() {
        val old = AppSettings(controlAddress = a, transferAddress = b, controlSerial = "1234")
        val registry = reload(SavedCameraCodec.migrate(old))
        assertTrue(registry.selected!!.pending)
        assertEquals(a, registry.selected!!.address)
        assertEquals(b, registry.selected!!.transfer)
        assertEquals("1234", registry.selected!!.serial)
    }

    @Test fun preservesEachLegacyIdentityClueAcrossRestarts() {
        for (old in listOf(AppSettings(controlSerial = "1234"), AppSettings(controlName = "Z"),
            AppSettings(controlAddress = a), AppSettings(transferAddress = b, controlSerial = "1234"))) {
            val registry = reload(reload(SavedCameraCodec.migrate(old)))
            val row = registry.devices.single()
            assertTrue(row.pending)
            assertEquals(old.controlSerial, row.serial)
            assertEquals(old.controlName, row.name)
            assertEquals(old.controlAddress, row.address)
            assertEquals(old.transferAddress, row.transfer)
        }
    }

    @Test fun verifiedSerialReplacesLegacyPlaceholderAndKeepsId() {
        for (placeholder in listOf("", "unknown", "N/A", "0000")) {
            val legacy = SavedCamera(CameraBrand.NIKON, a, "Z", b, placeholder, pending = true)
            val registry = reload(SavedCameras(listOf(legacy), legacy.id))
            val verified = SavedCamera(CameraBrand.NIKON, a, "Z", b, "1234", bleModel = "P1000")
            val rebound = reload(registry.rebind(verified, legacy.id)).selected!!
            assertEquals(legacy.id, rebound.id)
            assertEquals("1234", rebound.serial)
            assertFalse(rebound.pending)
        }
    }

    @Test fun rebindPreservesLegacyIdOnlyWithTheRecordedIdentityOrExplicitUnidentifiedRow() {
        val legacy = SavedCamera(CameraBrand.NIKON, "", "Z", serial = "00123", pending = true)
        val registry = SavedCameras(listOf(legacy), legacy.id)
        val candidate = SavedCamera(CameraBrand.NIKON, a, "Z", b, "123")
        assertEquals(legacy.id, registry.rebind(candidate, legacy.id).selectedId)
        for (wrong in listOf(candidate.copy(serial = "456"), candidate.copy(serial = ""),
            candidate.copy(brand = CameraBrand.SONY))) {
            assertTrue(runCatching { registry.rebind(wrong, legacy.id) }.isFailure)
            assertEquals(legacy, reload(registry).selected)
        }
        val nameOnly = legacy.copy(serial = "")
        assertEquals(nameOnly.id, SavedCameras(listOf(nameOnly), nameOnly.id).rebind(candidate, nameOnly.id).selectedId)
    }

    @Test fun sameNameCannotReplaceAnotherKnownBodyOrClaimItsRecord() {
        val legacy = SavedCamera(CameraBrand.NIKON, a, "Z", pending = true)
        val other = SavedCamera(CameraBrand.NIKON, b, "Z", c, "1234")
        val registry = SavedCameras(listOf(legacy, other), legacy.id)
        assertTrue(runCatching { registry.rebind(other, legacy.id) }.isFailure)
        assertTrue(runCatching {
            registry.rebind(other.copy(address = "31:32:33:34:35:36", transfer = c), legacy.id)
        }.isFailure)
        assertEquals(registry, reload(registry))
    }

    @Test fun editingAnOccupiedClassicAddressLeavesTheSelectedAssociationUntouched() {
        val first = SavedCamera(CameraBrand.NIKON, a, "Z", c, "1234")
        val second = SavedCamera(CameraBrand.NIKON, b, "Z", "31:32:33:34:35:36", "5678")
        val registry = SavedCameras(listOf(first, second), first.id)
        assertTrue(runCatching { registry.chooseTransferDevice(first.id, second.transfer) }.isFailure)
        assertEquals(first, registry.selected)
        val edited = registry.chooseTransferDevice(first.id, "41:42:43:44:45:46")
        assertTrue(edited.selected!!.pending)
        assertEquals(first.id, edited.selectedId)
        assertEquals(second, edited.devices.last())
    }

    @Test fun displayModelDoesNotReplaceTheBleCapabilityModel() {
        val original = SavedCamera(CameraBrand.NIKON, a, "Nikon", b, "1234", bleModel = "P1000")
        val registry = SavedCameras().save(original)
        val updated = reload(registry.save(original.copy(model = "COOLPIX P1000", bleModel = ""), original.id)).selected!!
        assertEquals("COOLPIX P1000", updated.model)
        assertEquals("P1000", updated.bleModel)
        assertFalse(org.prooftray.app.camera.needsBluetoothHandoff(updated.bleModel))
        assertTrue(org.prooftray.app.camera.needsBluetoothHandoff(""))
    }

    @Test fun earlierRegistryKeepsIdsButDoesNotInheritUnprovenNikonTrustOrBleModel() {
        val nikon = SavedCamera(CameraBrand.NIKON, a, "Z", b, "123", model = "P1000")
        val sony = SavedCamera(CameraBrand.SONY, c, "Sony")
        val stored = org.json.JSONObject(SavedCameraCodec.encode(SavedCameras(listOf(nikon, sony), nikon.id))).put("version", 1)
        val restored = SavedCameraCodec.decode(stored.toString())
        assertEquals(nikon.copy(pending = true), restored.selected)
        assertEquals(sony, restored.devices.last())
        assertEquals(restored, reload(restored))
    }
    @Test fun sameNameAndBrandAreDistinct() {
        var registry = SavedCameras()
        for (brand in CameraBrand.entries) for (address in listOf(a, b)) {
            registry = registry.save(SavedCamera(brand, address, "Camera", transfer = if (address == a) c else "31:32:33:34:35:36"))
        }
        assertEquals(4, reload(registry).devices.map { it.id }.distinct().size)
    }
    @Test fun updatesKeepIdAndRemovalSurvivesRestart() {
        val registry = SavedCameras().save(SavedCamera(CameraBrand.NIKON,a,"Camera"))
        val id = registry.selectedId!!
        assertEquals(id, registry.save(SavedCamera(CameraBrand.NIKON,a,"Updated")).selectedId)
        assertEquals(SavedCameras(), reload(registry.remove(id)))
    }
    @Test fun selectedProjectionCannotMixBrands() {
        val registry = SavedCameras().save(SavedCamera(CameraBrand.SONY,c,"Sony"))
        val projected = registry.project(AppSettings(controlAddress = a, transferAddress = b, controlSerial = "1234"))
        assertEquals("", projected.transferAddress)
        assertEquals("", projected.controlSerial)
        assertEquals(c, projected.selectedAddress)
    }
    @Test fun corruptRowPreservesReadableRowsAndBlocksWriting() {
        val registry = SavedCameras().save(SavedCamera(CameraBrand.SONY,c,"Sony"))
        val json = org.json.JSONObject(SavedCameraCodec.encode(registry))
        json.getJSONArray("devices").put(42)
        val recovered = SavedCameraCodec.decode(json.toString())
        assertTrue(recovered.readError)
        assertEquals(registry.devices, recovered.devices)
        assertTrue(runCatching { SavedCameraCodec.encode(recovered) }.isFailure)
    }
    @Test fun conflictingIdentityCannotReplaceKnownRecord() {
        val registry = SavedCameras().save(SavedCamera(CameraBrand.NIKON, a, "Z", b, "1234"))
        assertTrue(runCatching { registry.save(SavedCamera(CameraBrand.NIKON, a, "Z", b, "9999")) }.isFailure)
        assertEquals("1234", reload(registry).selected?.serial)
    }
    @Test fun verifiedModelAndSerialCanRestoreChangedAddress() {
        val original = SavedCamera(CameraBrand.NIKON, a, "Z", c, "00123", "Z6")
        val newer = SavedCamera(CameraBrand.NIKON, b, "Z", c, "123", "Z6")
        val migrated = SavedCameras().save(original).save(newer)
        assertEquals(original.id, migrated.devices.single().id)
        assertEquals(b, migrated.selected?.address)
    }
    @Test fun selectionsAndNonActiveRemovalPreserveOtherRecords() {
        val first = SavedCamera(CameraBrand.NIKON, a, "Z", b, "1234")
        val second = SavedCamera(CameraBrand.SONY, c, "Sony")
        val registry = SavedCameras().save(first).save(second)
        assertEquals(second, reload(registry.remove(first.id)).selected)
        val selected = registry.select(first.id).project(AppSettings())
        assertEquals(b, selected.transferAddress)
        assertEquals("", selected.sonyAddress)
    }
    @Test fun unknownVersionAndInvalidJsonAreReadOnly() {
        for (raw in listOf("{", "{\"version\":3,\"selected\":null,\"devices\":[]}", 3)) assertTrue(SavedCameraCodec.decode(raw).readError)
    }
}
