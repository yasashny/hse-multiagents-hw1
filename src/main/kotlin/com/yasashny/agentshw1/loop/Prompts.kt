package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.policy.models.Policy
import com.yasashny.agentshw1.state.models.RunMode
import com.yasashny.agentshw1.state.models.RunPhase

object Prompts {

    fun systemPrompt(phase: RunPhase, mode: RunMode, policy: Policy): String = buildString {
        appendLine("You are a console coding agent. You solve the user's task by calling tools.")
        appendLine("The runtime re-validates every tool call (arguments, policy, sandbox paths, mode) before execution;")
        appendLine("a rejected call returns an error as the tool result — read the reason and choose another step.")
        appendLine("When the work is done, reply with a short final text answer WITHOUT tool calls.")
        appendLine()
        appendLine("Execution mode: $mode — in 'safe' mode every modification (write/delete/bash) first needs the user's confirmation;")
        appendLine("the runtime pauses and resumes after the decision; keep working after a denial, do not repeat the denied action as-is.")
        appendLine()
        appendLine("Bash policy: ONLY these exact command strings are allowed (no pipes, redirects, substitutions, no other commands):")
        policy.commandsAllow.sorted().forEach { appendLine("  - $it") }
        appendLine("Denied commands (by policy): ${policy.commandsDeny.sorted().joinToString(", ").ifEmpty { "<none>" }}")
        appendLine("Run tests with exactly: python3 -m unittest -v (from the workspace root).")
        appendLine()
        if (phase == RunPhase.PLANNING) {
            appendLine("CURRENT PHASE: PLANNING. You may ONLY inspect the project (list_files, read_file).")
            appendLine("You must NOT modify files, delete files or run bash — the runtime rejects such calls in this phase.")
            appendLine("When you understand the task, call submit_plan with a structured plan:")
            appendLine("  goal, steps[{id, description, tools[], dependsOn[]}], risks[], assumptions[], done_criterion.")
            appendLine("Planning ends when the plan is submitted; execution starts only after the user approves the plan.")
        } else {
            appendLine("CURRENT PHASE: EXECUTION. You may read, write and delete files and run allowed bash commands.")
            appendLine("Inspect first, then make minimal changes, then verify by running the tests (bash).")
            appendLine("Never modify test files unless the user explicitly asks for it.")
        }
    }

    fun initialUserMessage(userTask: String, workspaceRoot: String, withPlan: Boolean): String = buildString {
        appendLine("Task: $userTask")
        appendLine("Workspace root: $workspaceRoot (file tool paths are relative to it).")
        if (withPlan) {
            appendLine("Work in plan mode: explore the project first, then submit a plan with submit_plan.")
        } else {
            appendLine("Find the relevant files yourself, fix the problem and verify by running the tests.")
        }
    }

    const val planApprovedMessage =
        "The user APPROVED the plan. Execution starts now. Work task by task; the runtime links every tool call " +
            "to the current task. A task is completed ONLY via complete_task(task_id, evidence_call_id) where " +
            "evidence_call_id is the EXACT id of one of your own previous tool calls in this task " +
            "(copy it character-for-character from the tool_call_id field of a successful result, " +
            "for example a bash test run with exit code 0); invented ids are rejected."

    const val allTasksDoneMessage =
        "All tasks are completed and verified. Provide the final answer now (plain text, no tool calls)."
}
