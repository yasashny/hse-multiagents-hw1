package com.yasashny.agentshw1.llm

import com.yasashny.agentshw1.llm.models.ModelReply
import com.yasashny.agentshw1.llm.models.StoredMessage
import com.yasashny.agentshw1.llm.models.ToolDescriptor

interface LLMClient {

    fun chat(messages: List<StoredMessage>, tools: List<ToolDescriptor>): ModelReply
}
