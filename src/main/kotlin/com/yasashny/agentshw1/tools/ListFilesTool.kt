package com.yasashny.agentshw1.tools

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ListFilesTool : Tool {

    override val id = ToolName.LIST_FILES
    override val description =
        "List files and subdirectories of a path inside the workspace. Relative paths are resolved against the workspace root."
    override val sideEffect = false
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonArray("required") { add("path") }
        put("properties", buildJsonObject {
            put("path", stringParameter("Directory path relative to the workspace root, e.g. '.' or 'src'"))
        })
    }

    override fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val raw = args.argString("path")
        return ctx.withCheckedFile(raw) { listDirectory(raw, it, ctx) }
    }

    private fun listDirectory(raw: String, dir: File, ctx: ToolContext): JsonObject {
        if (!dir.exists()) return ToolJson.error("directory does not exist: $raw")
        if (!dir.isDirectory) return ToolJson.error("not a directory: $raw")
        val entries = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        val listing = listingWithinBudget(entries, ctx.outputCapBytes)
        return buildJsonObject {
            put("ok", true)
            put("path", raw)
            putJsonArray("entries") { addAll(listing.lines) }
            if (listing.truncated) {
                put("truncated", true)
                put("note", "showing ${listing.lines.size} of ${entries.size} entries (output budget applies); result is incomplete")
            }
        }
    }

    private fun listingWithinBudget(entries: List<File>, capBytes: Long): Listing {
        val cap = capBytes.coerceAtLeast(0).toInt()
        val lines = mutableListOf<String>()
        var used = 0
        for (entry in entries) {
            val line = if (entry.isDirectory) "${entry.name}/" else entry.name
            val cost = line.length + ENTRY_OVERHEAD_BYTES
            if (lines.size >= MAX_ENTRIES || used + cost > cap) return Listing(lines, truncated = true)
            lines.add(line)
            used += cost
        }
        return Listing(lines, truncated = false)
    }

    private data class Listing(val lines: List<String>, val truncated: Boolean)

    private companion object {
        const val MAX_ENTRIES = 200
        const val ENTRY_OVERHEAD_BYTES = 4
    }
}
