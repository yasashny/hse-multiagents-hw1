package com.yasashny.agentshw1

import com.yasashny.agentshw1.llm.LLMClient
import com.yasashny.agentshw1.llm.LLMException
import com.yasashny.agentshw1.llm.models.ModelReply
import com.yasashny.agentshw1.llm.models.StoredMessage
import com.yasashny.agentshw1.llm.models.ToolDescriptor

class FakeLlm(vararg replies: ModelReply) : LLMClient {

    private val script = ArrayDeque(replies.toList())
    val requests = mutableListOf<Pair<List<StoredMessage>, List<ToolDescriptor>>>()

    override fun chat(messages: List<StoredMessage>, tools: List<ToolDescriptor>): ModelReply {
        requests.add(messages to tools)
        return script.removeFirstOrNull() ?: throw LLMException("FakeLlm script exhausted")
    }
}
