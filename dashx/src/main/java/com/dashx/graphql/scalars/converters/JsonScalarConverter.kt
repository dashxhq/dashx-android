package com.dashx.graphql.scalars.converters

import com.expediagroup.graphql.client.converter.ScalarConverter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class JsonScalarConverter : ScalarConverter<JsonObject> {
    override fun toScalar(rawValue: Any): JsonObject =
        Json.parseToJsonElement(rawValue.toString()).jsonObject

    override fun toJson(value: JsonObject): Any = value
}
