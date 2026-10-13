package com.yasashny.agentshw1.llm.models

import kotlinx.serialization.json.JsonObject

data class ToolDescriptor(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)
