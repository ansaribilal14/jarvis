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
    fun firstJsonObject(text: String): JsonObject? = jsonCandidates(text).firstOrNull()

    /**
     * ALL balanced JSON objects embedded in free text, in order of appearance
     * (max 8). Small models routinely emit prose + JSON + echoed prompt
     * fragments; scanning every candidate instead of only the first lets the
     * planner find the one object that actually parses into an action.
     */
    fun jsonCandidates(text: String, limit: Int = 8): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var i = 0
        while (i < text.length && out.size < limit) {
            val start = text.indexOf('{', i)
            if (start < 0) break
            var depth = 0
            var inString = false
            var escaped = false
            var j = start
            var end = -1
            while (j < text.length) {
                val c = text[j]
                if (escaped) { escaped = false }
                else when {
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) { end = j; break }
                    }
                }
                j++
            }
            if (end < 0) break // unbalanced tail - handled by repair pass at call sites
            val chunk = text.substring(start, end + 1)
            try {
                val el = json.parseToJsonElement(chunk)
                if (el is JsonObject) out.add(el)
            } catch (_: Exception) {
                // not valid JSON - keep scanning after this block
            }
            i = end + 1
        }
        return out
    }

    /**
     * Repair a TRUNCATED JSON object (small models that hit the token cap
     * mid-action): closes an open string, strips trailing commas, then closes
     * open brackets/braces in reverse order. Returns null if no '{' exists or
     * the repaired text still fails to parse.
     */
    fun repairedJsonObject(text: String): JsonObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val sb = StringBuilder(text.substring(start))
        // Walk with a stack, tracking open strings.
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var i = 0
        while (i < sb.length) {
            val c = sb[i]
            if (escaped) { escaped = false }
            else when {
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && (c == '{' || c == '[') -> stack.addLast(c)
                !inString && (c == '}' || c == ']') -> if (stack.isNotEmpty()) stack.removeLast()
            }
            i++
        }
        if (inString) sb.append('"')
        // Drop a dangling partial key/value like "args":{"na (no colon/completed value yet).
        var cleaned = sb.toString().replace(Regex(",\\s*\"[^\"]*\"\\s*$"), "").replace(Regex(",\\s*$"), "")
        // Close in reverse order.
        for (k in stack.indices.reversed()) {
            cleaned += if (stack[k] == '{') "}" else "]"
        }
        return try {
            json.parseToJsonElement(cleaned).jsonObject
        } catch (_: Exception) {
            null
        }
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
