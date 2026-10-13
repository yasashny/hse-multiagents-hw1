package com.yasashny.agentshw1

import com.yasashny.agentshw1.loop.ResolutionAction
import java.io.File

private const val RESOLVE_USAGE = "--resolve id=retry|fail|abort"
private const val ATTRIBUTE_USAGE = "--attribute yes|no"

data class ResumeOptions(
    val runId: String,
    val resolutions: Map<String, ResolutionAction>,
    val attribute: Boolean?,
    val policyFile: File?,
)

fun parseResumeOptions(args: List<String>): ResumeOptions {
    val runId = args.firstOrNull()?.takeIf { !it.startsWith("--") }
        ?: throw IllegalArgumentException("resume <runId> [--resolve id=retry|fail|abort]... [--attribute yes|no]")

    val resolutions = linkedMapOf<String, ResolutionAction>()
    var attribute: Boolean? = null
    var policyFile: File? = null

    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--resolve" -> {
                val spec = args.valueAt(i, RESOLVE_USAGE)
                val parts = spec.split("=", limit = 2)
                if (parts.size != 2) throw IllegalArgumentException(RESOLVE_USAGE)
                resolutions[parts[0]] = ResolutionAction.parse(parts[1])
                i += 2
            }
            "--attribute" -> {
                attribute = when (args.valueAt(i, ATTRIBUTE_USAGE)) {
                    "yes", "true" -> true
                    "no", "false" -> false
                    else -> throw IllegalArgumentException(ATTRIBUTE_USAGE)
                }
                i += 2
            }
            "--policy" -> {
                policyFile = File(args.valueAt(i)).absoluteFile
                i += 2
            }
            else -> throw IllegalArgumentException("unknown option ${args[i]}")
        }
    }
    return ResumeOptions(runId, resolutions, attribute, policyFile)
}
