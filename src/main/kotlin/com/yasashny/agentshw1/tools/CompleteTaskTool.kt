package com.yasashny.agentshw1.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class CompleteTaskTool : Tool {

    override val id = ToolName.COMPLETE_TASK
    override val description =
        "Mark the current task completed. evidence_call_id must be the EXACT id of one of your own previous tool calls in this task that succeeded and verifies its result (e.g. a bash test run with exit code 0) — copy it verbatim from the tool_call_id field of that result; invented or truncated ids are rejected."
    override val sideEffect = false
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonArray("required") { add("task_id"); add("evidence_call_id") }
        put("properties", buildJsonObject {
            put("task_id", stringParameter())
            put("evidence_call_id", stringParameter("callId of a successful tool call belonging to this task that confirms the result"))
        })
    }

    override fun execute(args: JsonObject, ctx: ToolContext): JsonObject =
        ToolJson.error("complete_task is a control tool and must be handled by the runtime", ErrorKind.PROTOCOL)
}
