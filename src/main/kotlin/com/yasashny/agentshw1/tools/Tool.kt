package com.yasashny.agentshw1.tools

import kotlinx.serialization.json.JsonObject

interface Tool {

    val id: ToolName

    val name: String get() = id.wire

    val description: String

    val parameters: JsonObject

    val sideEffect: Boolean

    fun execute(args: JsonObject, ctx: ToolContext): JsonObject
}
