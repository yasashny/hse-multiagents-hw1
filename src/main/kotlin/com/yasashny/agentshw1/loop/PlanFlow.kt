package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.llm.models.MessageRole
import com.yasashny.agentshw1.llm.models.StoredMessage
import com.yasashny.agentshw1.llm.models.ToolCallReq
import com.yasashny.agentshw1.state.models.Attempt
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.DebitRecord
import com.yasashny.agentshw1.state.models.RunPhase
import com.yasashny.agentshw1.state.models.RunStatus
import com.yasashny.agentshw1.state.models.TaskState
import com.yasashny.agentshw1.state.models.TaskStatus
import com.yasashny.agentshw1.tools.ErrorKind
import com.yasashny.agentshw1.tools.ToolJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class PlanFlow(
    private val env: RuntimeEnv,
    private val ledger: CallLedger,
    private val outputBudget: OutputBudget,
) {

    fun submitPlan(call: ToolCallReq) {
        val plan = PlanValidator.parse(call.args)
        if (plan == null) {
            ledger.completeWithoutExecution(
                call,
                AttemptOutcome.ERROR,
                ToolJson.error("submit_plan arguments do not form a valid plan", ErrorKind.INVALID_PLAN),
            )
            return
        }
        val validationError = PlanValidator.validate(plan)
        if (validationError != null) {
            ledger.completeWithoutExecution(call, AttemptOutcome.ERROR, ToolJson.error("invalid plan: $validationError", ErrorKind.INVALID_PLAN))
            return
        }

        val result = buildJsonObject {
            put("ok", true)
            put("plan_submitted", true)
            put("steps", plan.steps.size)
        }
        env.state.budget = env.state.budget.copy(toolCallsSpent = env.state.budget.toolCallsSpent + 1)
        val capped = outputBudget.enforce(result)
        val outputBytes = outputBudget.debit(capped)
        ledger.upsertRecord(
            call,
            Attempt(
                index = ledger.nextAttemptIndex(call),
                outcome = AttemptOutcome.OK,
                result = capped,
                debit = DebitRecord(toolCalls = 1),
                outputBytes = outputBytes,
            ),
        )
        ledger.appendToolMessage(call, capped)
        cancelRemainingBatch(call.id)
        env.state.plan = plan
        env.state.status = RunStatus.AWAITING_PLAN_APPROVAL
        env.save()
        env.ui.planReady(plan)
    }

    private fun cancelRemainingBatch(submittedCallId: String) {
        env.state.batchQueue.filter { it.id != submittedCallId }.forEach { call ->
            val skipped = outputBudget.enforce(
                ToolJson.error("skipped: plan submitted, planning phase ended", ErrorKind.SKIPPED),
            )
            outputBudget.debit(skipped)
            env.state.messages += StoredMessage(
                role = MessageRole.TOOL,
                content = skipped.toString(),
                toolCallId = call.id,
                name = call.name,
            )
        }
        env.state.batchQueue = emptyList()
    }

    fun approve() {
        check(env.state.status == RunStatus.AWAITING_PLAN_APPROVAL) {
            "run ${env.state.runId} is not awaiting plan approval (status: ${env.state.status})"
        }
        val plan = env.state.plan ?: error("no plan to approve")
        env.state.tasks = plan.steps.map { TaskState(id = it.id, description = it.description, tools = it.tools, dependsOn = it.dependsOn) }
        env.state.phase = RunPhase.EXECUTION
        env.state.status = RunStatus.RUNNING
        env.state.messages += StoredMessage(
            role = MessageRole.USER,
            content = Prompts.planApprovedMessage + "\n\n" + renderChecklist(env.state.tasks),
        )
        selectNextTask()
        env.save()
        env.ui.checklist(env.state.tasks)
        env.ui.info("plan approved; execution starts (approval does not pre-approve dangerous actions in ${env.state.mode} mode)")
    }

    fun reject() {
        check(env.state.status == RunStatus.AWAITING_PLAN_APPROVAL) { "run is not awaiting plan approval" }
        env.state.status = RunStatus.PLAN_REJECTED
        env.state.failure = "plan rejected by user"
        env.save()
        env.ui.info("plan rejected; run ${env.state.runId} terminated")
    }

    fun selectNextTask() {
        val next = env.state.tasks.firstOrNull { it.status == TaskStatus.PENDING && dependenciesDone(it) }
        if (next == null) {
            finishTaskSelection()
            return
        }
        env.state.tasks = env.state.tasks.map { if (it.id == next.id) it.copy(status = TaskStatus.RUNNING) else it }
        env.state.currentTaskId = next.id
        env.state.messages += StoredMessage(
            role = MessageRole.USER,
            content = "Current task: ${next.id} — ${next.description}" +
                (if (next.tools.isNotEmpty()) " (expected tools: ${next.tools.joinToString(", ")})" else "") +
                ". All tool calls you make now are recorded under this task. " +
                "Finish it with complete_task('${next.id}', evidence_call_id) where evidence_call_id " +
                "is the EXACT id copied from the tool_call_id field of one of your successful tool " +
                "results above (do not invent an id).",
        )
        env.ui.checklist(env.state.tasks)
    }

    private fun finishTaskSelection() {
        env.state.currentTaskId = null
        if (env.state.tasks.isNotEmpty() && env.state.tasks.all { it.status == TaskStatus.DONE }) {
            env.state.messages += StoredMessage(role = MessageRole.USER, content = Prompts.allTasksDoneMessage)
        }
    }

    fun dependenciesDone(task: TaskState): Boolean =
        task.dependsOn.all { dep -> env.state.tasks.find { it.id == dep }?.status == TaskStatus.DONE }

    fun completeTask(call: ToolCallReq) {
        val taskId = call.args["task_id"]?.jsonPrimitive?.content
        val evidenceId = call.args["evidence_call_id"]?.jsonPrimitive?.content
        val rejection = completionRejection(taskId, evidenceId)
        if (rejection != null) {
            ledger.completeWithoutExecution(call, AttemptOutcome.ERROR, ToolJson.error(rejection, ErrorKind.TASK_NOT_COMPLETED))
            return
        }

        env.state.tasks = env.state.tasks.map {
            if (it.id == taskId) it.copy(status = TaskStatus.DONE, evidenceCallId = evidenceId) else it
        }
        val result = buildJsonObject {
            put("ok", true)
            put("task_id", taskId)
            put("completed", true)
            put("verified_by", evidenceId)
        }
        env.state.budget = env.state.budget.copy(toolCallsSpent = env.state.budget.toolCallsSpent + 1)
        val capped = outputBudget.enforce(result)
        val outputBytes = outputBudget.debit(capped)
        ledger.upsertRecord(
            call,
            Attempt(
                index = ledger.nextAttemptIndex(call),
                outcome = AttemptOutcome.OK,
                result = capped,
                debit = DebitRecord(toolCalls = 1),
                outputBytes = outputBytes,
            ),
        )
        ledger.appendToolMessage(call, capped)
        ledger.dequeue(call.id)

        if (env.state.batchQueue.isEmpty()) {
            selectNextTask()
        } else {
            env.state.taskSelectionPending = true
        }
        env.save()
        env.ui.checklist(env.state.tasks)
    }

    private fun completionRejection(taskId: String?, evidenceId: String?): String? {
        if (taskId == null || evidenceId == null) {
            return "complete_task requires task_id and evidence_call_id"
        }
        val task = env.state.tasks.find { it.id == taskId }
        return when {
            task == null -> "unknown task: $taskId"
            task.status != TaskStatus.RUNNING || env.state.currentTaskId != taskId ->
                "task $taskId is not the current running task (current: ${env.state.currentTaskId})"
            !dependenciesDone(task) -> "task $taskId has unfinished dependencies"
            else -> evidenceRejection(taskId, evidenceId)
        }
    }

    private fun evidenceRejection(taskId: String, evidenceId: String): String? {
        val evidence = ledger.recordFor(evidenceId)
        return when {
            evidence == null -> "evidence call not found: $evidenceId"
            evidence.taskId != taskId ->
                "evidence call $evidenceId belongs to '${evidence.taskId ?: "planning"}', not to task $taskId"
            evidence.lastAttempt?.outcome != AttemptOutcome.OK ->
                "evidence call $evidenceId did not succeed (${evidence.lastAttempt?.outcome}); " +
                    "a textual claim is not enough — verify with a successful tool call"
            else -> null
        }
    }
}
