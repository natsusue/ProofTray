package org.prooftray.app.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.prooftray.app.camera.CameraChangeContext
import org.prooftray.app.camera.applyClassicDeviceChange
import org.prooftray.app.domain.CameraBrand
import org.prooftray.app.domain.SavedCamera

class SavedCameraRepositoryTest {
    private val a = "01:02:03:04:05:06"
    private val b = "11:12:13:14:15:16"
    private val c = "21:22:23:24:25:26"

    // Model SharedPreferences' edit/apply boundary so a new repository reads only published keys.
    private class Store(val values: MutableMap<String, Any> = mutableMapOf()) {
        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any>()
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putBoolean", "putInt" -> { changes[args[0] as String] = args[1]; proxy }
                    "apply" -> { values.putAll(changes); null }
                    else -> error(method.name)
                }
            } as SharedPreferences.Editor
        }

        fun open(): AppSettingsRepository {
            val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getAll" -> values.toMap()
                    "contains" -> values.containsKey(args[0])
                    "getString", "getBoolean" -> values[args[0]] ?: args[1]
                    "edit" -> editor()
                    else -> error(method.name)
                }
            } as SharedPreferences
            return AppSettingsRepository(prefs)
        }
    }

    @Test fun migrationWritesSelectionAndProjectionTogether() {
        val store = Store(mutableMapOf("controlAddress" to a, "controlSerial" to "1234", "transferAddress" to b,
            "sonyAddress" to c, "sonySelected" to true))
        val migrated = store.open().state.value.cameras
        assertEquals(2, migrated.devices.size)
        assertEquals(c, store.values["sonyAddress"])
        assertEquals("", store.values["controlAddress"])
        assertEquals(migrated, store.open().state.value.cameras)
        val nikon = migrated.devices.single { it.brand == CameraBrand.NIKON }
        store.open().changeCameras { it.select(nikon.id) }
        assertEquals(b, store.values["transferAddress"])
        assertEquals("", store.values["sonyAddress"])
    }

    @Test fun staleDowngradeKeysCannotResurrectDeletedDevice() {
        val store = Store(mutableMapOf("sonySelected" to true, "sonyAddress" to a))
        val repo = store.open()
        repo.changeCameras { it.remove(it.selectedId!!) }
        store.values["sonyAddress"] = a
        store.values["sonySelected"] = true
        val upgraded = store.open().state.value
        assertTrue(upgraded.cameras.devices.isEmpty())
        assertEquals("", upgraded.selectedAddress)
    }

    @Test fun unrelatedPreferenceUpdateCannotReplaceDamagedRegistry() {
        val store = Store(mutableMapOf(SavedCameraCodec.KEY to "{damaged", "controlAddress" to a))
        val repo = store.open()
        repo.update { it.copy(locationSync = true) }
        assertTrue(repo.state.value.cameras.readError)
        assertTrue(runCatching { repo.changeCameras { it.save(SavedCamera(CameraBrand.SONY, b, "Sony")) } }.isFailure)
        assertEquals("{damaged", store.values[SavedCameraCodec.KEY])
        assertEquals(a, store.values["controlAddress"])
        assertEquals(true, store.values["locationSync"])
    }

    @Test fun invalidOrUnconfirmedClassicAddressCannotDisconnectTheLiveCamera() {
        val repo = Store().open()
        val first = SavedCamera(CameraBrand.NIKON, a, "A", b, "123")
        val second = SavedCamera(CameraBrand.NIKON, c, "B", "31:32:33:34:35:36", "456")
        repo.changeCameras { it.save(first).save(second).select(first.id) }
        val origin = CameraChangeContext(3, first.id, true, first.id, null, a)
        val free = "41:42:43:44:45:46"
        var dropped = false
        val disconnect = { dropped = true }
        for ((address, approval) in listOf(second.transfer to origin, second.address to origin, free to null)) {
            assertTrue(runCatching { repo.applyClassicDeviceChange(address, origin, approval, { true }, disconnect) }.isFailure)
            assertEquals(first, repo.state.value.cameras.selected)
            assertFalse(dropped)
        }
        assertTrue(runCatching { repo.applyClassicDeviceChange(free, origin, origin, { false }, disconnect) }.isFailure)
        assertEquals(first, repo.state.value.cameras.selected)
        repo.applyClassicDeviceChange(b, origin, origin, { true }, disconnect)
        assertFalse(dropped)
        repo.applyClassicDeviceChange(free, origin, origin, { true }) {
            assertEquals(free, repo.state.value.cameras.selected?.transfer)
            disconnect()
        }
        assertTrue(dropped)
        assertEquals(first.id, repo.state.value.cameras.selectedId)
        assertTrue(repo.state.value.cameras.selected!!.pending)
    }
}
