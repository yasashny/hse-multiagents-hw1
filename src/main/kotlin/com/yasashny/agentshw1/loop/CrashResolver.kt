package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.llm.models.ToolCallReq
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.CallRecord
import com.yasashny.agentshw1.state.models.RunStatus
import com.yasashny.agentshw1.tools.ErrorKind
import com.yasashny.agentshw1.tools.ToolJson

internal class CrashResolver(
    private val env: RuntimeEnv,
    private val ledger: CallLedger,
) {

    fun reportIndeterminate(calls: List<CallRecord>) {
        env.ui.warn(
            "run ${env.state.runId} has ${calls.size} indeterminate tool call(s) " +
                "(crashed between execution and result saving):",
        )
        calls.forEach { call ->
            env.ui.warn("  - ${call.callId}: ${call.name}(${call.args.toString().take(TOOL_LOG_LENGTH)}) [attempt ${call.lastAttempt?.index}]")
        }
        val mayHaveSideEffects = calls.any { it.lastAttempt?.sideEffect == true }
        if (mayHaveSideEffects) {
            env.journal.preCheck(env.state)?.let { diff ->
                env.ui.warn("workspace tree differs from the last snapshot — the crashed call(s) may have made these changes:")
                env.ui.warn("  ${diff.describe()}")
                env.ui.warn(
                    "decide with --attribute yes (changes belong to this run, they will roll back with it) " +
                        "or --attribute no (treat as external).",
                )
            }
        }
        env.ui.info("resolve each call and continue with: agent resume ${env.state.runId} --resolve <callId>=retry|fail|abort [--attribute yes|no]")
    }

    fun resolveIndeterminate(resolutions: Map<String, ResolutionAction>, attribute: Boolean?): List<CallRecord> {
        val indeterminate = env.state.calls.filter { it.isIndeterminate }
        val ids = indeterminate.map { it.callId }.toSet()
        require(resolutions.keys == ids) {
            "resolutions required exactly for ${ids.joinToString(", ")} " +
                "(got ${resolutions.keys.joinToString(", ").ifEmpty { "none" }})"
        }

        val externalDiff = if (indeterminate.any { it.lastAttempt?.sideEffect == true }) env.journal.preCheck(env.state) else null
        if (externalDiff != null && attribute == null) {
            throw IllegalArgumentException(
                "the workspace changed since the last snapshot (${externalDiff.describe()}); " +
                    "pass --attribute yes (changes belong to this run) or --attribute no (external changes) to continue",
            )
        }

        for (record in indeterminate) {
            applyResolution(record, resolutions.getValue(record.callId), attribute, externalDiff != null)
        }
        return indeterminate
    }

    private fun applyResolution(record: CallRecord, action: ResolutionAction, attribute: Boolean?, attributeNeeded: Boolean) {
        val call = record.toToolCall()
        if (record.lastAttempt?.sideEffect == true && attributeNeeded) {
            val attributed = requireNotNull(attribute)
            val diff = env.journal.absorbCurrentTree(env.state, attributed)
            env.ui.info(
                "journal: crashed-call changes " +
                    "${if (attributed) "attributed to this run" else "treated as external"}: " +
                    diff.allPaths().joinToString(", "),
            )
        }
        when (action) {
            ResolutionAction.ABORT -> {
                ledger.updateLastAttempt(call) {
                    it.copy(outcome = AttemptOutcome.FAILED_BY_USER, note = "run aborted by user after crash")
                }
                env.failRun(RunStatus.ABORTED_BY_USER, "aborted by user while resolving indeterminate call ${record.callId}")
            }
            ResolutionAction.FAIL -> {
                val result = ToolJson.error(
                    "step marked failed by the user after a crash (result was indeterminate)",
                    ErrorKind.FAILED_AFTER_CRASH,
                )
                ledger.updateLastAttempt(call) {
                    it.copy(outcome = AttemptOutcome.FAILED_BY_USER, result = result, note = "resolved: fail")
                }
                ledger.appendToolMessage(call, result)
                ledger.dequeue(record.callId)
                env.save()
                env.ui.info("call ${record.callId} marked failed; the model will see the failure and continue")
            }
            ResolutionAction.RETRY -> {
                ledger.updateLastAttempt(call) {
                    it.copy(outcome = AttemptOutcome.RESOLVED_RETRY, note = "resolved: explicit retry")
                }
                env.save()
                env.ui.info("call ${record.callId} scheduled for an explicit retry (new attempt of the same tool call)")
            }
        }
    }

    private fun CallRecord.toToolCall(): ToolCallReq = ToolCallReq(callId, name, args)
}
