package org.prooftray.app.camera

import org.junit.Assert.*
import org.junit.Test

class SavedCameraRequestTest {
    private val request = SavedCameraRequest("token", "B", "A", 3)

    @Test fun grantTargetsRequestedDevice() {
        assertTrue(request.current("token", "A", 3))
        assertEquals("B", request.targetId)
    }

    @Test fun bluetoothCanBeRequestedForAnExistingWifiSession() {
        val wifi = CameraState(connected = true, connectedId = "B", sourceId = "camera-source")
        assertTrue(wifi.canRequestSaved("B", SavedCameraChannel.BLUETOOTH))
        assertFalse(wifi.canRequestSaved("B", SavedCameraChannel.WIFI))
        val bluetooth = request.copy(channel = SavedCameraChannel.BLUETOOTH)
        assertFalse(wifi.copy(controlAttempt = bluetooth).canRequestSaved("C", SavedCameraChannel.WIFI))
        assertFalse(wifi.copy(accessRequest = bluetooth).canRequestSaved("B", SavedCameraChannel.BLUETOOTH))
    }

    @Test fun obsoleteGrantCannotStartAnotherSession() {
        assertFalse(request.current("old", "A", 3))
        assertFalse(request.current("token", "B", 3))
        assertFalse(request.current("token", "A", 4))
    }

    @Test fun sameNameIsInsufficientAfterAddressChange() {
        assertTrue(runCatching { requireNikonIdentity("A", "B", null, null) }.isFailure)
        assertTrue(runCatching { requireNikonIdentity("A", "B", "123", null) }.isFailure)
        requireNikonIdentity("A", "B", "00123", "123")
    }

    @Test fun duplicateRequestsAreRejectedWhileWaitingConnectingOrReady() {
        assertTrue(CameraState().canRequestSaved("B"))
        assertFalse(CameraState(accessRequest = request).canRequestSaved("B"))
        assertFalse(CameraState(accessRequest = request).canRequestSaved("C"))
        assertFalse(CameraState(connecting = true, attemptId = "B").canRequestSaved("B"))
        assertFalse(CameraState(connected = true, connectedId = "B").canRequestSaved("B"))
    }

    @Test fun wifiSessionNeedsVerifiedRouteOrMatchingBodySerial() {
        assertFalse(verifiedWifiTarget(null, null, false))
        assertFalse(verifiedWifiTarget("123", null, true))
        assertFalse(verifiedWifiTarget("123", "456", true))
        assertTrue(verifiedWifiTarget("00123", "123", false))
        assertTrue(verifiedWifiTarget(null, null, true))
    }

    @Test fun existingClassicBondNeedsExplicitAssociation() {
        val paired = setOf("A", "B")
        assertFalse(confirmedClassicAddress("B", null, paired))
        assertFalse(confirmedClassicAddress("B", "A", paired))
        assertTrue(confirmedClassicAddress("B", "b", paired))
        assertTrue(confirmedClassicAddress("C", null, paired))
    }

    @Test fun knownAddressCannotOverrideWrongSerial() {
        assertTrue(runCatching { requireNikonIdentity("A", "A", "123", "456") }.isFailure)
        requireNikonIdentity("A", "A", null, null)
    }

    @Test fun legacyClassicBondNeedsMatchingSerialsFromBothChannels() {
        requireNikonClassicIdentity("00123", "123")
        for ((ble, classic) in listOf("123" to "456", "123" to "unknown", "" to "123", null to null)) {
            assertTrue(runCatching { requireNikonClassicIdentity(ble, classic) }.isFailure)
        }
    }

    @Test fun searchSwitchNeedsConsentForTheCurrentLiveCameraAndNotForTheSameBody() {
        val live = CameraState(generation = 3, sonyControlAddress = "AA").changeContext(BleState(), "A")
        val switching = live.switchesDevice("B", CameraMake.SONY, "BB")
        assertTrue(switching)
        assertFalse(live.switchesDevice("A", CameraMake.SONY, "aa"))
        assertTrue(live.switchesDevice("B", CameraMake.NIKON, "AA"))
        assertTrue(runCatching { live.requireApproval(switching, null) }.isFailure)
        live.requireApproval(switching, live)
        assertTrue(runCatching { live.copy(sonyAddress = "CC").requireApproval(true, live) }.isFailure)
    }
}
