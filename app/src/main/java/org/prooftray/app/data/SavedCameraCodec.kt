package org.prooftray.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.prooftray.app.domain.*
import org.prooftray.app.camera.cameraSerial
import java.util.UUID

internal object SavedCameraCodec {
    const val KEY = "savedCameraRegistry"
    private val address = SavedCamera.validAddress

    fun migrate(old: AppSettings): SavedCameras {
        val serial = cameraSerial(old.controlSerial).orEmpty()
        val hasNikon = listOf(old.controlAddress, old.transferAddress, old.controlName, serial).any(String::isNotBlank)
        // v0.11.1 could change the classic address independently of the BLE association.
        val nikon = if (hasNikon) SavedCamera(CameraBrand.NIKON, old.controlAddress, old.controlName,
            old.transferAddress, serial, pending = true) else null
        val sony = if (old.sonyAddress.isBlank() && old.sonyName.isBlank()) null
            else SavedCamera(CameraBrand.SONY, old.sonyAddress, old.sonyName, pending = !address.matches(old.sonyAddress))
        return SavedCameras(listOfNotNull(nikon, sony), (if (old.sonySelected) sony else nikon)?.id)
    }

    fun encode(value: SavedCameras): String {
        check(!value.readError)
        return JSONObject().put("version", 2).put("selected", value.selectedId ?: JSONObject.NULL)
            .put("devices", JSONArray().apply {
                value.devices.forEach { d -> put(JSONObject().put("id", d.id).put("brand", d.brand.name)
                    .put("address", d.address).put("name", d.name).put("transfer", d.transfer)
                    .put("serial", d.serial).put("model", d.model).put("pending", d.pending).put("bleModel", d.bleModel)) }
            }).toString()
    }

    // Preserve the original preference verbatim on malformed or newer data. Readable rows remain visible.
    fun decode(raw: Any?): SavedCameras {
        val devices = mutableListOf<SavedCamera>()
        return try {
            val root = JSONObject(raw as String)
            val version = root.getInt("version")
            require(version in 1..2)
            val rows = root.getJSONArray("devices")
            var damaged = false
            for (i in 0 until rows.length()) {
                try {
                    val row = rows.getJSONObject(i)
                    val id = row.getString("id")
                    require(UUID.fromString(id).toString() == id && devices.none { it.id == id })
                    val brand = CameraBrand.valueOf(row.getString("brand"))
                    val d = SavedCamera(brand, row.getString("address"),
                        row.getString("name"), row.getString("transfer"), row.getString("serial"), row.getString("model"),
                        row.getBoolean("pending") || (version == 1 && brand == CameraBrand.NIKON), id,
                        if (version == 2) row.getString("bleModel") else "")
                    require(d.pending || (d.hasControlAddress && (d.brand != CameraBrand.NIKON || address.matches(d.transfer))))
                    require(!d.hasControlAddress || devices.none { it.brand == d.brand && it.address.equals(d.address, true) })
                    devices.add(d)
                } catch (_: Exception) { damaged = true }
            }
            val selected = if (root.isNull("selected")) null else root.getString("selected")
            SavedCameras(devices, selected?.takeIf { id -> devices.any { it.id == id } },
                damaged || !root.has("selected") || (selected != null && devices.none { it.id == selected }))
        } catch (_: Exception) { SavedCameras(devices, readError = true) }
    }
}
