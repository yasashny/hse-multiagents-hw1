package com.yasashny.agentshw1.policy.models

import com.yasashny.agentshw1.policy.CommandChecker
import com.yasashny.agentshw1.policy.matchesIgnorePattern
import com.yasashny.agentshw1.policy.resolvePath
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Policy(
    val commandsAllow: Set<String>,
    val commandsDeny: Set<String>,
    val directoriesAllow: List<File>,
    val directoriesDeny: List<File>,
    val journalIgnore: List<String>,
    val sourcePath: File,
) {

    fun validate(workspaceRoot: File, runsDir: File, fail: (String) -> Nothing) {
        val workspace = workspaceRoot.canonicalFile
        if (directoriesAllow.isEmpty()) {
            fail("policy: directories.allow must contain at least one directory")
        }
        for (dir in directoriesAllow) {
            if (!isInside(workspace, dir)) {
                fail(
                    "policy: allowed directory '${dir.canonicalPath}' must be inside " +
                        "workspace '${workspace.canonicalPath}'",
                )
            }
        }
        if (isInside(workspace, runsDir.canonicalFile)) {
            fail("runs directory '${runsDir.canonicalPath}' must be outside the workspace")
        }
        val protectedSources = (
            listOf("order.py", "test_order.py") +
                (workspace.listFiles { f -> f.isFile && f.name.endsWith(".py") }?.map { it.name } ?: emptyList())
            ).distinct()
        for (pattern in journalIgnore) {
            val hidden = protectedSources.filter { matchesIgnorePattern(it, listOf(pattern)) }
            if (hidden.isNotEmpty()) {
                fail(
                    "policy: journal.ignore pattern '$pattern' would hide control project sources/tests " +
                        "(${hidden.joinToString(", ")}); ignore patterns may only match generated files " +
                        "like __pycache__/ or *.pyc",
                )
            }
        }
    }

    companion object {

        fun load(path: File): Policy {
            val root = Json.parseToJsonElement(path.readText()).jsonObject
            val commands = root["commands"]?.jsonObject
            val dirs = root["directories"]?.jsonObject
            val journal = root["journal"]?.jsonObject
            val baseDir = path.absoluteFile.parentFile ?: File(".")

            return Policy(
                commandsAllow = strings(commands, "allow").map { CommandChecker.normalize(it) }.toSet(),
                commandsDeny = strings(commands, "deny").map { CommandChecker.normalize(it) }.toSet(),
                directoriesAllow = strings(dirs, "allow").map { resolvePath(it, baseDir) },
                directoriesDeny = strings(dirs, "deny").map { resolvePath(it, baseDir) },
                journalIgnore = strings(journal, "ignore"),
                sourcePath = path.absoluteFile,
            )
        }

        fun isInside(dir: File, path: File): Boolean {
            val dirPath = dir.canonicalPath.removeSuffix(File.separator) + File.separator
            val pathValue = path.canonicalPath
            return pathValue == dir.canonicalPath || pathValue.startsWith(dirPath)
        }

        private fun strings(obj: JsonObject?, key: String): List<String> =
            obj?.get(key)?.jsonArray
                ?.map { it.jsonPrimitive.content.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
    }
}
