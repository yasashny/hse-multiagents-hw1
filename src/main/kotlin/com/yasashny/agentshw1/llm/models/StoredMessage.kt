package com.yasashny.agentshw1.llm.models

import kotlinx.serialization.Serializable

@Serializable
data class StoredMessage(
    val role: MessageRole,
    val content: String? = null,
    val toolCalls: List<ToolCallReq> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
)
