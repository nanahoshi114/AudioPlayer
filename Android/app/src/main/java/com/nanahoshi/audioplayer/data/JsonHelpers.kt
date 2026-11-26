package com.nanahoshi.audioplayer.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** 面向外部 JSON 的宽松解析配置：未知字段和类型漂移都不得让整次读取失败。 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

internal fun parseJsonOrNull(raw: String?): JsonElement? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { AppJson.parseToJsonElement(it) }.getOrNull() }

internal fun parseJsonObjectOrNull(raw: String?): JsonObject? = parseJsonOrNull(raw)?.asObjectOrNull()

internal fun parseJsonArrayOrNull(raw: String?): JsonArray? = parseJsonOrNull(raw)?.asArrayOrNull()

internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray

/** 只接受 JSON 字符串；数字、布尔和 null 都不算文本。 */
internal fun JsonElement?.jsonTextOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.jsonValue(key: String): JsonElement? = this[key]

internal fun JsonObject.jsonPrimitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

internal fun JsonObject.jsonString(key: String): String? = jsonPrimitive(key)?.contentOrNull

internal fun JsonObject.jsonStringOrEmpty(key: String): String = jsonString(key).orEmpty()

internal fun JsonObject.jsonLong(key: String): Long? = jsonString(key)?.toLongOrNull()

internal fun JsonObject.jsonInt(key: String): Int? = jsonString(key)?.toIntOrNull()

internal fun JsonObject.jsonDouble(key: String): Double? = jsonString(key)?.toDoubleOrNull()

internal fun JsonObject.jsonBool(key: String): Boolean? = jsonPrimitive(key)?.booleanOrNull

internal fun JsonObject.jsonArray(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.jsonObject(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonArray?.jsonObjects(): List<JsonObject> = this.orEmpty().mapNotNull { it as? JsonObject }

internal fun JsonArray?.jsonStrings(): List<String> = this.orEmpty().mapNotNull { it.jsonTextOrNull()?.takeIf(String::isNotBlank) }

internal fun JsonObject.jsonObjects(key: String): List<JsonObject> = jsonArray(key).jsonObjects()

internal fun JsonObject.jsonStrings(key: String): List<String> = jsonArray(key).jsonStrings()

internal fun JsonObject.jsonNames(key: String): List<String> =
    jsonObjects(key).mapNotNull { it.jsonString("name")?.takeIf(String::isNotBlank) }