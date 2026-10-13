package com.yasashny.agentshw1.tools

import com.yasashny.agentshw1.exec.BashExecutor
import com.yasashny.agentshw1.policy.Decision
import com.yasashny.agentshw1.policy.PathChecker
import com.yasashny.agentshw1.policy.resolvePath
import java.io.File
import kotlinx.serialization.json.JsonObject

data class ToolContext(
    val workspaceRoot: File,
    val pathChecker: PathChecker,
    val bashExecutor: BashExecutor,
    val outputCapBytes: Long,
)

internal fun ToolContext.withCheckedFile(rawPath: String, block: (File) -> JsonObject): JsonObject {
    val file = resolvePath(rawPath, workspaceRoot)
    return when (val decision = pathChecker.checkPath(rawPath, workspaceRoot)) {
        is Decision.Deny -> ToolJson.error(decision.reason, ErrorKind.PATH_DENIED)
        Decision.Allow -> block(file)
    }
}
