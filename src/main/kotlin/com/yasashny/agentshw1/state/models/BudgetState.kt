package com.yasashny.agentshw1.state.models

import kotlinx.serialization.Serializable

@Serializable
data class BudgetState(
    val limits: BudgetLimits = BudgetLimits(),
    val modelRequestsSpent: Int = 0,
    val toolCallsSpent: Int = 0,
    val sideEffectsSpent: Int = 0,
    val outputBytesSpent: Long = 0L,
) {
    val modelRequestsLeft: Int get() = limits.modelRequests - modelRequestsSpent

    val toolCallsLeft: Int get() = limits.toolCalls - toolCallsSpent

    val sideEffectsLeft: Int get() = limits.sideEffects - sideEffectsSpent

    val outputBytesLeft: Long get() = limits.outputBytes - outputBytesSpent

    fun summary(): String =
        "budget: modelRequests $modelRequestsSpent/${limits.modelRequests}, " +
            "toolCalls $toolCallsSpent/${limits.toolCalls}, " +
            "sideEffects $sideEffectsSpent/${limits.sideEffects}, " +
            "outputBytes $outputBytesSpent/${limits.outputBytes}"
}
