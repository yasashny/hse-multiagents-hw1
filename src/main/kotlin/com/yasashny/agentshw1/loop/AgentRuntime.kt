package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.core.CrashHook
import com.yasashny.agentshw1.core.CrashPoint
import com.yasashny.agentshw1.core.nowIso
import com.yasashny.agentshw1.exec.BashExecutor
import com.yasashny.agentshw1.journal.JournalEngine
import com.yasashny.agentshw1.llm.LLMClient
import com.yasashny.agentshw1.llm.LLMException
import com.yasashny.agentshw1.llm.models.ModelReply
import com.yasashny.agentshw1.llm.models.MessageRole
import com.yasashny.agentshw1.llm.models.StoredMessage
import com.yasashny.agentshw1.llm.models.ToolCallReq
import com.yasashny.agentshw1.llm.models.ToolDescriptor
import com.yasashny.agentshw1.policy.PathChecker
import com.yasashny.agentshw1.policy.SafetyGate
import com.yasashny.agentshw1.policy.models.Policy
import com.yasashny.agentshw1.settings.Settings
import com.yasashny.agentshw1.state.RunStore
import com.yasashny.agentshw1.state.models.Attempt
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.BudgetLimits
import com.yasashny.agentshw1.state.models.BudgetState
import com.yasashny.agentshw1.state.models.ConfirmationDecision
import com.yasashny.agentshw1.state.models.CallRecord
import com.yasashny.agentshw1.state.models.DebitRecord
import com.yasashny.agentshw1.state.models.JournalState
import com.yasashny.agentshw1.state.models.PendingConfirmation
import com.yasashny.agentshw1.state.models.RunPhase
import com.yasashny.agentshw1.state.models.RunMode
import com.yasashny.agentshw1.state.models.RunState
import com.yasashny.agentshw1.state.models.RunStatus
import com.yasashny.agentshw1.state.models.TaskState
import com.yasashny.agentshw1.state.models.TaskStatus
import com.yasashny.agentshw1.tools.Tool
import com.yasashny.agentshw1.tools.ToolCatalog
import com.yasashny.agentshw1.tools.ToolContext
import com.yasashny.agentshw1.tools.ToolJson
import com.yasashny.agentshw1.tools.ToolName
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.yasashny.agentshw1.tools.ErrorKind

