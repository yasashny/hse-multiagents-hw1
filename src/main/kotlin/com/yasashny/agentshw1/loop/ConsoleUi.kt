package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.state.models.PendingConfirmation
import com.yasashny.agentshw1.state.models.Plan
import com.yasashny.agentshw1.state.models.TaskState

class ConsoleUi : Ui {

    override fun info(msg: String) = println(msg)

    override fun warn(msg: String) = println(renderWarn(msg))

    override fun modelRequest(index: Int) = println(renderModelRequest(index))

    override fun toolStart(callId: String, name: String, summary: String) =
        println(renderToolStart(callId, name, summary))

    override fun toolResult(callId: String, name: String, ok: Boolean, summary: String) =
        println(renderToolResult(name, ok, summary))

    override fun confirmNeeded(confirmation: PendingConfirmation, runId: String) {
        println()
        println("=== CONFIRMATION REQUIRED (${confirmation.reason}) ===")
        println("  ${confirmation.name}(${confirmation.args.entries.joinToString(", ") { (k, v) -> "$k=$v" }})")
        println("To approve: agent approve $runId")
        println("To deny:    agent deny    $runId")
        println("Then continue with: agent resume $runId")
    }

    override fun planReady(plan: Plan) {
        println()
        println("=== PLAN SUBMITTED (waiting for user approval) ===")
        println(renderPlan(plan))
    }

    override fun checklist(tasks: List<TaskState>) = println(renderChecklist(tasks))

    override fun finish(msg: String) = println(renderFinish(msg))
}
