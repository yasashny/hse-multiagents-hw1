package com.yasashny.agentshw1

import com.yasashny.agentshw1.state.models.RunMode
import java.io.File

data class RunOptions(
    val task: String,
    val mode: RunMode,
    val withPlan: Boolean,
    val workspace: File,
    val policyFile: File?,
    val runsDirOverride: String?,
    val limitsSpec: String?,
)

fun parseRunOptions(args: List<String>): RunOptions {
    var task: String? = null
    var mode = RunMode.SAFE
    var withPlan = false
    var workspace = File("workspace")
    var policyFile: File? = null
    var runsDirOverride: String? = null
    var limitsSpec: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--task" -> {
                task = args.valueAt(i)
                i += 2
            }
            "--mode" -> {
                mode = when (args.valueAt(i)) {
                    "safe" -> RunMode.SAFE
                    "auto" -> RunMode.AUTO
                    else -> throw IllegalArgumentException("--mode must be safe or auto")
                }
                i += 2
            }
            "--plan" -> {
                withPlan = true
                i += 1
            }
            "--workspace" -> {
                workspace = File(args.valueAt(i))
                i += 2
            }
            "--policy" -> {
                policyFile = File(args.valueAt(i)).absoluteFile
                i += 2
            }
            "--runs-dir" -> {
                runsDirOverride = args.valueAt(i)
                i += 2
            }
            "--limits" -> {
                limitsSpec = args.valueAt(i)
                i += 2
            }
            else -> throw IllegalArgumentException("unknown option ${args[i]}")
        }
    }
    val finalTask = task ?: throw IllegalArgumentException("--task is required")
    if (finalTask.isBlank()) {
        throw IllegalArgumentException("--task must not be empty")
    }
    if (finalTask in OPTION_FLAGS) {
        throw IllegalArgumentException("missing value for --task (got '$finalTask', which looks like the next option)")
    }
    return RunOptions(
        task = finalTask,
        mode = mode,
        withPlan = withPlan,
        workspace = workspace,
        policyFile = policyFile,
        runsDirOverride = runsDirOverride,
        limitsSpec = limitsSpec,
    )
}

private val OPTION_FLAGS = setOf("--task", "--mode", "--plan", "--workspace", "--policy", "--runs-dir", "--limits")

internal fun List<String>.valueAt(optionIndex: Int, usage: String? = null): String =
    getOrNull(optionIndex + 1) ?: throw IllegalArgumentException(usage ?: "missing value for ${this[optionIndex]}")
