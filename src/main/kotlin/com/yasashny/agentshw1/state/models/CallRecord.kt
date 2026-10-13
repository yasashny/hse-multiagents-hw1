package com.yasashny.agentshw1.state.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class CallRecord(
    val callId: String,
    val name: String,
    val args: JsonObject,
    val phase: RunPhase,
    val taskId: String? = null,
    val attempts: List<Attempt> = emptyList(),
) {

    val lastAttempt: Attempt? get() = attempts.lastOrNull()

    val status: CallStatus
        get() {
            val last = lastAttempt ?: return CallStatus.RECORDED
            return when (last.outcome) {
                AttemptOutcome.RUNNING -> CallStatus.INDETERMINATE
                AttemptOutcome.OK -> CallStatus.COMPLETED
                else -> CallStatus.FAILED
            }
        }

    val isIndeterminate: Boolean get() = status == CallStatus.INDETERMINATE

    val lastRealResult: JsonObject?
        get() = attempts.lastOrNull { it.outcome != AttemptOutcome.PROTOCOL_ERROR }?.result
}
