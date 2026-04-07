package com.dashx.android

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Custom serializer that handles `screen_data` as either a native JSON object or a stringified
 * JSON string. The FCM data payload delivers all values as strings, so nested objects like
 * `screen_data` may arrive as `"{\"k\":\"v\"}"` instead of `{"k":"v"}`.
 *
 * **Coercion rules:**
 * - String values are kept as-is.
 * - Non-string primitives (booleans, numbers) are coerced to their string representation
 *   (e.g. `true` becomes `"true"`, `42` becomes `"42"`).
 * - Nested objects or arrays are serialized to their JSON string representation
 *   (e.g. `{"status":"active"}` becomes `"{\"status\":\"active\"}"`).
 * - Malformed stringified JSON returns `null`.
 */
object FlexibleStringMapSerializer : KSerializer<Map<String, String>?> {
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    override val descriptor: SerialDescriptor = mapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Map<String, String>?) {
        if (value != null) {
            encoder.encodeSerializableValue(mapSerializer, value)
        } else {
            encoder.encodeNull()
        }
    }

    override fun deserialize(decoder: Decoder): Map<String, String>? {
        val jsonDecoder = decoder as? JsonDecoder ?: return null
        val element = jsonDecoder.decodeJsonElement()

        return when (element) {
            is JsonObject -> element.toStringMap()
            is JsonPrimitive -> {
                if (element.isString) {
                    try {
                        Json.parseToJsonElement(element.content).jsonObject.toStringMap()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun JsonObject.toStringMap(): Map<String, String> =
        mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> value.content
                else -> value.toString()
            }
        }
}
