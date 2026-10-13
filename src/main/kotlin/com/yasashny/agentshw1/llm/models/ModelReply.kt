package com.yasashny.agentshw1.llm.models

sealed interface ModelReply {

    data class ToolCalls(val calls: List<ToolCallReq>) : ModelReply

    data class Text(val text: String) : ModelReply
}
