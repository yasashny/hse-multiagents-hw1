package com.yasashny.agentshw1.state.models

import kotlinx.serialization.Serializable

@Serializable
data class DebitRecord(
    val toolCalls: Int = 0,
    val sideEffects: Int = 0,
)
