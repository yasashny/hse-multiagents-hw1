package com.yasashny.agentshw1.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun JsonObject.argString(key: String): String =
    getValue(key).jsonPrimitive.content

internal fun stringParameter(): JsonObject = buildJsonObject {
    put("type", "string")
}

internal fun stringParameter(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

object ToolJson {

    fun error(message: String, kind: ErrorKind = ErrorKind.ERROR): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", message)
        put("kind", kind.wire)
    }

    fun truncatedNote(shown: Int, total: Int): String =
        "output truncated to $shown of $total bytes (budget/truncation applies); result is incomplete"
}
