package com.dashx.android

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Custom serializer that handles boolean values arriving as either a native JSON boolean
 * or a string (`"true"` / `"false"`).
 *
 * FCM data payloads deliver all values as strings, so fields like `rich_landing` may arrive
 * as `"true"` instead of `true`.
 */
object FlexibleBoolSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBool", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value != null) {
            encoder.encodeBoolean(value)
        } else {
            encoder.encodeNull()
        }
    }

    override fun deserialize(decoder: Decoder): Boolean? {
        val jsonDecoder = decoder as? JsonDecoder ?: return null
        val element = jsonDecoder.decodeJsonElement()

        if (element is JsonPrimitive) {
            // Native boolean
            element.content.toBooleanStrictOrNull()?.let { return it }
            // String "true" / "false"
            if (element.isString) {
                return element.content.toBooleanStrictOrNull()
            }
        }
        return null
    }
}
