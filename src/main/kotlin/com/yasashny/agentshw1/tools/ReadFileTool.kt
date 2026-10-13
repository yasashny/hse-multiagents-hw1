package com.yasashny.agentshw1.tools

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ReadFileTool : Tool {

    override val id = ToolName.READ_FILE
    override val description = "Read the text content of a file inside the workspace."
    override val sideEffect = false
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonArray("required") { add("path") }
        put("properties", buildJsonObject {
            put("path", stringParameter("File path relative to the workspace root"))
        })
    }

    override fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val raw = args.argString("path")
        return ctx.withCheckedFile(raw) { readFile(raw, it, ctx) }
    }

    private fun readFile(raw: String, file: File, ctx: ToolContext): JsonObject {
        if (!file.exists()) return ToolJson.error("file does not exist: $raw")
        if (file.isDirectory) return ToolJson.error("path is a directory, not a file: $raw")

        val bytes = file.readBytes()
        val cap = ctx.outputCapBytes.coerceAtLeast(0).toInt()
        val truncated = bytes.size > cap
        val shown = if (truncated) copyUpToCharBoundary(bytes, cap) else bytes
        return buildJsonObject {
            put("ok", true)
            put("path", raw)
            put("bytes", bytes.size)
            if (truncated) {
                put("truncated", true)
                put("note", ToolJson.truncatedNote(shown.size, bytes.size))
            }
            put("content", String(shown, Charsets.UTF_8))
        }
    }

    private fun copyUpToCharBoundary(bytes: ByteArray, cap: Int): ByteArray {
        var end = cap.coerceAtMost(bytes.size)
        while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) end -= 1
        if (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0xC0) end -= 1
        return bytes.copyOf(end)
    }
}
