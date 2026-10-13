package com.yasashny.agentshw1.state.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PendingConfirmation(
    val callId: String,
    val name: String,
    val args: JsonObject,
    val reason: String,
    val decision: ConfirmationDecision? = null,
)
