package org.prooftray.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.prooftray.app.camera.CameraMake
import org.prooftray.app.camera.cameraSerial
import org.prooftray.app.camera.cameraSerialMatches
import org.prooftray.app.domain.AppSettings
import org.prooftray.app.domain.SavedCamera

internal val legacyCameraKeys = listOf("controlAddress", "controlName", "transferAddress", "controlSerial", "sonyAddress", "sonyName", "sonySelected")

internal fun AppSettings.migrateLegacyCameras(values: Map<String, *>): AppSettings {
    fun value(key: String) = (values[key] as? String).orEmpty()
    val nikon = SavedCamera(make = CameraMake.NIKON, name = value("controlName"), controlAddress = value("controlAddress"),
        transferAddress = value("transferAddress"), serial = value("controlSerial"))
    val sony = SavedCamera(make = CameraMake.SONY, name = value("sonyName"), controlAddress = value("sonyAddress"))
    val saved = listOf(nikon, sony).filter { it.controlAddress.isNotBlank() || it.transferAddress.isNotBlank() ||
        it.name.isNotBlank() || it.serial.isNotBlank() }
    val previouslySelected = if (values["sonySelected"] == true) sony else nikon
    return copy(cameras = saved, selectedCameraId = previouslySelected.id.takeIf { previouslySelected in saved })
}

internal fun encodeCameras(cameras: List<SavedCamera>): String = JSONArray().apply {
    cameras.forEach { camera ->
        put(JSONObject().put("id", camera.id).put("make", camera.make.name).put("name", camera.name)
            .put("controlAddress", camera.controlAddress).put("transferAddress", camera.transferAddress).put("serial", camera.serial)
            .put("alias", camera.alias).put("lastConnectedAt", camera.lastConnectedAt).put("wifiSourceId", camera.wifiSourceId))
    }
}.toString()

internal fun decodeCameras(encoded: String): List<SavedCamera> {
    val array = JSONArray(encoded)
    return List(array.length()) { index ->
        val item = array.getJSONObject(index)
        SavedCamera(id = item.getString("id"), make = CameraMake.valueOf(item.getString("make")), name = item.optString("name"),
            controlAddress = item.optString("controlAddress"), transferAddress = item.optString("transferAddress"),
            serial = item.optString("serial"), alias = item.optString("alias"), lastConnectedAt = item.optLong("lastConnectedAt").takeIf { it > 0 },
            wifiSourceId = item.optString("wifiSourceId"))
    }
}

/** A BLE address can change. A verified body serial or a saved transport address can identify a record, a model name cannot. */
internal fun AppSettings.withPairedCamera(make: CameraMake, name: String, controlAddress: String, transferAddress: String,
    serial: String, knownId: String?, connectedAt: Long): AppSettings {
    require(controlAddress.isNotBlank())
    val verifiedSerial = cameraSerial(serial)
    val candidates = cameras.filter { camera ->
        if (camera.make != make) return@filter false
        val savedSerial = cameraSerial(camera.serial)
        val classicKnownOnBoth = transferAddress.isNotBlank() && camera.transferAddress.isNotBlank()
        camera.id == knownId || camera.controlAddress.equals(controlAddress, true) ||
            (classicKnownOnBoth && camera.transferAddress.equals(transferAddress, true)) ||
            (!classicKnownOnBoth && verifiedSerial != null && savedSerial != null && cameraSerialMatches(verifiedSerial, savedSerial))
    }
    check(candidates.size <= 1) { "机身信息与多条相机记录冲突，请核对后重试" }
    val existing = candidates.singleOrNull()
    if (knownId != null) check(existing?.id == knownId) { "所选相机记录已变化，请重新选择相机" }
    check(cameraSerialMatches(verifiedSerial, cameraSerial(existing?.serial.orEmpty()))) { "机身序列号与已保存相机不符，请重新选择相机" }
    val camera = existing ?: SavedCamera(make = make, name = name, controlAddress = controlAddress)
    return withCamera(camera.copy(name = name.ifBlank { camera.name }, controlAddress = controlAddress, lastConnectedAt = connectedAt,
        transferAddress = transferAddress.ifBlank { camera.transferAddress }, serial = verifiedSerial ?: camera.serial))
}
