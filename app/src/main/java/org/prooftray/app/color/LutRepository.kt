package org.prooftray.app.color

import org.prooftray.app.io.writeBytesAtomically
import org.prooftray.app.io.readBounded
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File
import org.prooftray.app.util.sha256Hex
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONException
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.ColorPreset
import org.prooftray.app.domain.LutAsset
import org.prooftray.app.domain.LutUsage
import org.prooftray.app.domain.LutImportChoice
import org.prooftray.app.domain.LutPreviewData
import org.prooftray.app.domain.validateLutName

data class ColorImportResult(val selectableId: String?, val notice: String? = null)

internal class PreviewColorTable(val data: LutPreviewData, val bakedHandle: Long)

class LutRepository private constructor(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val root = File(context.filesDir, "color-library").apply { mkdirs() }
    private val lock = Any()
    private val handles = linkedMapOf<String, Long>()
    private val previewTables = linkedMapOf<ColorLookSettings, PreviewColorTable>()

    suspend fun luts(includeRemoved: Boolean = false): List<LutAsset> = withContext(Dispatchers.IO) { synchronized(lock) {
        root.listFiles()?.filter { it.name.endsWith(".lut.json") }?.mapNotNull { readTableMetadata(it) }
            ?.filter { includeRemoved || !it.removed }?.sortedBy { it.name } ?: emptyList()
    } }

    private fun readTableMetadata(file: File): LutAsset? = try {
        val json = LookCodec.checkedJson(file.readText())
        val implicit = if (json.has("implicitUnitDomain")) json.getBoolean("implicitUnitDomain") else null
        LutAsset(json.getString("id"), json.getString("name"), json.getInt("dimension"), json.getInt("size"), json.getString("sourceFormat"),
            LookCodec.decodeUsage(json.getJSONObject("defaultUsage")), implicit, json.optBoolean("removed", false))
    } catch (_: JSONException) { null } catch (_: IllegalArgumentException) { null }

    suspend fun presets(): List<ColorPreset> = withContext(Dispatchers.IO) { synchronized(lock) {
        presetRecords().mapNotNull { (_, json) ->
            runCatching { ColorPreset(json.getString("id"), json.getString("name"), LookCodec.decode(json.getJSONObject("look"))) }.getOrNull()
        }.sortedBy { it.name }
    } }

    private fun presetRecords(): List<Pair<File, JSONObject>> = root.listFiles().orEmpty().filter { it.name.endsWith(".preset.json") }
        .mapNotNull { file -> runCatching { file to LookCodec.checkedJson(file.readText()) }.getOrNull() }

    suspend fun describeImport(uri: Uri): LutImportChoice = withContext(Dispatchers.IO) {
        var name = "导入的 LUT"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)?.take(160) ?: name
        }
        LutImportChoice(uri.toString(), name)
    }

    suspend fun import(choice: LutImportChoice): ColorImportResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(choice.uri)
        val name = choice.name
        val coroutine = currentCoroutineContext()
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            stream.readBounded(32 * 1024 * 1024, "文件超过 32 MiB 导入上限") { coroutine.ensureActive() }
        } ?: error("无法读取 LUT 文件")
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in setOf("ptlook", "oblook", "json")) {
            importPreset(bytes, name)
        } else {
            val handle = if (extension == "png") loadHald(bytes) else {
                require(extension == "cube") { "请选择 .cube、HaldCLUT .png 或 .ptlook 预设。ZIP 请先解压。" }
                NativeColor.load(bytes, true)
            }
            try {
                val provenance = JSONObject().apply {
                    put("filename", name); put("license", "unspecified")
                    if (extension == "cube") {
                        val comments = bytes.toString(Charsets.UTF_8).lineSequence().map { it.trimStart() }
                            .filter { it.startsWith('#') }.take(64).joinToString("\n").take(8192)
                        put("comments", comments)
                    }
                }
                val usage = choice.usage
                usage.validateForPhoto()
                val asset = saveLut(handle, name.substringBeforeLast('.'), if (extension == "png") "HaldCLUT PNG" else "CUBE", provenance,
                    usage)
                val reason = asset.defaultUsage.unavailableReason
                ColorImportResult(asset.id.takeIf { reason == null }, reason ?: asset.domainWarning(usage) ?: asset.domainWarning())
            } finally { NativeColor.release(handle) }
        }
    }

    private fun loadHald(bytes: ByteArray): Long {
        require(bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "PNG 文件头无效" }
        require(bytes[24].toInt() == 8 && bytes[25].toInt() in setOf(2, 6)) { "当前支持 8 位 RGB/RGBA Hald PNG，暂不支持 16 位或索引色" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == bounds.outHeight && bounds.outWidth in (2..12).map { it * it * it }) {
            "Hald LUT 必须是边长等于 level³ 的方形图片，支持 level 2–12"
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
        }) ?: error("Hald PNG 解码失败")
        try {
            require(bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.colorSpace?.isSrgb == true) { "仅支持 sRGB Hald LUT" }
            return NativeColor.hald(bitmap)
        } finally { bitmap.recycle() }
    }

    /** Stores the table once per content hash. [defaultUsage] only applies to a new table; re-importing keeps the user's choice. */
    private fun saveLut(handle: Long, name: String, sourceFormat: String, provenance: JSONObject?, defaultUsage: LutUsage,
        embeddedImplicitDomain: Boolean? = null): LutAsset {
        val binary = NativeColor.pack(handle)
        val id = binary.sha256Hex()
        val info = NativeColor.values(handle, true)
        synchronized(lock) {
            val table = File(root, "$id.ptlut")
            if (!table.exists()) write(table, binary)
            val metadataFile = File(root, "$id.lut.json")
            val unitDomain = (2..4).all { info[it] == 0f && info[it + 3] == 1f }
            val implicit = if (!unitDomain) false else if (sourceFormat == ".ptlook") embeddedImplicitDomain else info[8] == 0f
            if (metadataFile.exists()) {
                val stored = requireNotNull(readTableMetadata(metadataFile)) { "已有 LUT 元数据无法读取" }
                if (stored.removed) updateRemoved(id, false)
                return stored.copy(implicitUnitDomain = implicit ?: stored.implicitUnitDomain, removed = false)
            }
            val asset = LutAsset(id, name.take(128), info[0].toInt(), info[1].toInt(), sourceFormat, defaultUsage, implicit)
            val metadata = JSONObject().apply {
                put("id", id); put("name", asset.name); put("dimension", asset.dimension); put("size", asset.size)
                put("sourceFormat", sourceFormat); put("provenance", cleanProvenance(provenance)); put("implicitUnitDomain", implicit)
                put("defaultUsage", LookCodec.encodeUsage(defaultUsage))
            }
            write(metadataFile, metadata.toString().toByteArray())
            return asset
        }
    }

    suspend fun setRemoved(id: String, removed: Boolean) = withContext(Dispatchers.IO) { synchronized(lock) { updateRemoved(id, removed) } }

    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) { synchronized(lock) {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "LUT 标识无效" }
        val title = validateLutName(name)
        val file = File(root, "$id.lut.json")
        val record = LookCodec.checkedJson(file.readText()).put("name", title)
        write(file, record.toString().toByteArray())
    } }

    private fun updateRemoved(id: String, removed: Boolean) {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "LUT 标识无效" }
        val file = File(root, "$id.lut.json")
        val record = LookCodec.checkedJson(file.readText()).put("removed", removed)
        write(file, record.toString().toByteArray())
    }

    /** Only app-owned copies are removed. The confirmation carries the exact set of related presets the user saw. */
    suspend fun deleteLut(id: String, confirmedPresets: Set<String>) = withContext(Dispatchers.IO) { synchronized(lock) {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "LUT 标识无效" }
        val references = presetRecords().filter { (_, record) -> record.optJSONObject("look")?.optString("lutId") == id }
        require(references.all { it.first.name.removeSuffix(".preset.json") in confirmedPresets }) {
            "关联预设已变化，请重新确认删除"
        }
        val metadata = File(root, "$id.lut.json")
        val records = (references.map { it.first } + metadata).filter { it.isFile }.map { it to it.readBytes() }
        val table = File(root, "$id.ptlut")
        try {
            // Delete the data last so failed record cleanup can restore its small JSON snapshots.
            for ((file, _) in records) check(file.delete() || !file.exists()) { "无法删除预设记录" }
            check(!table.exists() || table.delete()) { "无法删除 LUT 副本" }
        } catch (error: Exception) {
            for ((file, bytes) in records) if (!file.exists()) {
                try { write(file, bytes) } catch (restore: Exception) { error.addSuppressed(restore) }
            }
            throw error
        }
        handles.remove(id)?.let(NativeColor::release)
        Unit
    } }

    suspend fun deletePreset(id: String) = withContext(Dispatchers.IO) { synchronized(lock) {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "预设标识无效" }
        val file = File(root, "$id.preset.json")
        check(!file.exists() || file.delete()) { "无法删除预设" }
    } }

    suspend fun setDefault(id: String, usage: LutUsage) = withContext(Dispatchers.IO) { synchronized(lock) { updateDefault(id, usage) } }

    private fun updateDefault(id: String, usage: LutUsage) {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "LUT 标识无效" }
        usage.validateForPhoto()
        val file = File(root, "$id.lut.json")
        write(file, LookCodec.checkedJson(file.readText()).put("defaultUsage", LookCodec.encodeUsage(usage)).toString().toByteArray())
    }

    suspend fun savePreset(name: String, look: ColorLookSettings): String = withContext(Dispatchers.IO) {
        look.validate()
        storePreset(name, look)
    }

    private fun storePreset(name: String, look: ColorLookSettings): String = synchronized(lock) {
        require(name.isNotBlank()) { "预设名称不能为空" }
        if (look.lutId != null) canonical(look.lutId)
        val id = UUID.randomUUID().toString()
        val record = JSONObject().apply {
            put("id", id); put("name", name.trim().take(80)); put("look", LookCodec.encode(look.copy(name = name.trim().take(80))))
        }
        write(File(root, "$id.preset.json"), record.toString().toByteArray())
        id
    }

    suspend fun exportPreset(preset: ColorPreset, destination: Uri): Unit = withContext(Dispatchers.IO) {
        preset.look.validate()
        val payload = LookCodec.presetEnvelope(preset.name, preset.look).apply {
            if (preset.look.lutId != null) {
                val bytes = canonical(preset.look.lutId)
                put("lut", JSONObject().apply {
                    put("sha256", bytes.sha256Hex()); put("encoding", LookCodec.LUT_ENCODING)
                    put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    val metadata = LookCodec.checkedJson(File(root, "${preset.look.lutId}.lut.json").readText())
                    put("provenance", cleanProvenance(metadata.optJSONObject("provenance")))
                    if (metadata.has("implicitUnitDomain")) put("implicitUnitDomain", metadata.getBoolean("implicitUnitDomain"))
                })
            }
        }.toString(2).toByteArray()
        resolver.openOutputStream(destination, "wt")?.use { it.write(payload) } ?: error("无法保存预设文件")
    }

    private fun importPreset(bytes: ByteArray, fallbackName: String): ColorImportResult {
        val json = LookCodec.checkedJson(bytes.toString(Charsets.UTF_8))
        var look = LookCodec.decodePresetLook(json)
        val packed = json.optJSONObject("lut")
        var table: LutAsset? = null
        if (packed != null) {
            require(packed.getString("encoding") in LookCodec.LUT_ENCODINGS) { "预设内 LUT 编码无效" }
            val binary = Base64.decode(packed.getString("data"), Base64.DEFAULT)
            val hash = binary.sha256Hex()
            require(hash == packed.getString("sha256")) { "预设中的 LUT 校验失败" }
            require(look.lutId == null || look.lutId == hash) { "预设引用与内嵌 LUT 不一致" }
            val handle = NativeColor.load(binary, false)
            val saved = try {
                val implicit = if (packed.has("implicitUnitDomain")) packed.getBoolean("implicitUnitDomain") else null
                saveLut(handle, json.optString("name", fallbackName), ".ptlook", packed.optJSONObject("provenance"), look.usage, implicit)
            } finally { NativeColor.release(handle) }
            table = saved
            look = look.copy(lutId = saved.id)
        } else if (look.lutId != null) {
            require(File(root, "${look.lutId}.ptlut").exists()) { "预设引用的 LUT 缺失，请导入完整 .ptlook 文件" }
            val metadata = File(root, "${look.lutId}.lut.json")
            if (metadata.isFile) table = readTableMetadata(metadata)
        }
        val name = json.optString("name", fallbackName).take(80)
        val id = storePreset(name, look)
        val reason = look.usage.unavailableReason
        return ColorImportResult(id.takeIf { reason == null }, reason ?: table?.domainWarning(look.usage))
    }

    internal fun previewData(look: ColorLookSettings): PreviewColorTable = synchronized(lock) {
        look.validate()
        require(!look.usesLog) { "此配方不能烘焙为显示预览" }
        val key = look.copy(name = "")
        previewTables.remove(key)?.let { previewTables[key] = it; return it }
        val handle = NativeColor.bake(look.lutId?.let(::handle) ?: 0L, look.parameters(), look.adjustments.parameters(),
            checkNotNull(look.usage.input).nativeCode, checkNotNull(look.usage.output).nativeCode)
        val table = try {
            val data = LutPreviewData(UUID.randomUUID().toString(), 65, NativeColor.values(handle, false))
            PreviewColorTable(data, handle).also { previewTables[key] = it }
        } catch (error: Throwable) { NativeColor.release(handle); throw error }
        if (previewTables.size > 2) NativeColor.release(checkNotNull(previewTables.remove(previewTables.keys.first())).bakedHandle)
        table
    }

    /** A rendered bitmap needs no transform. GPU previews use the exact table currently bound to the shader. */
    internal fun histogram(bitmap: Bitmap, look: ColorLookSettings?, table: PreviewColorTable?, viewportWidth: Int, viewportHeight: Int): IntArray {
        fun sample(handle: Long) = NativeColor.histogram(handle, bitmap, bitmap.isPremultiplied, look?.parameters(), look?.adjustments?.parameters(),
            look?.usage?.input?.nativeCode ?: 0, look?.usage?.output?.nativeCode ?: 0, viewportWidth, viewportHeight)
        // Native sampling holds a shared reference while the next display table bakes or replaces this one.
        if (table != null) return sample(table.bakedHandle)
        val id = look?.lutId ?: return sample(0L)
        return synchronized(lock) { sample(handle(id)) }
    }

    fun apply(bitmap: Bitmap, look: ColorLookSettings) = synchronized(lock) {
        look.validate()
        require(!look.usesLog) { "Log 输入需要 RAW 原片" }
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) { "调色需要可写的 sRGB RGBA8 位图" }
        NativeColor.apply(look.lutId?.let(::handle) ?: 0L, bitmap, look.parameters(), look.adjustments.parameters(), bitmap.isPremultiplied,
            checkNotNull(look.usage.input).nativeCode, checkNotNull(look.usage.output).nativeCode)
    }

    private fun handle(id: String): Long {
        handles.remove(id)?.let { handles[id] = it; return it }
        val handle = NativeColor.load(canonical(id), false)
        handles[id] = handle
        while (handles.size > 2) {
            val oldest = handles.entries.first()
            handles.remove(oldest.key); NativeColor.release(oldest.value)
        }
        return handle
    }

    internal fun canonical(id: String): ByteArray = synchronized(lock) {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "LUT 标识无效" }
        val file = File(root, "$id.ptlut")
        require(file.isFile && file.length() <= 16 * 1024 * 1024) { "LUT 文件不存在或过大" }
        file.readBytes().also { require(it.sha256Hex() == id) { "LUT 文件内容已变化" } }
    }

    private fun write(file: File, bytes: ByteArray) = file.writeBytesAtomically(bytes)

    // Attribution supplied by the file author is retained as text; it is not a verified license grant.
    private fun cleanProvenance(source: JSONObject?): JSONObject = JSONObject().apply {
        put("license", source?.optString("license", "unspecified")?.take(4096) ?: "unspecified")
        for (key in listOf("filename", "author", "url", "comments")) {
            source?.optString(key)?.takeIf { it.isNotEmpty() }?.let { put(key, it.take(8192)) }
        }
    }

    companion object {
        @Volatile private var instance: LutRepository? = null
        fun get(context: Context): LutRepository = instance ?: synchronized(this) {
            instance ?: LutRepository(context.applicationContext).also { instance = it }
        }
    }
}
