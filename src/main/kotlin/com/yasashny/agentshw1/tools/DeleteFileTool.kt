package com.yasashny.agentshw1.tools

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class DeleteFileTool : Tool {

    override val id = ToolName.DELETE_FILE
    override val description = "Delete a single file inside the workspace. Directories are not supported."
    override val sideEffect = true
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonArray("required") { add("path") }
        put("properties", buildJsonObject {
            put("path", stringParameter("File path relative to the workspace root"))
        })
    }

    override fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val raw = args.argString("path")
        return ctx.withCheckedFile(raw) { deleteFile(raw, it) }
    }

    private fun deleteFile(raw: String, file: File): JsonObject {
        if (!file.exists()) return ToolJson.error("file does not exist: $raw")
        if (file.isDirectory) return ToolJson.error("refusing to delete a directory: $raw")
        file.delete()
        return buildJsonObject {
            put("ok", true)
            put("path", raw)
            put("deleted", true)
        }
    }
}