class AgentRuntime private constructor(
    private val store: RunStore,
    private val llm: LLMClient,
    private val policy: Policy,
    private val workspaceRoot: File,
    private val bashExecutor: BashExecutor,
    private val ui: Ui,
    val state: RunState,
) {

    private val tools: Map<ToolName, Tool> = ToolCatalog.defaultTools()
    private val sideEffectIds: Set<ToolName> = ToolCatalog.sideEffectIds()

    private val pathChecker: PathChecker by lazy {
        PathChecker(
            policy,
            listOf(
                policy.sourcePath.canonicalFile,
                File(state.runsDir).canonicalFile,
                Settings.defaultPath().canonicalFile,
            ),
        )
    }

    private val gate: SafetyGate by lazy { SafetyGate(policy, pathChecker, workspaceRoot, tools) }

    private val journal: JournalEngine by lazy { JournalEngine(workspaceRoot, policy) }

    private fun save() = store.save(state)

    companion object {

        private const val TOOL_LOG_LENGTH = 160
        private const val RESULT_SUMMARY_LENGTH = 140
        private const val OUTPUT_WRAPPER_OVERHEAD_BYTES = 200L
        private const val MAX_PLANNING_NUDGES = 1
        private const val MAX_TASK_NUDGES = 3

        fun startRun(
            store: RunStore,
            llm: LLMClient,
            policy: Policy,
            workspaceRoot: File,
            runsDir: File,
            mode: RunMode,
            userTask: String,
            withPlan: Boolean,
            limits: BudgetLimits,
            ui: Ui,
            bashExecutor: BashExecutor = BashExecutor(),
        ): AgentRuntime {
            val workspace = workspaceRoot.canonicalFile
            require(workspace.isDirectory) { "workspace is not a directory: ${workspace.canonicalPath}" }
            policy.validate(workspace, runsDir) { throw IllegalArgumentException("invalid policy: $it") }

            val initialPhase = if (withPlan) RunPhase.PLANNING else RunPhase.EXECUTION
            val initialTree = JournalEngine(workspace, policy).scan()

            val state = RunState(
                runId = store.newRunId(),
                createdAt = nowIso(),
                phase = initialPhase,
                status = RunStatus.RUNNING,
                mode = mode,
                userTask = userTask,
                workspaceRoot = workspace.canonicalPath,
                policyPath = policy.sourcePath.canonicalPath,
                runsDir = runsDir.canonicalPath,
                withPlan = withPlan,
                messages = listOf(
                    StoredMessage(role = MessageRole.SYSTEM, content = Prompts.systemPrompt(initialPhase, mode, policy)),
                    StoredMessage(
                        role = MessageRole.USER,
                        content = Prompts.initialUserMessage(userTask, workspace.canonicalPath, withPlan),
                    ),
                ),
                budget = BudgetState(limits = limits),
                journal = JournalState(
                    lastSnapshot = initialTree.hashes,
                    lastSnapshotContents = initialTree.contents,
                ),
            )
            val runtime = AgentRuntime(store, llm, policy, workspace, bashExecutor, ui, state)
            runtime.save()
            ui.info("run ${state.runId} started (mode=$mode, phase=${state.phase}, workspace=${workspace.canonicalPath})")
            return runtime
        }

        fun attach(
            store: RunStore,
            runId: String,
            llm: LLMClient,
            policy: Policy,
            ui: Ui,
            bashExecutor: BashExecutor = BashExecutor(),
        ): AgentRuntime {
            val loaded = store.loadOrThrow(runId)
            policy.validate(File(loaded.workspaceRoot).canonicalFile, File(loaded.runsDir).canonicalFile) {
                throw IllegalArgumentException("invalid policy: $it")
            }
            val runtime = AgentRuntime(store, llm, policy, File(loaded.workspaceRoot).canonicalFile, bashExecutor, ui, loaded)
            return runtime
        }
    }

    fun prepareResume(): Boolean {
        return when (state.status) {
            RunStatus.FAILED -> {
                val reason = state.failure
                state.status = RunStatus.RUNNING
                state.failure = null
                save()
                ui.warn("run ${state.runId} was FAILED ($reason); explicit resume continues it from the saved state")
                true
            }
            RunStatus.RUNNING, RunStatus.AWAITING_CONFIRMATION, RunStatus.AWAITING_PLAN_APPROVAL -> true
            else -> {
                ui.info("run ${state.runId} is ${state.status}; there is nothing to resume")
                false
            }
        }
    }

    fun runLoop() {
        while (true) {
            val continueLoop = when (state.status) {
                RunStatus.RUNNING -> stepRunning()
                RunStatus.AWAITING_CONFIRMATION -> resumeAfterDecision()
                RunStatus.AWAITING_PLAN_APPROVAL -> {
                    ui.info("plan is waiting for user approval (agent approve-plan / reject-plan ${state.runId})")
                    false
                }
                else -> false
            }
            if (!continueLoop) return
        }
    }

    private fun stepRunning(): Boolean {
        val indeterminate = state.calls.filter { it.isIndeterminate }
        if (indeterminate.isNotEmpty()) {
            reportIndeterminate(indeterminate)
            return false
        }
        val confirmation = state.pendingConfirmation
        if (confirmation != null) {
            if (confirmation.decision == null) {
                ui.confirmNeeded(confirmation, state.runId)
                return false
            }
            applyConfirmationDecision(confirmation)
            return true
        }
        if (state.batchQueue.isNotEmpty()) {
            drainBatch()
            return true
        }
        
        
        if (state.taskSelectionPending) {
            state.taskSelectionPending = false
            selectNextTask()
            save()
        }
        repairDanglingToolCalls()
        modelStep()
        return true
    }

    private fun resumeAfterDecision(): Boolean {
        val confirmation = state.pendingConfirmation ?: return false
        if (confirmation.decision == null) {
            ui.confirmNeeded(confirmation, state.runId)
            return false
        }
        state.status = RunStatus.RUNNING
        save()
        return true
    }

    private fun modelStep() {
        if (state.budget.modelRequestsLeft <= 0) {
            budgetExceeded("model request budget exhausted: limit ${state.budget.limits.modelRequests}")
            return
        }
        state.budget = state.budget.copy(modelRequestsSpent = state.budget.modelRequestsSpent + 1)
        save()
        ui.modelRequest(state.budget.modelRequestsSpent)

        val reply = try {
            llm.chat(
                state.messages,
                advertisedTools().map { ToolDescriptor(it.name, it.description, it.parameters) },
            )
        } catch (e: LLMException) {
            failRun(RunStatus.FAILED, "model request failed: ${e.message}")
            return
        }

        when (reply) {
            is ModelReply.Text -> handleFinalText(reply.text)
            is ModelReply.ToolCalls -> handleToolCallsReply(reply)
        }
    }

    private fun handleToolCallsReply(reply: ModelReply.ToolCalls) {
        val fresh = reply.calls.filter { needsExecution(it) }
        val neededCalls = fresh.size
        val neededSideEffects = fresh.count { ToolName.fromWire(it.name) in sideEffectIds }
        if (state.budget.toolCallsLeft < neededCalls || state.budget.sideEffectsLeft < neededSideEffects) {
            state.messages += StoredMessage(role = MessageRole.ASSISTANT, toolCalls = reply.calls)
            state.batchQueue = emptyList()
            budgetExceeded(
                "tool call budget exhausted: batch needs $neededCalls tool calls " +
                    "(left ${state.budget.toolCallsLeft}) and $neededSideEffects side effects " +
                    "(left ${state.budget.sideEffectsLeft}); NONE of the ${reply.calls.size} batch calls was executed",
            )
            return
        }
        state.messages += StoredMessage(role = MessageRole.ASSISTANT, toolCalls = reply.calls)
        state.batchQueue = reply.calls
        
        
        state.nudges = 0
        save()
    }

    private fun advertisedTools(): List<Tool> =
        gate.toolsForPhase(state.phase).filterNot { it.id == ToolName.COMPLETE_TASK && state.tasks.isEmpty() }

    private fun needsExecution(call: ToolCallReq): Boolean {
        val existing = recordFor(call.id) ?: return true
        return existing.name != call.name ||
            existing.args != call.args ||
            existing.lastAttempt?.outcome == AttemptOutcome.RESOLVED_RETRY
    }

    private fun handleFinalText(text: String) {
        state.messages += StoredMessage(role = MessageRole.ASSISTANT, content = text)
        if (state.phase == RunPhase.PLANNING) {
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
        if (state.nudges >= MAX_TASK_NUDGES) {
            failRun(RunStatus.FAILED, "model kept returning empty replies (no text and no tool calls)")
            return
        }
        state.nudges += 1
        state.messages += StoredMessage(
            role = MessageRole.USER,
            content = "Your last reply was empty: it contained neither text nor tool calls. " +
                "Continue the task: call one of the available tools or return the final text answer.",
        )
        save()
    }

    private fun handlePlanningFinalText() {
        if (state.nudges >= MAX_PLANNING_NUDGES) {
            failRun(RunStatus.FAILED, "planning phase ended without a submitted plan")
            return
        }
        state.nudges += 1
        state.messages += StoredMessage(
            role = MessageRole.USER,
            content = "Planning is not finished: explore the project with list_files/read_file " +
                "and call submit_plan with a structured plan.",
        )
        save()
    }

    private fun handleExecutionFinalText(text: String) {
        val pendingTasks = state.tasks.filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING }
        if (pendingTasks.isEmpty()) {
            state.finalAnswer = text
            state.status = RunStatus.FINISHED
            save()
            ui.finish("run ${state.runId} finished: $text")
            ui.info(state.budget.summary())
            return
        }
        if (state.nudges >= MAX_TASK_NUDGES) {
            failRun(RunStatus.FAILED, "model kept returning final answers with pending tasks")
            return
        }
        state.nudges += 1
        state.messages += StoredMessage(
            role = MessageRole.USER,
            content = "The task list is not complete yet:\n${renderChecklist(state.tasks)}\n" +
                "Continue with the current task '${state.currentTaskId}' and call " +
                "complete_task(task_id, evidence_call_id) when its result is verified. Do not give the final answer yet.",
        )
        save()
    }

    private fun repairDanglingToolCalls() {
        val last = state.messages.lastOrNull() ?: return
        if (last.role != MessageRole.ASSISTANT || last.toolCalls.isEmpty()) return
        for (call in last.toolCalls) {
            val result = ToolJson.error(
                "call interrupted by a crash before execution; no result was recorded",
                ErrorKind.INTERRUPTED,
            )
            state.messages += StoredMessage(
                role = MessageRole.TOOL,
                content = result.toString(),
                toolCallId = call.id,
                name = call.name,
            )
        }
        save()
        ui.warn("repaired ${last.toolCalls.size} tool call(s) interrupted by a crash before execution")
    }

    private fun drainBatch() {
        while (state.status == RunStatus.RUNNING &&
            state.pendingConfirmation == null &&
            state.batchQueue.isNotEmpty()
        ) {
            dispatchOne(state.batchQueue.first())
        }
    }

    private fun dispatchOne(call: ToolCallReq) {
        val ordinal = dispatchOrdinal()
        val toolName = ToolName.fromWire(call.name)

        if (handleDuplicate(call)) return

        if (state.budget.toolCallsLeft <= 0 ||
            (toolName in sideEffectIds && state.budget.sideEffectsLeft <= 0)
        ) {
            budgetExceeded(
                "cannot start '${call.name}': budget exhausted " +
                    "(toolCalls left ${state.budget.toolCallsLeft}, sideEffects left ${state.budget.sideEffectsLeft}); " +
                    "the action was not started and nothing was debited",
            )
            return
        }

        ui.toolStart(call.id, call.name, call.args.toString().take(TOOL_LOG_LENGTH))

        when (resolveVerdict(call)) {
            is SafetyGate.Verdict.Reject -> return
            is SafetyGate.Verdict.Confirm -> return
            SafetyGate.Verdict.Execute -> {}
        }

        when (toolName) {
            ToolName.SUBMIT_PLAN -> {
                handleSubmitPlan(call)
                return
            }
            ToolName.COMPLETE_TASK -> {
                handleCompleteTask(call)
                return
            }
            else -> {}
        }

        val tool = toolName?.let { tools[it] } ?: run {
            completeWithoutExecution(call, AttemptOutcome.DENIED_POLICY, ToolJson.error("unknown tool ${call.name}"))
            return
        }
        if (tool.sideEffect) {
            dispatchSideEffect(call, tool, ordinal)
        } else {
            dispatchReadOnly(call, tool, ordinal)
        }
    }

    private fun resolveVerdict(call: ToolCallReq): SafetyGate.Verdict {
        var verdict = gate.check(state.phase, state.mode, call.name, call.args)
        if (verdict is SafetyGate.Verdict.Confirm && call.id == state.approvedCallId) {
            verdict = SafetyGate.Verdict.Execute
            state.approvedCallId = null
        }
        when (verdict) {
            is SafetyGate.Verdict.Reject ->
                completeWithoutExecution(call, AttemptOutcome.DENIED_POLICY, ToolJson.error(verdict.reason, ErrorKind.DENIED_BY_POLICY))
            is SafetyGate.Verdict.Confirm -> {
                state.pendingConfirmation = PendingConfirmation(call.id, call.name, call.args, verdict.reason)
                state.status = RunStatus.AWAITING_CONFIRMATION
                save()
            }
            SafetyGate.Verdict.Execute -> {}
        }
        return verdict
    }

    private fun handleDuplicate(call: ToolCallReq): Boolean {
        val existing = recordFor(call.id) ?: return false
        if (existing.lastAttempt?.outcome == AttemptOutcome.RESOLVED_RETRY) return false

        if (existing.name != call.name || existing.args != call.args) {
            completeWithoutExecution(
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
        val stored = existing.lastRealResult ?: ToolJson.error("no stored result for call ${call.id}")
        appendToolMessage(call, stored)
        dequeue(call.id)
        save()
        ui.toolResult(call.id, call.name, ok = false, summary = "replayed stored result (no re-execution)")
        return true
    }

    private fun beginAttempt(call: ToolCallReq, ordinal: Int, sideEffect: Boolean) {
        state.budget = state.budget.copy(
            toolCallsSpent = state.budget.toolCallsSpent + 1,
            sideEffectsSpent = state.budget.sideEffectsSpent + if (sideEffect) 1 else 0,
        )
        upsertRecord(
            call,
            Attempt(
                index = nextAttemptIndex(call),
                outcome = AttemptOutcome.RUNNING,
                sideEffect = sideEffect,
                debit = DebitRecord(toolCalls = 1, sideEffects = if (sideEffect) 1 else 0),
            ),
        )
        save()
        crashPoint(ordinal, CrashPoint.AFTER_DEBIT_BEFORE_EXEC, call)
    }

    private fun dispatchReadOnly(call: ToolCallReq, tool: Tool, ordinal: Int) {
        beginAttempt(call, ordinal, sideEffect = false)
        val result = executeTool(tool, call)
        crashPoint(ordinal, CrashPoint.AFTER_EXEC_BEFORE_SAVE, call)
        finishAttempt(call, result)
    }

    private fun dispatchSideEffect(call: ToolCallReq, tool: Tool, ordinal: Int) {
        val conflict = journal.preCheck(state)
        if (conflict != null) {
            state.journal = state.journal.copy(conflicts = state.journal.conflicts + conflict.describe())
            completeWithoutExecution(
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
        val touched = journal.postAction(state)
        if (touched.isNotEmpty()) {
            ui.info("journal: recorded changes to ${touched.joinToString(", ")}")
        }
        finishAttempt(call, result)
    }

    private fun executeTool(tool: Tool, call: ToolCallReq): JsonObject = try {
        val context = ToolContext(
            workspaceRoot = workspaceRoot,
            pathChecker = pathChecker,
            bashExecutor = bashExecutor,
            outputCapBytes = state.budget.outputBytesLeft.coerceAtLeast(0),
        )
        tool.execute(call.args, context)
    } catch (e: CrashHook.HardCrash) {
        throw e
    } catch (e: Exception) {
        ToolJson.error("tool execution failed: ${e.message}")
    }

    private fun recordTerminalAttempt(call: ToolCallReq, outcome: AttemptOutcome, result: JsonObject): JsonObject {
        state.budget = state.budget.copy(toolCallsSpent = state.budget.toolCallsSpent + 1)
        val capped = enforceOutputCap(result)
        val outputBytes = debitOutput(capped)
        upsertRecord(
            call,
            Attempt(
                index = nextAttemptIndex(call),
                outcome = outcome,
                result = capped,
                debit = DebitRecord(toolCalls = 1),
                outputBytes = outputBytes,
            ),
        )
        appendToolMessage(call, capped)
        dequeue(call.id)
        save()
        return capped
    }

    private fun completeWithoutExecution(call: ToolCallReq, outcome: AttemptOutcome, result: JsonObject) {
        val capped = recordTerminalAttempt(call, outcome, result)
        ui.toolResult(call.id, call.name, ok = capped.isOk(), summary = capped.errorText()?.take(RESULT_SUMMARY_LENGTH) ?: outcome.name)
    }

    private fun finishAttempt(call: ToolCallReq, result: JsonObject) {
        val capped = enforceOutputCap(result)
        val outputBytes = debitOutput(capped)
        val outcome = if (capped.isOk()) AttemptOutcome.OK else AttemptOutcome.ERROR
        updateLastAttempt(call) { it.copy(outcome = outcome, result = capped, outputBytes = outputBytes) }
        appendToolMessage(call, capped)
        dequeue(call.id)
        save()
        ui.toolResult(call.id, call.name, ok = outcome == AttemptOutcome.OK, summary = resultSummary(capped))
    }

    private fun enforceOutputCap(result: JsonObject): JsonObject {
        val left = state.budget.outputBytesLeft.coerceAtLeast(0)
        val serialized = result.toString()
        val size = serialized.toByteArray(Charsets.UTF_8).size
        if (size <= left) return result
        
        
        val partialBytes = (left - OUTPUT_WRAPPER_OVERHEAD_BYTES)
            .coerceIn(0, Int.MAX_VALUE.toLong())
            .toInt()
        val partial = String(serialized.toByteArray(Charsets.UTF_8).copyOf(partialBytes), Charsets.UTF_8)
        return buildJsonObject {
            put("ok", result["ok"] ?: JsonPrimitive(false))
            put("truncated", true)
            put("note", "result of $size bytes exceeded the remaining output budget and was truncated; it is incomplete")
            put("partial", partial)
        }
    }

    private fun debitOutput(result: JsonObject): Long {
        val size = result.toString().toByteArray(Charsets.UTF_8).size.toLong()
        val debit = size.coerceAtMost(state.budget.outputBytesLeft.coerceAtLeast(0))
        state.budget = state.budget.copy(outputBytesSpent = state.budget.outputBytesSpent + debit)
        return debit
    }

    private fun upsertRecord(call: ToolCallReq, attempt: Attempt) {
        val index = state.calls.indexOfFirst { it.callId == call.id }
        if (index >= 0) {
            val updated = state.calls[index].copy(attempts = state.calls[index].attempts + attempt)
            state.calls = state.calls.toMutableList().also { it[index] = updated }
        } else {
            state.calls += CallRecord(
                            callId = call.id,
                            name = call.name,
                            args = call.args,
                            phase = state.phase,
                            taskId = state.currentTaskId,
                            attempts = listOf(attempt),
                        )
        }
    }

    private fun updateLastAttempt(call: ToolCallReq, transform: (Attempt) -> Attempt) {
        val index = state.calls.indexOfFirst { it.callId == call.id }
        if (index < 0) return
        val record = state.calls[index]
        val attempts = record.attempts.toMutableList()
        if (attempts.isNotEmpty()) {
            attempts[attempts.lastIndex] = transform(attempts.last())
        }
        state.calls = state.calls.toMutableList().also { it[index] = record.copy(attempts = attempts) }
    }

    private fun appendToolMessage(call: ToolCallReq, result: JsonObject) {
        val resultWithCallId = JsonObject(result + ("call_id" to JsonPrimitive(call.id)))
        state.messages += StoredMessage(
            role = MessageRole.TOOL,
            content = resultWithCallId.toString(),
            toolCallId = call.id,
            name = call.name,
        )
    }

    private fun recordFor(callId: String): CallRecord? = state.calls.find { it.callId == callId }

    private fun nextAttemptIndex(call: ToolCallReq): Int =
        recordFor(call.id)?.attempts?.size ?: 0

    private fun dispatchOrdinal(): Int = state.calls.sumOf { it.attempts.size } + 1

    private fun dequeue(callId: String) {
        val index = state.batchQueue.indexOfFirst { it.id == callId }
        if (index >= 0) {
            state.batchQueue = state.batchQueue.filterIndexed { i, _ -> i != index }
        }
    }

    private fun resultSummary(result: JsonObject): String {
        result.errorText()?.let { return it.take(RESULT_SUMMARY_LENGTH) }
        result["stdout"]?.jsonPrimitive?.contentOrNull?.let { stdout ->
            val stderr = result["stderr"]?.jsonPrimitive?.contentOrNull
            
            
            return "exit=${result["exit_code"]?.jsonPrimitive?.content}, ${stdout.length} bytes stdout" +
                (if (stderr != null) ", ${stderr.length} bytes stderr" else "")
        }
        result["content"]?.jsonPrimitive?.contentOrNull?.let { content ->
            return "${content.length} chars"
        }
        return result.keys.joinToString(",")
    }

    private fun JsonObject.isOk(): Boolean = this["ok"]?.jsonPrimitive?.contentOrNull == "true"
    private fun JsonObject.errorText(): String? = this["error"]?.jsonPrimitive?.contentOrNull

    private fun crashPoint(ordinal: Int, point: CrashPoint, call: ToolCallReq) {
        val active = CrashHook.activePointFor(ordinal) ?: return
        if (active == point) {
            CrashHook.crash(point, "at dispatch #$ordinal of ${call.name}(${call.args.toString().take(TOOL_LOG_LENGTH)})")
        }
    }

    fun setConfirmationDecision(runId: String, callId: String, approved: Boolean): Boolean {
        check(state.runId == runId)
        val confirmation = state.pendingConfirmation ?: return false
        if (confirmation.callId != callId) return false
        state.pendingConfirmation = confirmation.copy(
            decision = if (approved) ConfirmationDecision.APPROVED else ConfirmationDecision.DENIED,
        )
        save()
        return true
    }

    private fun applyConfirmationDecision(confirmation: PendingConfirmation) {
        when (confirmation.decision) {
            ConfirmationDecision.APPROVED -> applyApproval(confirmation)
            ConfirmationDecision.DENIED -> applyDenial(confirmation)
            null -> {}
        }
    }

    private fun applyApproval(confirmation: PendingConfirmation) {
        state.pendingConfirmation = null
        state.approvedCallId = confirmation.callId
        save()
        ui.info("user approved ${confirmation.name}; re-checking policy and executing")
    }

    private fun applyDenial(confirmation: PendingConfirmation) {
        state.pendingConfirmation = null
        val call = state.batchQueue.firstOrNull { it.id == confirmation.callId } ?: run {
            save()
            return
        }
        recordTerminalAttempt(
            call,
            AttemptOutcome.USER_DENIED,
            ToolJson.error(
                "action denied by the user (${confirmation.reason}); choose a different step",
                ErrorKind.USER_DENIED,
            ),
        )
        ui.toolResult(call.id, call.name, ok = false, summary = "denied by user")
    }

    private fun handleSubmitPlan(call: ToolCallReq) {
        val plan = PlanValidator.parse(call.args)
        if (plan == null) {
            completeWithoutExecution(
                call,
                AttemptOutcome.ERROR,
                ToolJson.error("submit_plan arguments do not form a valid plan", ErrorKind.INVALID_PLAN),
            )
            return
        }
        val validationError = PlanValidator.validate(plan)
        if (validationError != null) {
            completeWithoutExecution(call, AttemptOutcome.ERROR, ToolJson.error("invalid plan: $validationError", ErrorKind.INVALID_PLAN))
            return
        }

        val result = buildJsonObject {
            put("ok", true)
            put("plan_submitted", true)
            put("steps", plan.steps.size)
        }
        state.budget = state.budget.copy(toolCallsSpent = state.budget.toolCallsSpent + 1)
        val capped = enforceOutputCap(result)
        val outputBytes = debitOutput(capped)
        upsertRecord(
            call,
            Attempt(
                index = nextAttemptIndex(call),
                outcome = AttemptOutcome.OK,
                result = capped,
                debit = DebitRecord(toolCalls = 1),
                outputBytes = outputBytes,
            ),
        )
        appendToolMessage(call, capped)
        cancelRemainingBatch(call.id)
        state.plan = plan
        state.status = RunStatus.AWAITING_PLAN_APPROVAL
        save()
        ui.planReady(plan)
    }

    private fun cancelRemainingBatch(submittedCallId: String) {
        state.batchQueue.filter { it.id != submittedCallId }.forEach { call ->
            val skipped = enforceOutputCap(
                ToolJson.error("skipped: plan submitted, planning phase ended", ErrorKind.SKIPPED),
            )
            debitOutput(skipped)
            state.messages += StoredMessage(
                            role = MessageRole.TOOL,
                            content = skipped.toString(),
                            toolCallId = call.id,
                            name = call.name,
                        )
        }
        state.batchQueue = emptyList()
    }

    fun approvePlan() {
        check(state.status == RunStatus.AWAITING_PLAN_APPROVAL) {
            "run ${state.runId} is not awaiting plan approval (status: ${state.status})"
        }
        val plan = state.plan ?: error("no plan to approve")
        state.tasks = plan.steps.map { TaskState(id = it.id, description = it.description, tools = it.tools, dependsOn = it.dependsOn) }
        state.phase = RunPhase.EXECUTION
        state.status = RunStatus.RUNNING
        state.messages += StoredMessage(
                    role = MessageRole.USER,
                    content = Prompts.planApprovedMessage + "\n\n" + renderChecklist(state.tasks),
                )
        selectNextTask()
        save()
        ui.checklist(state.tasks)
        ui.info("plan approved; execution starts (approval does not pre-approve dangerous actions in ${state.mode} mode)")
    }

    fun rejectPlan() {
        check(state.status == RunStatus.AWAITING_PLAN_APPROVAL) { "run is not awaiting plan approval" }
        state.status = RunStatus.PLAN_REJECTED
        state.failure = "plan rejected by user"
        save()
        ui.info("plan rejected; run ${state.runId} terminated")
    }

    private fun selectNextTask() {
        val next = state.tasks.firstOrNull { it.status == TaskStatus.PENDING && dependenciesDone(it) }
        if (next == null) {
            finishTaskSelection()
            return
        }
        state.tasks = state.tasks.map { if (it.id == next.id) it.copy(status = TaskStatus.RUNNING) else it }
        state.currentTaskId = next.id
        state.messages += StoredMessage(
                    role = MessageRole.USER,
                    content = "Current task: ${next.id} — ${next.description}" +
                        (if (next.tools.isNotEmpty()) " (expected tools: ${next.tools.joinToString(", ")})" else "") +
                        ". All tool calls you make now are recorded under this task. " +
                        "Finish it with complete_task('${next.id}', evidence_call_id) where evidence_call_id " +
                        "is the EXACT id copied from the tool_call_id field of one of your successful tool " +
                        "results above (do not invent an id).",
                )
        ui.checklist(state.tasks)
    }

    private fun finishTaskSelection() {
        state.currentTaskId = null
        if (state.tasks.isNotEmpty() && state.tasks.all { it.status == TaskStatus.DONE }) {
            state.messages += StoredMessage(role = MessageRole.USER, content = Prompts.allTasksDoneMessage)
        }
    }

    private fun dependenciesDone(task: TaskState): Boolean =
        task.dependsOn.all { dep -> state.tasks.find { it.id == dep }?.status == TaskStatus.DONE }

    private fun handleCompleteTask(call: ToolCallReq) {
        val taskId = call.args["task_id"]?.jsonPrimitive?.content
        val evidenceId = call.args["evidence_call_id"]?.jsonPrimitive?.content
        val rejection = completionRejection(taskId, evidenceId)
        if (rejection != null) {
            completeWithoutExecution(call, AttemptOutcome.ERROR, ToolJson.error(rejection, ErrorKind.TASK_NOT_COMPLETED))
            return
        }

        state.tasks = state.tasks.map {
            if (it.id == taskId) it.copy(status = TaskStatus.DONE, evidenceCallId = evidenceId) else it
        }
        val result = buildJsonObject {
            put("ok", true)
            put("task_id", taskId)
            put("completed", true)
            put("verified_by", evidenceId)
        }
        state.budget = state.budget.copy(toolCallsSpent = state.budget.toolCallsSpent + 1)
        val capped = enforceOutputCap(result)
        val outputBytes = debitOutput(capped)
        upsertRecord(
            call,
            Attempt(
                index = nextAttemptIndex(call),
                outcome = AttemptOutcome.OK,
                result = capped,
                debit = DebitRecord(toolCalls = 1),
                outputBytes = outputBytes,
            ),
        )
        appendToolMessage(call, capped)
        dequeue(call.id)
        
        
        
        
        if (state.batchQueue.isEmpty()) {
            selectNextTask()
        } else {
            state.taskSelectionPending = true
        }
        save()
        ui.checklist(state.tasks)
    }
    private fun completionRejection(taskId: String?, evidenceId: String?): String? {
        if (taskId == null || evidenceId == null) {
            return "complete_task requires task_id and evidence_call_id"
        }
        val task = state.tasks.find { it.id == taskId }
        return when {
            task == null -> "unknown task: $taskId"
            task.status != TaskStatus.RUNNING || state.currentTaskId != taskId ->
                "task $taskId is not the current running task (current: ${state.currentTaskId})"
            !dependenciesDone(task) -> "task $taskId has unfinished dependencies"
            else -> evidenceRejection(taskId, evidenceId)
        }
    }

    private fun evidenceRejection(taskId: String, evidenceId: String): String? {
        val evidence = recordFor(evidenceId)
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

    private fun reportIndeterminate(calls: List<CallRecord>) {
        ui.warn(
            "run ${state.runId} has ${calls.size} indeterminate tool call(s) " +
                "(crashed between execution and result saving):",
        )
        calls.forEach { call ->
            ui.warn("  - ${call.callId}: ${call.name}(${call.args.toString().take(TOOL_LOG_LENGTH)}) [attempt ${call.lastAttempt?.index}]")
        }
        val mayHaveSideEffects = calls.any { it.lastAttempt?.sideEffect == true }
        if (mayHaveSideEffects) {
            journal.preCheck(state)?.let { diff ->
                ui.warn("workspace tree differs from the last snapshot — the crashed call(s) may have made these changes:")
                ui.warn("  ${diff.describe()}")
                ui.warn(
                    "decide with --attribute yes (changes belong to this run, they will roll back with it) " +
                        "or --attribute no (treat as external).",
                )
            }
        }
        ui.info("resolve each call and continue with: agent resume ${state.runId} --resolve <callId>=retry|fail|abort [--attribute yes|no]")
    }

    fun resolveIndeterminate(resolutions: Map<String, ResolutionAction>, attribute: Boolean?): List<CallRecord> {
        val indeterminate = state.calls.filter { it.isIndeterminate }
        val ids = indeterminate.map { it.callId }.toSet()
        require(resolutions.keys == ids) {
            "resolutions required exactly for ${ids.joinToString(", ")} " +
                "(got ${resolutions.keys.joinToString(", ").ifEmpty { "none" }})"
        }

        val externalDiff = if (indeterminate.any { it.lastAttempt?.sideEffect == true }) journal.preCheck(state) else null
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
            val diff = journal.absorbCurrentTree(state, attributed)
            ui.info(
                "journal: crashed-call changes " +
                    "${if (attributed) "attributed to this run" else "treated as external"}: " +
                    diff.allPaths().joinToString(", "),
            )
        }
        when (action) {
            ResolutionAction.ABORT -> {
                updateLastAttempt(call) {
                    it.copy(outcome = AttemptOutcome.FAILED_BY_USER, note = "run aborted by user after crash")
                }
                failRun(RunStatus.ABORTED_BY_USER, "aborted by user while resolving indeterminate call ${record.callId}")
            }
            ResolutionAction.FAIL -> {
                val result = ToolJson.error(
                    "step marked failed by the user after a crash (result was indeterminate)",
                    ErrorKind.FAILED_AFTER_CRASH,
                )
                updateLastAttempt(call) {
                    it.copy(outcome = AttemptOutcome.FAILED_BY_USER, result = result, note = "resolved: fail")
                }
                appendToolMessage(call, result)
                dequeue(record.callId)
                save()
                ui.info("call ${record.callId} marked failed; the model will see the failure and continue")
            }
            ResolutionAction.RETRY -> {
                updateLastAttempt(call) {
                    it.copy(outcome = AttemptOutcome.RESOLVED_RETRY, note = "resolved: explicit retry")
                }
                save()
                ui.info("call ${record.callId} scheduled for an explicit retry (new attempt of the same tool call)")
            }
        }
    }

    private fun CallRecord.toToolCall(): ToolCallReq = ToolCallReq(callId, name, args)

    private fun failRun(status: RunStatus, reason: String) {
        state.status = status
        state.failure = reason
        save()
        ui.warn(reason)
    }

    private fun budgetExceeded(reason: String) {
        state.status = RunStatus.BUDGET_EXCEEDED
        state.failure = reason
        save()
        ui.warn("BUDGET EXCEEDED: $reason")
        ui.info(state.budget.summary())
    }

    fun renderJournalDiff(): String = journal.renderDiff(state)

    fun rollback(): JournalEngine.RollbackResult {
        if (state.status == RunStatus.ROLLED_BACK) {
            return JournalEngine.RollbackResult.Success(emptyList(), emptyList())
        }
        if (state.calls.any { it.isIndeterminate }) {
            return JournalEngine.RollbackResult.Conflict(
                "run ${state.runId} has indeterminate calls; " +
                    "resolve them first (agent resume ${state.runId} --resolve ...)",
            )
        }
        val result = journal.rollback(state, pathChecker)
        if (result is JournalEngine.RollbackResult.Success) {
            state.status = RunStatus.ROLLED_BACK
            val tree = journal.scan()
            state.journal = state.journal.copy(
                lastSnapshot = tree.hashes,
                lastSnapshotContents = tree.contents,
            )
            save()
        }
        return result
    }
}
