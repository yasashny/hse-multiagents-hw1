package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.core.CrashHook
import com.yasashny.agentshw1.core.CrashPoint
import com.yasashny.agentshw1.llm.models.ToolCallReq
import com.yasashny.agentshw1.policy.SafetyGate
import com.yasashny.agentshw1.state.models.Attempt
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.DebitRecord
import com.yasashny.agentshw1.state.models.PendingConfirmation
import com.yasashny.agentshw1.state.models.RunStatus
import com.yasashny.agentshw1.tools.ErrorKind
import com.yasashny.agentshw1.tools.Tool
import com.yasashny.agentshw1.tools.ToolContext
import com.yasashny.agentshw1.tools.ToolJson
import com.yasashny.agentshw1.tools.ToolName
import kotlinx.serialization.json.JsonObject

internal class BatchDispatcher(
    private val env: RuntimeEnv,
    private val ledger: CallLedger,
    private val outputBudget: OutputBudget,
    private val planFlow: PlanFlow,
) {

    fun drainBatch() {
        while (env.state.status == RunStatus.RUNNING &&
            env.state.pendingConfirmation == null &&
            env.state.batchQueue.isNotEmpty()
        ) {
            dispatchOne(env.state.batchQueue.first())
        }
    }

    private fun dispatchOne(call: ToolCallReq) {
        val ordinal = ledger.dispatchOrdinal()
        val toolName = ToolName.fromWire(call.name)

        if (handleDuplicate(call)) return

        if (env.state.budget.toolCallsLeft <= 0 ||
            (toolName in env.sideEffectIds && env.state.budget.sideEffectsLeft <= 0)
        ) {
            env.budgetExceeded(
                "cannot start '${call.name}': budget exhausted " +
                    "(toolCalls left ${env.state.budget.toolCallsLeft}, sideEffects left ${env.state.budget.sideEffectsLeft}); " +
                    "the action was not started and nothing was debited",
            )
            return
        }

        env.ui.toolStart(call.id, call.name, call.args.toString().take(TOOL_LOG_LENGTH))

        when (resolveVerdict(call)) {
            is SafetyGate.Verdict.Reject -> return
            is SafetyGate.Verdict.Confirm -> return
            SafetyGate.Verdict.Execute -> {}
        }

        when (toolName) {
            ToolName.SUBMIT_PLAN -> {
                planFlow.submitPlan(call)
                return
            }
            ToolName.COMPLETE_TASK -> {
                planFlow.completeTask(call)
                return
            }
            else -> {}
        }

        val tool = toolName?.let { env.tools[it] } ?: run {
            ledger.completeWithoutExecution(call, AttemptOutcome.DENIED_POLICY, ToolJson.error("unknown tool ${call.name}"))
            return
        }
        if (tool.sideEffect) {
            dispatchSideEffect(call, tool, ordinal)
        } else {
            dispatchReadOnly(call, tool, ordinal)
        }
    }

    private fun resolveVerdict(call: ToolCallReq): SafetyGate.Verdict {
        var verdict = env.gate.check(env.state.phase, env.state.mode, call.name, call.args)
        if (verdict is SafetyGate.Verdict.Confirm && call.id == env.state.approvedCallId) {
            verdict = SafetyGate.Verdict.Execute
            env.state.approvedCallId = null
        }
        when (verdict) {
            is SafetyGate.Verdict.Reject ->
                ledger.completeWithoutExecution(call, AttemptOutcome.DENIED_POLICY, ToolJson.error(verdict.reason, ErrorKind.DENIED_BY_POLICY))
            is SafetyGate.Verdict.Confirm -> {
                env.state.pendingConfirmation = PendingConfirmation(call.id, call.name, call.args, verdict.reason)
                env.state.status = RunStatus.AWAITING_CONFIRMATION
                env.save()
            }
            SafetyGate.Verdict.Execute -> {}
        }
        return verdict
    }

    private fun handleDuplicate(call: ToolCallReq): Boolean {
        val existing = ledger.recordFor(call.id) ?: return false
        if (existing.lastAttempt?.outcome == AttemptOutcome.RESOLVED_RETRY) return false

        if (existing.name != call.name || existing.args != call.args) {
            ledger.completeWithoutExecution(
                call,
                AttemptOutcome.PROTOCOL_ERROR,
                ToolJson.error(
                    "protocol error: tool_call_id '${call.id}' was already used with different name/arguments; " +
                        "each call must use a fresh id",
                    ErrorKind.PROTOCOL_ERROR,
                ),
            )
            return true
        }
        ledger.replayStored(call, existing)
        return true
    }

    private fun beginAttempt(call: ToolCallReq, ordinal: Int, sideEffect: Boolean) {
        env.state.budget = env.state.budget.copy(
            toolCallsSpent = env.state.budget.toolCallsSpent + 1,
            sideEffectsSpent = env.state.budget.sideEffectsSpent + if (sideEffect) 1 else 0,
        )
        ledger.upsertRecord(
            call,
            Attempt(
                index = ledger.nextAttemptIndex(call),
                outcome = AttemptOutcome.RUNNING,
                sideEffect = sideEffect,
                debit = DebitRecord(toolCalls = 1, sideEffects = if (sideEffect) 1 else 0),
            ),
        )
        env.save()
        crashPoint(ordinal, CrashPoint.AFTER_DEBIT_BEFORE_EXEC, call)
    }

    private fun dispatchReadOnly(call: ToolCallReq, tool: Tool, ordinal: Int) {
        beginAttempt(call, ordinal, sideEffect = false)
        val result = executeTool(tool, call)
        crashPoint(ordinal, CrashPoint.AFTER_EXEC_BEFORE_SAVE, call)
        finishAttempt(call, result)
    }

    private fun dispatchSideEffect(call: ToolCallReq, tool: Tool, ordinal: Int) {
        val conflict = env.journal.preCheck(env.state)
        if (conflict != null) {
            env.state.journal = env.state.journal.copy(conflicts = env.state.journal.conflicts + conflict.describe())
            ledger.completeWithoutExecution(
                call,
                AttemptOutcome.CONFLICT,
                ToolJson.error(
                    "action stopped: workspace changed externally since the last snapshot; ${conflict.describe()}",
                    ErrorKind.CONFLICT,
                ),
            )
            return
        }

        beginAttempt(call, ordinal, sideEffect = true)
        val result = executeTool(tool, call)
        crashPoint(ordinal, CrashPoint.AFTER_EXEC_BEFORE_SAVE, call)
        val touched = env.journal.postAction(env.state)
        if (touched.isNotEmpty()) {
            env.ui.info("journal: recorded changes to ${touched.joinToString(", ")}")
        }
        finishAttempt(call, result)
    }

    private fun executeTool(tool: Tool, call: ToolCallReq): JsonObject = try {
        val context = ToolContext(
            workspaceRoot = env.workspaceRoot,
            pathChecker = env.pathChecker,
            bashExecutor = env.bashExecutor,
            outputCapBytes = env.state.budget.outputBytesLeft.coerceAtLeast(0),
        )
        tool.execute(call.args, context)
    } catch (e: CrashHook.HardCrash) {
        throw e
    } catch (e: Exception) {
        ToolJson.error("tool execution failed: ${e.message}")
    }

    private fun finishAttempt(call: ToolCallReq, result: JsonObject) {
        val capped = outputBudget.enforce(result)
        val outputBytes = outputBudget.debit(capped)
        val outcome = if (capped.isOk()) AttemptOutcome.OK else AttemptOutcome.ERROR
        ledger.updateLastAttempt(call) { it.copy(outcome = outcome, result = capped, outputBytes = outputBytes) }
        ledger.appendToolMessage(call, capped)
        ledger.dequeue(call.id)
        env.save()
        env.ui.toolResult(call.id, call.name, ok = outcome == AttemptOutcome.OK, summary = ledger.resultSummary(capped))
    }

    private fun crashPoint(ordinal: Int, point: CrashPoint, call: ToolCallReq) {
        val active = CrashHook.activePointFor(ordinal) ?: return
        if (active == point) {
            CrashHook.crash(point, "at dispatch #$ordinal of ${call.name}(${call.args.toString().take(TOOL_LOG_LENGTH)})")
        }
    }
}
