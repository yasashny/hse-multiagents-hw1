package com.yasashny.agentshw1.state.models

import kotlinx.serialization.Serializable

@Serializable
data class BudgetLimits(
    val modelRequests: Int = DEFAULT_MODEL_REQUESTS,
    val toolCalls: Int = DEFAULT_TOOL_CALLS,
    val sideEffects: Int = DEFAULT_SIDE_EFFECTS,
    val outputBytes: Long = DEFAULT_OUTPUT_BYTES,
) {
    private companion object {
        const val DEFAULT_MODEL_REQUESTS = 60
        const val DEFAULT_TOOL_CALLS = 60
        const val DEFAULT_SIDE_EFFECTS = 20
        const val DEFAULT_OUTPUT_BYTES = 512_000L
    }
}
