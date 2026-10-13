package com.yasashny.agentshw1.tools

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class WriteFileTool : Tool {

    override val id = ToolName.WRITE_FILE
    override val description = "Create or overwrite a text file inside the workspace with the given content."
    override val sideEffect = true
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonArray("required") { add("path"); add("content") }
        put("properties", buildJsonObject {
            put("path", stringParameter("File path relative to the workspace root"))
            put("content", stringParameter("Full new content of the file"))
        })
    }

    override fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val raw = args.argString("path")
        val content = args.argString("content")
        return ctx.withCheckedFile(raw) { writeFile(raw, content, it) }
    }

    private fun writeFile(raw: String, content: String, file: File): JsonObject {
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return buildJsonObject {
            put("ok", true)
            put("path", raw)
            put("bytes", file.length().toInt())
        }
    }
}
