package com.yasashny.agentshw1.state.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Attempt(
    val index: Int,
    val outcome: AttemptOutcome,
    val result: JsonObject? = null,
    val sideEffect: Boolean = false,
    val debit: DebitRecord = DebitRecord(),
    val outputBytes: Long = 0L,
    val note: String? = null,
)
