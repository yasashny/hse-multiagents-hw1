package com.yasashny.agentshw1.state.models

import com.yasashny.agentshw1.llm.models.StoredMessage
import com.yasashny.agentshw1.llm.models.ToolCallReq
import kotlinx.serialization.Serializable

@Serializable
data class RunState(
    val runId: String,
    val createdAt: String,
    var phase: RunPhase,
    var status: RunStatus,
    val mode: RunMode,
    val userTask: String,
    val workspaceRoot: String,
    val policyPath: String,
    val runsDir: String,
    val withPlan: Boolean = false,
    var messages: List<StoredMessage> = emptyList(),
    var calls: List<CallRecord> = emptyList(),
    var batchQueue: List<ToolCallReq> = emptyList(),
    var pendingConfirmation: PendingConfirmation? = null,
    var approvedCallId: String? = null,
    var plan: Plan? = null,
    var tasks: List<TaskState> = emptyList(),
    var currentTaskId: String? = null,
    var taskSelectionPending: Boolean = false,
    var budget: BudgetState = BudgetState(),
    var journal: JournalState = JournalState(),
    var finalAnswer: String? = null,
    var failure: String? = null,
    var nudges: Int = 0,
)
