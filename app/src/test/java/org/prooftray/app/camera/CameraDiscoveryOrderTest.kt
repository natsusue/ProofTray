package org.prooftray.app.camera

import org.junit.Assert.*
import org.junit.Test

class CameraDiscoveryOrderTest {
    @Test fun interleavedSonyAdvertisementsUpdateTheOriginalRows() {
        val first = BleCamera("AA", "ILCE-7CM2", make = CameraMake.SONY)
        val second = BleCamera("BB", "ILCE-7M5", make = CameraMake.SONY)
        var state = BleState().withDiscoveredCamera(first).withDiscoveredCamera(second)
        repeat(5) { round ->
            val update = first.copy(pairingAvailable = round % 2 == 0)
            state = state.withDiscoveredCamera(update)
            assertEquals(listOf(update, second), state.cameras)
            assertSame(state, state.withDiscoveredCamera(second))
        }
    }

    @Test fun identicalNamesAndScanLimitUseDeviceAddresses() {
        var state = BleState()
        for (i in 1..31) state = state.withDiscoveredCamera(BleCamera("addr$i", "Camera"))
        assertEquals((2..31).map { "addr$it" }, state.cameras.map { it.address })
        state = state.withDiscoveredCamera(BleCamera("addr2", "Updated", classic = true))
        assertEquals(30, state.cameras.size)
        assertTrue(state.cameras.first().classic)
    }
}
