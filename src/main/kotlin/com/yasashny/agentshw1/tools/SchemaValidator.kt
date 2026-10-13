package com.yasashny.agentshw1.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

object SchemaValidator {

    fun validate(schema: JsonObject, args: JsonObject): List<String> {
        val errors = requiredFields(schema)
            .filter { args[it] == null }
            .map { "missing required argument: $it" }
            .toMutableList()
        val properties = schema["properties"] as? JsonObject
            ?: return errors
        for ((key, value) in args) {
            val propSchema = properties[key] as? JsonObject
            if (propSchema == null) {
                if (properties.isNotEmpty()) errors.add("unknown argument: $key (allowed: ${properties.keys})")
                continue
            }
            val expected = (propSchema["type"] as? JsonPrimitive)?.content ?: continue
            if (!typeMatches(expected, value)) {
                errors.add("argument '$key' must be of type $expected, got: ${value::class.simpleName}")
            }
        }
        return errors
    }

    private fun requiredFields(schema: JsonObject): List<String> =
        (schema["required"] as? JsonArray)
            ?.map { it.jsonPrimitive.content }
            .orEmpty()

    private fun typeMatches(expected: String, value: JsonElement): Boolean = when (expected) {
        "string" -> value is JsonPrimitive && value.isString
        "number" -> value is JsonPrimitive && !value.isString
        "integer" -> value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
        "boolean" -> value is JsonPrimitive && !value.isString && (value.content == "true" || value.content == "false")
        "array" -> value is JsonArray
        "object" -> value is JsonObject
        else -> true
    }
}
