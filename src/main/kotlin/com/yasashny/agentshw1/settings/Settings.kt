package com.yasashny.agentshw1.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

data class Settings(
    val llm: LlmSettings = LlmSettings(),
) {

    companion object {

        fun load(path: File): Settings {
            if (!path.exists()) return Settings()
            val root = try {
                Json.parseToJsonElement(path.readText()).jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException(invalidJsonMessage(path, e))
            }
            val llm = when (val element = root["llm"]) {
                null, is JsonNull -> null
                is JsonObject -> element
                else -> throw IllegalArgumentException(
                    "settings file ${path.name}: 'llm' must be a JSON object (see settings.example.json)",
                )
            }
            return Settings(
                llm = LlmSettings(
                    baseUrl = llm.str(path, "baseUrl"),
                    apiKey = llm.str(path, "apiKey"),
                    model = llm.str(path, "model"),
                ),
            )
        }

        fun defaultPath(): File = File("settings.json").absoluteFile

        private fun invalidJsonMessage(path: File, e: Exception): String {
            val detail = e.message
                ?.substringBefore("JSON input:")
                ?.trim()
                ?.take(200)
                .orEmpty()
            return "settings file ${path.name} is not valid JSON" +
                (if (detail.isEmpty()) "" else ": $detail") +
                " — fix or remove it (see settings.example.json)"
        }
    }
}

private fun JsonObject?.str(path: File, key: String): String? {
    val value = this?.get(key) ?: return null
    if (value !is JsonPrimitive || value is JsonNull || !value.isString) {
        throw IllegalArgumentException(
            "settings file ${path.name}: llm.$key must be a string (see settings.example.json)",
        )
    }
    return value.content.takeIf { it.isNotBlank() }
}
