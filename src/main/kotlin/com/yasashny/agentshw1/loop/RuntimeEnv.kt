package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.exec.BashExecutor
import com.yasashny.agentshw1.journal.JournalEngine
import com.yasashny.agentshw1.llm.LLMClient
import com.yasashny.agentshw1.policy.PathChecker
import com.yasashny.agentshw1.policy.SafetyGate
import com.yasashny.agentshw1.policy.models.Policy
import com.yasashny.agentshw1.settings.Settings
import com.yasashny.agentshw1.state.RunStore
import com.yasashny.agentshw1.state.models.RunState
import com.yasashny.agentshw1.state.models.RunStatus
import com.yasashny.agentshw1.tools.Tool
import com.yasashny.agentshw1.tools.ToolCatalog
import com.yasashny.agentshw1.tools.ToolName
import java.io.File

internal const val TOOL_LOG_LENGTH = 160

internal class RuntimeEnv(
    val store: RunStore,
    val llm: LLMClient,
    val policy: Policy,
    val workspaceRoot: File,
    val bashExecutor: BashExecutor,
    val ui: Ui,
    val state: RunState,
) {

    val tools: Map<ToolName, Tool> = ToolCatalog.defaultTools()
    val sideEffectIds: Set<ToolName> = ToolCatalog.sideEffectIds()

    val pathChecker: PathChecker by lazy {
        PathChecker(
            policy,
            listOf(
                policy.sourcePath.canonicalFile,
                File(state.runsDir).canonicalFile,
                Settings.defaultPath().canonicalFile,
            ),
        )
    }

    val gate: SafetyGate by lazy { SafetyGate(policy, pathChecker, workspaceRoot, tools) }

    val journal: JournalEngine by lazy { JournalEngine(workspaceRoot, policy) }

    fun save() = store.save(state)

    fun failRun(status: RunStatus, reason: String) {
        state.status = status
        state.failure = reason
        save()
        ui.warn(reason)
    }

    fun budgetExceeded(reason: String) {
        state.status = RunStatus.BUDGET_EXCEEDED
        state.failure = reason
        save()
        ui.warn("BUDGET EXCEEDED: $reason")
        ui.info(state.budget.summary())
    }
}
