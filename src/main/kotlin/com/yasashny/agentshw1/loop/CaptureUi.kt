package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.state.models.PendingConfirmation
import com.yasashny.agentshw1.state.models.Plan
import com.yasashny.agentshw1.state.models.TaskState

class CaptureUi : Ui {

    val lines = mutableListOf<String>()

    override fun info(msg: String) {
        lines += msg
    }

    override fun warn(msg: String) {
        lines += renderWarn(msg)
    }

    override fun modelRequest(index: Int) {
        lines += renderModelRequest(index)
    }

    override fun toolStart(callId: String, name: String, summary: String) {
        lines += renderToolStart(callId, name, summary)
    }

    override fun toolResult(callId: String, name: String, ok: Boolean, summary: String) {
        lines += renderToolResult(name, ok, summary)
    }

    override fun confirmNeeded(confirmation: PendingConfirmation, runId: String) {
        lines += "[confirm] ${confirmation.name} ${confirmation.reason}"
    }

    override fun planReady(plan: Plan) {
        lines += renderPlan(plan)
    }

    override fun checklist(tasks: List<TaskState>) {
        lines += renderChecklist(tasks)
    }

    override fun finish(msg: String) {
        lines += renderFinish(msg)
    }
}
