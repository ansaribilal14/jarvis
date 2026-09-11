package com.jarvis.mobile.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** JSON helpers for robust parsing of model output (models wrap JSON in prose/fences). */
object JsonX {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Find the first balanced JSON object embedded in free text. Returns null if none. */
    fun firstJsonObject(text: String): JsonObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (escaped) { escaped = false; i++; continue }
            when {
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return try {
                            json.parseToJsonElement(text.substring(start, i + 1)).jsonObject
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    fun JsonElement?.str(key: String): String? = when (val v = this?.asObj()?.get(key)) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.content
        else -> v.toString()
    }

    fun JsonElement?.int(key: String): Int? = when (val v = this?.asObj()?.get(key)) {
        is JsonPrimitive -> v.intOrNull
        else -> null
    }

    fun JsonElement?.bool(key: String): Boolean? = when (val v = this?.asObj()?.get(key)) {
        is JsonPrimitive -> v.booleanOrNull
        else -> null
    }

    fun JsonElement?.dbl(key: String): Double? = when (val v = this?.asObj()?.get(key)) {
        is JsonPrimitive -> v.doubleOrNull
        else -> null
    }

    fun JsonElement?.obj(key: String): JsonObject? = when (val v = this?.asObj()?.get(key)) {
        is JsonObject -> v
        else -> null
    }

    fun JsonElement?.arr(key: String): JsonArray? = when (val v = this?.asObj()?.get(key)) {
        is JsonArray -> v
        else -> null
    }

    private fun JsonElement.asObj(): JsonObject = try {
        jsonObject
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}
