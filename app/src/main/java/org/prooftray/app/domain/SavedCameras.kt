package org.prooftray.app.domain

import java.util.UUID
import org.prooftray.app.camera.cameraSerial
import org.prooftray.app.camera.cameraSerialMatches

enum class CameraBrand { NIKON, SONY }

data class SavedCamera(
    val brand: CameraBrand, val address: String, val name: String,
    val transfer: String = "", val serial: String = "", val model: String = "",
    val pending: Boolean = false, val id: String = UUID.randomUUID().toString(),
    val bleModel: String = ""
) {
    val title get() = name.ifBlank { model.ifBlank { if (brand == CameraBrand.NIKON) "尼康相机" else "索尼相机" } }
    val hasControlAddress get() = validAddress.matches(address)

    companion object {
        internal val validAddress = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
    }
}

data class SavedCameras(val devices: List<SavedCamera> = emptyList(), val selectedId: String? = null,
                        val readError: Boolean = false) {
    val selected get() = devices.find { it.id == selectedId }

    fun select(id: String): SavedCameras {
        require(devices.any { it.id == id }) { "相机关联已移除" }
        return copy(selectedId = id)
    }

    fun remove(id: String) = copy(devices = devices.filterNot { it.id == id }, selectedId = selectedId?.takeUnless { it == id })

    fun rebind(camera: SavedCamera, id: String): SavedCameras {
        val prior = devices.single { it.id == id }
        require(prior.pending && prior.brand == camera.brand) { "所选机身与待确认的关联不符" }
        val expectedSerial = cameraSerial(prior.serial)
        val actualSerial = cameraSerial(camera.serial)
        val sameAddress = prior.hasControlAddress && prior.address.equals(camera.address, true)
        val sameBody = expectedSerial != null && actualSerial != null && cameraSerialMatches(expectedSerial, actualSerial)
        require(sameBody || (expectedSerial == null && (sameAddress || !prior.hasControlAddress))) {
            "无法确认所选机身属于这条旧关联，已保留原记录"
        }
        return save(camera, id)
    }

    fun chooseTransferDevice(id: String, address: String): SavedCameras {
        require(id == selectedId && selected?.brand == CameraBrand.NIKON) { "所选尼康相机已变化" }
        require(address.isEmpty() || SavedCamera.validAddress.matches(address)) { "无效的经典蓝牙传图地址" }
        val device = checkNotNull(selected)
        if (device.transfer.equals(address, true)) return this
        require(address.isEmpty() || devices.none {
            it.id != id && it.brand == CameraBrand.NIKON && it.address.equals(address, true)
        }) { "该地址属于另一台相机的控制通道" }
        return save(device.copy(transfer = address, pending = true), id)
    }

    fun save(camera: SavedCamera, expectedId: String? = null): SavedCameras {
        check(!readError) { "设备记录无法读取，已保留原数据" }
        val sameBrand = devices.filter { it.brand == camera.brand }
        val existing = sameBrand.find { it.address.equals(camera.address, true) && camera.address.isNotBlank() }
        val serial = cameraSerial(camera.serial)
        val sameBody = sameBrand.filter {
            val previousSerial = cameraSerial(it.serial)
            val sameModel = (camera.model.isNotBlank() && camera.model.equals(it.model, true)) ||
                (camera.bleModel.isNotBlank() && camera.bleModel.equals(it.bleModel, true))
            sameModel && serial != null && previousSerial != null && cameraSerialMatches(serial, previousSerial)
        }
        require(sameBody.size <= 1) { "机身身份与多条记录相符，请重新确认" }
        val prior = expectedId?.let { id -> devices.single { it.id == id } } ?: existing ?: sameBody.singleOrNull()
        require(sameBody.isEmpty() || sameBody.single().id == prior?.id) { "机身身份与另一条记录冲突" }
        require(existing == null || existing.id == prior?.id) { "设备地址与另一条记录冲突" }
        require(camera.transfer.isBlank() || sameBrand.none { it.id != prior?.id && it.transfer.equals(camera.transfer, true) }) {
            "传图地址已关联到另一台相机"
        }
        require(prior == null || prior.brand == camera.brand) { "相机品牌不一致" }
        require(cameraSerialMatches(prior?.serial?.let(::cameraSerial), serial)) { "机身序列号与保存记录不符" }
        val next = camera.copy(id = prior?.id ?: camera.id, model = camera.model.ifBlank { prior?.model.orEmpty() },
            serial = serial ?: prior?.serial?.let(::cameraSerial).orEmpty(),
            bleModel = camera.bleModel.ifBlank { prior?.bleModel.orEmpty() })
        return copy(devices = if (prior == null) devices + next else devices.map { if (it.id == prior.id) next else it }, selectedId = next.id)
    }

    fun project(settings: AppSettings): AppSettings {
        val active = selected.takeUnless { readError }
        val nikon = active?.takeIf { it.brand == CameraBrand.NIKON }
        val sony = active?.takeIf { it.brand == CameraBrand.SONY }
        return settings.copy(cameras = this, controlAddress = nikon?.address.orEmpty(), controlName = nikon?.name.orEmpty(),
            controlSerial = nikon?.serial.orEmpty(), transferAddress = nikon?.transfer.orEmpty(), sonySelected = sony != null,
            sonyAddress = sony?.address.orEmpty(), sonyName = sony?.name.orEmpty())
    }
}
