package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.core.nowIso
import com.yasashny.agentshw1.exec.BashExecutor
import com.yasashny.agentshw1.journal.JournalEngine
import com.yasashny.agentshw1.llm.LLMClient
import com.yasashny.agentshw1.llm.models.MessageRole
import com.yasashny.agentshw1.llm.models.StoredMessage
import com.yasashny.agentshw1.policy.models.Policy
import com.yasashny.agentshw1.state.RunStore
import com.yasashny.agentshw1.state.models.BudgetLimits
import com.yasashny.agentshw1.state.models.BudgetState
import com.yasashny.agentshw1.state.models.CallRecord
import com.yasashny.agentshw1.state.models.JournalState
import com.yasashny.agentshw1.state.models.RunMode
import com.yasashny.agentshw1.state.models.RunPhase
import com.yasashny.agentshw1.state.models.RunState
import com.yasashny.agentshw1.state.models.RunStatus
import java.io.File

class AgentRuntime private constructor(
    private val env: RuntimeEnv,
    private val confirmationFlow: ConfirmationFlow,
    private val planFlow: PlanFlow,
    private val dispatcher: BatchDispatcher,
    private val modelSteps: ModelStepRunner,
    private val crashResolver: CrashResolver,
) {

    val state: RunState = env.state

    companion object {

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
            val runtime = wire(RuntimeEnv(store, llm, policy, workspace, bashExecutor, ui, state))
            runtime.env.save()
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
            return wire(
                RuntimeEnv(store, llm, policy, File(loaded.workspaceRoot).canonicalFile, bashExecutor, ui, loaded),
            )
        }

        private fun wire(env: RuntimeEnv): AgentRuntime {
            val outputBudget = OutputBudget(env)
            val ledger = CallLedger(env, outputBudget)
            val planFlow = PlanFlow(env, ledger, outputBudget)
            return AgentRuntime(
                env = env,
                confirmationFlow = ConfirmationFlow(env, ledger),
                planFlow = planFlow,
                dispatcher = BatchDispatcher(env, ledger, outputBudget, planFlow),
                modelSteps = ModelStepRunner(env, ledger),
                crashResolver = CrashResolver(env, ledger),
            )
        }
    }

    fun prepareResume(): Boolean {
        return when (state.status) {
            RunStatus.FAILED -> {
                val reason = state.failure
                state.status = RunStatus.RUNNING
                state.failure = null
                env.save()
                env.ui.warn("run ${state.runId} was FAILED ($reason); explicit resume continues it from the saved state")
                true
            }
            RunStatus.RUNNING, RunStatus.AWAITING_CONFIRMATION, RunStatus.AWAITING_PLAN_APPROVAL -> true
            else -> {
                env.ui.info("run ${state.runId} is ${state.status}; there is nothing to resume")
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
                    env.ui.info("plan is waiting for user approval (agent approve-plan / reject-plan ${state.runId})")
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
            crashResolver.reportIndeterminate(indeterminate)
            return false
        }
        val confirmation = state.pendingConfirmation
        if (confirmation != null) {
            if (confirmation.decision == null) {
                env.ui.confirmNeeded(confirmation, state.runId)
                return false
            }
            confirmationFlow.apply(confirmation)
            return true
        }
        if (state.batchQueue.isNotEmpty()) {
            dispatcher.drainBatch()
            return true
        }

        if (state.taskSelectionPending) {
            state.taskSelectionPending = false
            planFlow.selectNextTask()
            env.save()
        }
        modelSteps.repairDanglingToolCalls()
        modelSteps.modelStep()
        return true
    }

    private fun resumeAfterDecision(): Boolean {
        val confirmation = state.pendingConfirmation ?: return false
        if (confirmation.decision == null) {
            env.ui.confirmNeeded(confirmation, state.runId)
            return false
        }
        state.status = RunStatus.RUNNING
        env.save()
        return true
    }

    fun setConfirmationDecision(runId: String, callId: String, approved: Boolean): Boolean =
        confirmationFlow.setDecision(runId, callId, approved)

    fun approvePlan() = planFlow.approve()

    fun rejectPlan() = planFlow.reject()

    fun resolveIndeterminate(resolutions: Map<String, ResolutionAction>, attribute: Boolean?): List<CallRecord> =
        crashResolver.resolveIndeterminate(resolutions, attribute)

    fun renderJournalDiff(): String = env.journal.renderDiff(state)

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
        val result = env.journal.rollback(state, env.pathChecker)
        if (result is JournalEngine.RollbackResult.Success) {
            state.status = RunStatus.ROLLED_BACK
            val tree = env.journal.scan()
            state.journal = state.journal.copy(
                lastSnapshot = tree.hashes,
                lastSnapshotContents = tree.contents,
            )
            env.save()
        }
        return result
    }
}
