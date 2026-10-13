package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.state.models.Plan
import com.yasashny.agentshw1.state.models.TaskState
import com.yasashny.agentshw1.state.models.TaskStatus

fun renderPlan(plan: Plan): String = buildString {
    appendLine("goal: ${plan.goal}")
    plan.steps.forEachIndexed { i, step ->
        appendLine("  ${i + 1}. [${step.id}] ${step.description}")
        if (step.tools.isNotEmpty()) appendLine("      tools: ${step.tools.joinToString(", ")}")
        if (step.dependsOn.isNotEmpty()) appendLine("      depends on: ${step.dependsOn.joinToString(", ")}")
    }
    if (plan.risks.isNotEmpty()) appendLine("risks: ${plan.risks.joinToString("; ")}")
    if (plan.assumptions.isNotEmpty()) appendLine("assumptions: ${plan.assumptions.joinToString("; ")}")
    if (plan.doneCriterion.isNotEmpty()) appendLine("done when: ${plan.doneCriterion}")
}.trimEnd()

fun renderChecklist(tasks: List<TaskState>): String = buildString {
    appendLine("=== TASKS ===")
    tasks.forEach { task ->
        val mark = when (task.status) {
            TaskStatus.DONE -> "[x]"
            TaskStatus.RUNNING -> "[>]"
            TaskStatus.FAILED -> "[!]"
            TaskStatus.PENDING -> "[ ]"
        }
        val evidence = task.evidenceCallId?.let { "  (verified by $it)" } ?: ""
        appendLine("$mark ${task.id}: ${task.description}$evidence")
    }
}.trimEnd()

fun renderWarn(msg: String): String = "[!] $msg"

fun renderModelRequest(index: Int): String = "[model] request #$index"

fun renderToolStart(callId: String, name: String, summary: String): String = "[tool] $name($summary) [$callId]"

fun renderToolResult(name: String, ok: Boolean, summary: String): String =
    "[tool] $name -> ${if (ok) "ok" else "error"}: $summary"

fun renderFinish(msg: String): String = "[done] $msg"
