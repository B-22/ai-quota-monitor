package com.tokenmon.mobile.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Defensive accessors over a raw [JsonObject].
 *
 * Token Monitor's Hub normalizes its own payload before it goes on the wire, but
 * it is still a project that adds providers, window kinds and fields over time.
 * Everything here therefore answers "is this usable?" instead of "is this the
 * expected type?", and every miss degrades to null rather than throwing.
 */
object Json {

    /** Mirrors the Hub's own `asNumber`: accepts `"42"`, `"42%"`, `"$1,234.5"`. */
    fun number(value: JsonElement?): Double? {
        if (value == null || value is JsonNull) return null
        return when (value) {
            is JsonPrimitive -> {
                if (value.isString) {
                    val cleaned = value.content.replace("[%,$ ]".toRegex(), "")
                    if (cleaned.isEmpty()) null else cleaned.toDoubleOrNull()
                } else {
                    value.doubleOrNull ?: value.content.toDoubleOrNull()
                }
            }
            else -> null
        }
    }

    fun obj(value: JsonElement?): JsonObject? = value as? JsonObject

    fun array(value: JsonElement?): JsonArray? = value as? JsonArray

    fun JsonObject.objOrNull(key: String): JsonObject? = obj(this[key])

    fun JsonObject.arrayOrNull(key: String): JsonArray? = array(this[key])

    fun JsonObject.stringOrNull(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val content = primitive.content
        return content.ifEmpty { null }
    }

    fun JsonObject.stringOrEmpty(key: String): String = stringOrNull(key).orEmpty()

    fun JsonObject.doubleOrNull(key: String): Double? = number(this[key])

    fun JsonObject.longOrNull(key: String): Long? = doubleOrNull(key)?.toLong()

    fun JsonObject.intOrNull(key: String): Int? = doubleOrNull(key)?.toInt()

    fun JsonObject.boolOrNull(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.booleanOrNull
    }

    /**
     * Reads a list of objects, isolating each entry.
     *
     * A single malformed element must never take the surrounding list down with
     * it: a provider row the parser cannot read is rendered as "unreadable" while
     * its siblings still display.
     */
    fun JsonObject.objectListOrEmpty(key: String): List<JsonObject> =
        arrayOrNull(key).orEmpty().mapNotNull { obj(it) }

    fun JsonArray.objects(): List<JsonObject> = mapNotNull { obj(it) }

    /** Clamps to the range the Hub itself uses for a percentage. */
    fun percent(value: Double?): Double? = value?.coerceIn(0.0, 100.0)
}
