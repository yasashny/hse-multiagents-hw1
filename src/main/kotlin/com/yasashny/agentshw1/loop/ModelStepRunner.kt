package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.llm.LLMException
import com.yasashny.agentshw1.llm.models.MessageRole
import com.yasashny.agentshw1.llm.models.ModelReply
import com.yasashny.agentshw1.llm.models.StoredMessage
import com.yasashny.agentshw1.llm.models.ToolCallReq
import com.yasashny.agentshw1.llm.models.ToolDescriptor
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.RunPhase
import com.yasashny.agentshw1.state.models.RunStatus
import com.yasashny.agentshw1.state.models.TaskStatus
import com.yasashny.agentshw1.tools.ErrorKind
import com.yasashny.agentshw1.tools.Tool
import com.yasashny.agentshw1.tools.ToolJson
import com.yasashny.agentshw1.tools.ToolName

internal class ModelStepRunner(
    private val env: RuntimeEnv,
    private val ledger: CallLedger,
) {

    fun modelStep() {
        if (env.state.budget.modelRequestsLeft <= 0) {
            env.budgetExceeded("model request budget exhausted: limit ${env.state.budget.limits.modelRequests}")
            return
        }
        env.state.budget = env.state.budget.copy(modelRequestsSpent = env.state.budget.modelRequestsSpent + 1)
        env.save()
        env.ui.modelRequest(env.state.budget.modelRequestsSpent)

        val reply = try {
            env.llm.chat(
                env.state.messages,
                advertisedTools().map { ToolDescriptor(it.name, it.description, it.parameters) },
            )
        } catch (e: LLMException) {
            env.failRun(RunStatus.FAILED, "model request failed: ${e.message}")
            return
        }

        when (reply) {
            is ModelReply.Text -> handleFinalText(reply.text)
            is ModelReply.ToolCalls -> handleToolCallsReply(reply)
        }
    }

    fun repairDanglingToolCalls() {
        val last = env.state.messages.lastOrNull() ?: return
        if (last.role != MessageRole.ASSISTANT || last.toolCalls.isEmpty()) return
        for (call in last.toolCalls) {
            val result = ToolJson.error(
                "call interrupted by a crash before execution; no result was recorded",
                ErrorKind.INTERRUPTED,
            )
            env.state.messages += StoredMessage(
                role = MessageRole.TOOL,
                content = result.toString(),
                toolCallId = call.id,
                name = call.name,
            )
        }
        env.save()
        env.ui.warn("repaired ${last.toolCalls.size} tool call(s) interrupted by a crash before execution")
    }

    private fun advertisedTools(): List<Tool> =
        env.gate.toolsForPhase(env.state.phase).filterNot { it.id == ToolName.COMPLETE_TASK && env.state.tasks.isEmpty() }

    private fun needsExecution(call: ToolCallReq): Boolean {
        val existing = ledger.recordFor(call.id) ?: return true
        return existing.name != call.name ||
            existing.args != call.args ||
            existing.lastAttempt?.outcome == AttemptOutcome.RESOLVED_RETRY
    }

    private fun handleToolCallsReply(reply: ModelReply.ToolCalls) {
        val fresh = reply.calls.filter { needsExecution(it) }
        val neededCalls = fresh.size
        val neededSideEffects = fresh.count { ToolName.fromWire(it.name) in env.sideEffectIds }
        if (env.state.budget.toolCallsLeft < neededCalls || env.state.budget.sideEffectsLeft < neededSideEffects) {
            env.state.messages += StoredMessage(role = MessageRole.ASSISTANT, toolCalls = reply.calls)
            env.state.batchQueue = emptyList()
            env.budgetExceeded(
                "tool call budget exhausted: batch needs $neededCalls tool calls " +
                    "(left ${env.state.budget.toolCallsLeft}) and $neededSideEffects side effects " +
                    "(left ${env.state.budget.sideEffectsLeft}); NONE of the ${reply.calls.size} batch calls was executed",
            )
            return
        }
        env.state.messages += StoredMessage(role = MessageRole.ASSISTANT, toolCalls = reply.calls)
        env.state.batchQueue = reply.calls

        env.state.nudges = 0
        env.save()
    }

    private fun handleFinalText(text: String) {
        env.state.messages += StoredMessage(role = MessageRole.ASSISTANT, content = text)
        if (env.state.phase == RunPhase.PLANNING) {
            handlePlanningFinalText()
            return
        }
        if (text.isBlank()) {
            handleEmptyReply()
            return
        }
        handleExecutionFinalText(text)
    }

    private fun handleEmptyReply() {
        if (env.state.nudges >= MAX_TASK_NUDGES) {
            env.failRun(RunStatus.FAILED, "model kept returning empty replies (no text and no tool calls)")
            return
        }
        env.state.nudges += 1
        env.state.messages += StoredMessage(
            role = MessageRole.USER,
            content = "Your last reply was empty: it contained neither text nor tool calls. " +
                "Continue the task: call one of the available tools or return the final text answer.",
        )
        env.save()
    }

    private fun handlePlanningFinalText() {
        if (env.state.nudges >= MAX_PLANNING_NUDGES) {
            env.failRun(RunStatus.FAILED, "planning phase ended without a submitted plan")
            return
        }
        env.state.nudges += 1
        env.state.messages += StoredMessage(
            role = MessageRole.USER,
            content = "Planning is not finished: explore the project with list_files/read_file " +
                "and call submit_plan with a structured plan.",
        )
        env.save()
    }

    private fun handleExecutionFinalText(text: String) {
        val pendingTasks = env.state.tasks.filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING }
        if (pendingTasks.isEmpty()) {
            env.state.finalAnswer = text
            env.state.status = RunStatus.FINISHED
            env.save()
            env.ui.finish("run ${env.state.runId} finished: $text")
            env.ui.info(env.state.budget.summary())
            return
        }
        if (env.state.nudges >= MAX_TASK_NUDGES) {
            env.failRun(RunStatus.FAILED, "model kept returning final answers with pending tasks")
            return
        }
        env.state.nudges += 1
        env.state.messages += StoredMessage(
            role = MessageRole.USER,
            content = "The task list is not complete yet:\n${renderChecklist(env.state.tasks)}\n" +
                "Continue with the current task '${env.state.currentTaskId}' and call " +
                "complete_task(task_id, evidence_call_id) when its result is verified. Do not give the final answer yet.",
        )
        env.save()
    }

    companion object {
        private const val MAX_PLANNING_NUDGES = 1
        private const val MAX_TASK_NUDGES = 3
    }
}
