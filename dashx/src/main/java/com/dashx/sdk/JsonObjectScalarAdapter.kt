package com.dashx.android

import com.apollographql.apollo.api.AnyAdapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.dashx.graphql.generated.type.JSON
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

/**
 * Apollo scalar adapter for the GraphQL `JSON` type mapped to
 * [kotlinx.serialization.json.JsonObject]. Required at runtime so that
 * Apollo can deserialize/serialize JSON fields in responses and variables.
 */
object JsonObjectScalarAdapter : Adapter<JsonObject> {

    private val json = Json { ignoreUnknownKeys = true }

    override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters): JsonObject {
        val any = AnyAdapter.fromJson(reader, customScalarAdapters)
        @Suppress("UNCHECKED_CAST")
        val map = (any as? Map<String, Any?>) ?: emptyMap()
        val jsonString = JSONObject(map).toString()
        return json.parseToJsonElement(jsonString).jsonObject
    }

    override fun toJson(
        writer: JsonWriter,
        customScalarAdapters: CustomScalarAdapters,
        value: JsonObject
    ) {
        val map = jsonObjectToMap(value)
        AnyAdapter.toJson(writer, customScalarAdapters, map)
    }

    private fun jsonObjectToMap(obj: JsonObject): Map<String, Any?> =
        obj.mapValues { jsonElementToAny(it.value) }

    private fun jsonElementToAny(element: JsonElement): Any? = when (element) {
        is JsonObject -> jsonObjectToMap(element)
        is JsonArray -> element.map { jsonElementToAny(it) }
        is JsonPrimitive -> element.content
        is JsonNull -> null
    }
}
