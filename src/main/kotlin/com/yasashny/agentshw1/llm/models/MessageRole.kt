package com.yasashny.agentshw1.llm.models

import kotlinx.serialization.Serializable

@Serializable(with = MessageRoleSerializer::class)
enum class MessageRole(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    companion object {
        fun fromWire(value: String): MessageRole = entries.first { it.wire == value }
    }
}
