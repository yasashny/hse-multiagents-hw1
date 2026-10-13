package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.state.models.PendingConfirmation
import com.yasashny.agentshw1.state.models.Plan
import com.yasashny.agentshw1.state.models.TaskState

interface Ui {

    fun info(msg: String)

    fun warn(msg: String)

    fun modelRequest(index: Int)

    fun toolStart(callId: String, name: String, summary: String)

    fun toolResult(callId: String, name: String, ok: Boolean, summary: String)

    fun confirmNeeded(confirmation: PendingConfirmation, runId: String)

    fun planReady(plan: Plan)

    fun checklist(tasks: List<TaskState>)

    fun finish(msg: String)
}
