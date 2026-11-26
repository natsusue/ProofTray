package org.prooftray.app.color

import org.json.JSONObject
import org.prooftray.app.domain.COLOR_LOOK_VERSION
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.ColorAdjustments
import org.prooftray.app.domain.LutRange
import org.prooftray.app.domain.LutUsage

object LookCodec {
    const val PRESET_SCHEMA = "org.prooftray.look"
    const val PRESET_VERSION = COLOR_LOOK_VERSION
    const val LUT_ENCODING = "ptlut1-base64"
    val PRESET_SCHEMAS = setOf(PRESET_SCHEMA, "org.openbridge.look")
    val LUT_ENCODINGS = setOf(LUT_ENCODING, "oblut1-base64")

    fun encodeUsage(usage: LutUsage) = JSONObject().apply {
        put("input", usage.inputName); put("output", usage.outputName)
        put("inputRange", usage.inputRange.name); put("outputRange", usage.outputRange.name)
    }

    fun decodeUsage(json: JSONObject) = LutUsage(json.getString("input"), json.getString("output"),
        LutRange.valueOf(json.getString("inputRange")), LutRange.valueOf(json.getString("outputRange"))).also {
        if (it.unavailableReason == null) it.validateForPhoto()
    }

    fun encode(look: ColorLookSettings): JSONObject = JSONObject().apply {
        put("name", look.name); put("lutId", look.lutId ?: JSONObject.NULL); put("strength", look.strength.toDouble())
        put("exposure", look.exposure.toDouble()); put("contrast", look.contrast.toDouble())
        put("saturation", look.saturation.toDouble()); put("warmth", look.warmth.toDouble())
        put("lutUsage", encodeUsage(look.usage))
        if (look.usesLog || look.usage.unavailableReason != null) put("logGainEv", look.logGainEv.toDouble())
        put("renderVersion", COLOR_LOOK_VERSION)
        put("tint", look.tint.toDouble())
        put("adjustments", ColorAdjustmentsCodec.encode(look.adjustments))
    }

    fun presetEnvelope(name: String, look: ColorLookSettings): JSONObject = JSONObject().apply {
        put("schema", PRESET_SCHEMA); put("version", PRESET_VERSION); put("name", name); put("look", encode(look))
    }

    fun decodePresetLook(json: JSONObject): ColorLookSettings {
        require(json.optString("schema") in PRESET_SCHEMAS) { "请选择 .ptlook 预设，暂不支持 Lightroom XMP。" }
        require((json.opt("version") as? Number)?.toDouble() == PRESET_VERSION.toDouble()) { "仅支持版本 $PRESET_VERSION 的调色预设" }
        return decode(json.getJSONObject("look"))
    }

    fun decode(json: JSONObject): ColorLookSettings {
        require((json.opt("renderVersion") as? Number)?.toDouble() == COLOR_LOOK_VERSION.toDouble()) { "仅支持版本 $COLOR_LOOK_VERSION 的编辑配方" }
        fun number(key: String, fallback: Float): Float {
            if (!json.has(key)) return fallback
            val value = json.get(key)
            require(value is Number) { "预设参数 $key 必须是数值" }
            return value.toFloat()
        }
        return ColorLookSettings(
            name = json.optString("name", "自定义").take(128), lutId = if (json.isNull("lutId")) null else json.getString("lutId"),
            strength = number("strength", 1f), exposure = number("exposure", 0f), contrast = number("contrast", 0f),
            saturation = number("saturation", 1f), warmth = number("warmth", 0f),
            usage = decodeUsage(json.getJSONObject("lutUsage")), logGainEv = number(if (json.has("logGainEv")) "logGainEv" else "nlogGainEv", 0f),
            tint = number("tint", 0f),
            adjustments = if (json.has("adjustments")) ColorAdjustmentsCodec.decode(json.getJSONObject("adjustments")) else ColorAdjustments()
        ).also { it.validateParameters() }
    }

    fun checkedJson(text: String): JSONObject {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (character == '"' && !escaped) quoted = !quoted
            if (!quoted) {
                if (character == '{' || character == '[') depth++
                if (character == '}' || character == ']') depth--
                require(depth in 0..8) { "预设 JSON 嵌套过深或格式无效" }
            }
            escaped = !escaped && character == '\\'
        }
        return JSONObject(text)
    }
}
