package com.yasashny.agentshw1.policy

import com.yasashny.agentshw1.policy.models.Policy
import com.yasashny.agentshw1.state.models.RunMode
import com.yasashny.agentshw1.state.models.RunPhase
import com.yasashny.agentshw1.tools.SchemaValidator
import com.yasashny.agentshw1.tools.Tool
import com.yasashny.agentshw1.tools.ToolName
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class SafetyGate(
    private val policy: Policy,
    private val pathChecker: PathChecker,
    private val workspaceRoot: File,
    private val tools: Map<ToolName, Tool>,
) {

    sealed interface Verdict {
        data object Execute : Verdict
        data class Confirm(val reason: String) : Verdict
        data class Reject(val reason: String) : Verdict
    }

    private companion object {
        val planningTools = setOf(ToolName.LIST_FILES, ToolName.READ_FILE, ToolName.SUBMIT_PLAN)
        val executionTools = setOf(
            ToolName.LIST_FILES,
            ToolName.READ_FILE,
            ToolName.WRITE_FILE,
            ToolName.DELETE_FILE,
            ToolName.BASH,
            ToolName.COMPLETE_TASK,
        )
    }

    fun toolsForPhase(phase: RunPhase): List<Tool> = toolsFor(phase).mapNotNull { tools[it] }

    fun check(phase: RunPhase, mode: RunMode, toolName: String, args: JsonObject): Verdict {
        val id = ToolName.fromWire(toolName)
            ?: return Verdict.Reject(
                "unknown tool: $toolName (available here: ${toolsForPhase(phase).joinToString { it.name }})",
            )
        val tool = tools[id] ?: return Verdict.Reject("unknown tool: $toolName")

        if (id !in toolsFor(phase)) {
            return Verdict.Reject(
                "tool '$toolName' is not available in phase $phase " +
                    "(planning may only read files and submit_plan; modifications and bash require an approved plan)",
            )
        }

        (args["_invalid_arguments"] as? JsonPrimitive)?.let { marker ->
            return Verdict.Reject(
                "arguments of '$toolName' are not a valid JSON object " +
                    "(got: ${marker.content.take(160)}); resend the tool call with a valid JSON object as arguments",
            )
        }

        val schemaErrors = SchemaValidator.validate(tool.parameters, args)
        if (schemaErrors.isNotEmpty()) {
            return Verdict.Reject("invalid arguments for $toolName: ${schemaErrors.joinToString("; ")}")
        }

        when (id) {
            ToolName.BASH -> checkBashArgs(args)?.let { return it }
            ToolName.LIST_FILES, ToolName.READ_FILE, ToolName.WRITE_FILE, ToolName.DELETE_FILE ->
                checkPathArg(args)?.let { return it }
            else -> {}
        }

        return verdictForMode(mode, id, tool)
    }

    private fun toolsFor(phase: RunPhase): Set<ToolName> =
        if (phase == RunPhase.PLANNING) planningTools else executionTools

    private fun Decision.toReject(prefix: String? = null): Verdict.Reject? =
        when (this) {
            is Decision.Deny -> Verdict.Reject(prefix?.let { "$it: $reason" } ?: reason)
            Decision.Allow -> null
        }

    private fun checkBashArgs(args: JsonObject): Verdict.Reject? {
        val command = args["command"]?.jsonPrimitive?.content.orEmpty()
        CommandChecker.check(policy, command).toReject()?.let { return it }
        val cwd = args["cwd"]?.jsonPrimitive?.content.orEmpty().ifBlank { "." }
        pathChecker.checkPath(cwd, workspaceRoot).toReject("bash cwd rejected")?.let { return it }
        return null
    }

    private fun checkPathArg(args: JsonObject): Verdict.Reject? {
        val path = args["path"]?.jsonPrimitive?.content.orEmpty()
        pathChecker.checkPath(path, workspaceRoot).toReject("path rejected")?.let { return it }
        return null
    }

    private fun verdictForMode(mode: RunMode, id: ToolName, tool: Tool): Verdict {
        val needsConfirmation = when (mode) {
            RunMode.SAFE -> tool.sideEffect
            RunMode.AUTO -> id == ToolName.DELETE_FILE
        }
        if (needsConfirmation) {
            return Verdict.Confirm("mode=$mode requires user confirmation for '${id.wire}' before execution")
        }
        return Verdict.Execute
    }
}
