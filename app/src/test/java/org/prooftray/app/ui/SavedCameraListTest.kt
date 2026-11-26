package org.prooftray.app.ui

import org.junit.Assert.*
import org.junit.Test
import org.prooftray.app.camera.*
import org.prooftray.app.domain.*

class SavedCameraListTest {
    private val a = SavedCamera(CameraBrand.NIKON, "AA", "same")
    private val b = SavedCamera(CameraBrand.SONY, "BB", "same")

    @Test fun bluetoothControlAndWifiHaveSeparateOwners() {
        val camera = CameraState(connected = true, connectedId = b.id)
        val ble = BleState(authenticated = true, connectedAddress = a.address)
        assertTrue(deviceLinks(a, camera, ble).bluetooth)
        assertFalse(deviceLinks(a, camera, ble).wifi)
        assertTrue(deviceLinks(a, camera, ble).otherLive)
        assertTrue(deviceLinks(b, camera, ble).wifi)
    }
    @Test fun permissionAndFailureBelongToOneRow() {
        val camera = CameraState(accessRequest = SavedCameraRequest("token", b.id, a.id, 1))
        assertTrue(deviceLinks(b, camera, BleState()).awaiting)
        assertFalse(deviceLinks(a, camera, BleState()).awaiting)
        val failed = camera.copy(accessRequest = null, failedId = b.id, deviceError = "timeout")
        assertEquals("timeout", deviceLinks(b, failed, BleState()).failure)
        assertNull(deviceLinks(a, failed, BleState()).failure)
        assertFalse(deviceLinks(b, failed, BleState()).wifi)
    }
    @Test fun manualWifiSessionCannotLabelSavedDeviceConnected() {
        val links = deviceLinks(b, CameraState(connected = true), BleState())
        assertFalse(links.wifi)
        assertTrue(links.otherLive)
    }
    @Test fun obsoleteOrCancelledAttemptDoesNotShowAsConnected() {
        val before = CameraState(connecting = true, attemptId = b.id)
        assertTrue(deviceLinks(b, before, BleState()).attempting)
        val after = before.disconnected(2)
        assertFalse(deviceLinks(b, after, BleState()).attempting)
        assertFalse(deviceLinks(b, after, BleState()).wifi)
        assertNull(deviceLinks(b, after, BleState()).failure)
    }
}
