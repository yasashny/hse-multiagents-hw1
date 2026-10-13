package com.yasashny.agentshw1.loop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class OutputBudget(private val env: RuntimeEnv) {

    fun enforce(result: JsonObject): JsonObject {
        val left = env.state.budget.outputBytesLeft.coerceAtLeast(0)
        val serialized = result.toString()
        val size = serialized.toByteArray(Charsets.UTF_8).size
        if (size <= left) return result

        val partialBytes = (left - OUTPUT_WRAPPER_OVERHEAD_BYTES)
            .coerceIn(0, Int.MAX_VALUE.toLong())
            .toInt()
        val partial = String(serialized.toByteArray(Charsets.UTF_8).copyOf(partialBytes), Charsets.UTF_8)
        return buildJsonObject {
            put("ok", result["ok"] ?: JsonPrimitive(false))
            put("truncated", true)
            put("note", "result of $size bytes exceeded the remaining output budget and was truncated; it is incomplete")
            put("partial", partial)
        }
    }

    fun debit(result: JsonObject): Long {
        val size = result.toString().toByteArray(Charsets.UTF_8).size.toLong()
        val debit = size.coerceAtMost(env.state.budget.outputBytesLeft.coerceAtLeast(0))
        env.state.budget = env.state.budget.copy(outputBytesSpent = env.state.budget.outputBytesSpent + debit)
        return debit
    }

    companion object {
        private const val OUTPUT_WRAPPER_OVERHEAD_BYTES = 200L
    }
}
