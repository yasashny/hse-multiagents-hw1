package com.yasashny.agentshw1.llm.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolCallReq(
    val id: String,
    val name: String,
    val args: JsonObject,
)
