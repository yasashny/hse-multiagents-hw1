package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.ConfirmationDecision
import com.yasashny.agentshw1.state.models.PendingConfirmation
import com.yasashny.agentshw1.tools.ErrorKind
import com.yasashny.agentshw1.tools.ToolJson

internal class ConfirmationFlow(
    private val env: RuntimeEnv,
    private val ledger: CallLedger,
) {

    fun setDecision(runId: String, callId: String, approved: Boolean): Boolean {
        check(env.state.runId == runId)
        val confirmation = env.state.pendingConfirmation ?: return false
        if (confirmation.callId != callId) return false
        env.state.pendingConfirmation = confirmation.copy(
            decision = if (approved) ConfirmationDecision.APPROVED else ConfirmationDecision.DENIED,
        )
        env.save()
        return true
    }

    fun apply(confirmation: PendingConfirmation) {
        when (confirmation.decision) {
            ConfirmationDecision.APPROVED -> applyApproval(confirmation)
            ConfirmationDecision.DENIED -> applyDenial(confirmation)
            null -> {}
        }
    }

    private fun applyApproval(confirmation: PendingConfirmation) {
        env.state.pendingConfirmation = null
        env.state.approvedCallId = confirmation.callId
        env.save()
        env.ui.info("user approved ${confirmation.name}; re-checking policy and executing")
    }

    private fun applyDenial(confirmation: PendingConfirmation) {
        env.state.pendingConfirmation = null
        val call = env.state.batchQueue.firstOrNull { it.id == confirmation.callId } ?: run {
            env.save()
            return
        }
        ledger.recordTerminalAttempt(
            call,
            AttemptOutcome.USER_DENIED,
            ToolJson.error(
                "action denied by the user (${confirmation.reason}); choose a different step",
                ErrorKind.USER_DENIED,
            ),
        )
        env.ui.toolResult(call.id, call.name, ok = false, summary = "denied by user")
    }
}
