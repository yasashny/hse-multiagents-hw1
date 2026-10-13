package com.yasashny.agentshw1.tools

import com.yasashny.agentshw1.policy.Decision
import com.yasashny.agentshw1.policy.resolvePath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class BashTool : Tool {

    override val id = ToolName.BASH
    override val description =
        "Run an allowed shell command in a working directory inside the workspace. Only exact simple commands from policy commands.allow are permitted (no pipes, redirects, substitutions). Use it e.g. to run 'python3 -m unittest -v'."
    override val sideEffect = true
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonArray("required") { add("command"); add("cwd") }
        put("properties", buildJsonObject {
            put("command", stringParameter("The exact command string, must match policy commands.allow"))
            put("cwd", stringParameter("Working directory relative to the workspace root, e.g. '.'"))
        })
    }

    override fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val command = args.argString("command")
        val cwdRaw = args.argString("cwd").ifBlank { "." }
        when (val decision = ctx.pathChecker.checkPath(cwdRaw, ctx.workspaceRoot)) {
            is Decision.Deny -> return ToolJson.error("cwd rejected: ${decision.reason}", ErrorKind.PATH_DENIED)
            Decision.Allow -> {}
        }
        val cwd = resolvePath(cwdRaw, ctx.workspaceRoot)
        if (!cwd.isDirectory) return ToolJson.error("cwd is not a directory: $cwdRaw")
        val outcome = ctx.bashExecutor.run(command, cwd, ctx.outputCapBytes)
        return ctx.bashExecutor.resultJson(outcome)
    }
}
