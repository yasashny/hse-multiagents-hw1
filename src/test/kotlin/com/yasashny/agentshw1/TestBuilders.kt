package com.yasashny.agentshw1

import com.yasashny.agentshw1.core.CrashHook
import com.yasashny.agentshw1.llm.models.ModelReply
import com.yasashny.agentshw1.llm.models.ToolCallReq
import com.yasashny.agentshw1.state.models.CallRecord
import com.yasashny.agentshw1.state.models.RunState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun varargJson(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, v) }
}

fun call(id: String, name: String, args: JsonObject): ToolCallReq = ToolCallReq(id, name, args)

fun toolReply(vararg calls: ToolCallReq): ModelReply = ModelReply.ToolCalls(calls.toList())

fun textReply(text: String): ModelReply = ModelReply.Text(text)

fun complete(id: String, taskId: String, evidence: String): ModelReply =
    toolReply(call(id, "complete_task", varargJson("task_id" to taskId, "evidence_call_id" to evidence)))

fun RunState.call(callId: String): CallRecord = calls.first { it.callId == callId }

fun planStep(id: String, description: String, tool: String, dependsOn: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("id", id)
        put("description", description)
        put("tools", buildJsonArray { add(tool) })
        put("dependsOn", buildJsonArray { dependsOn.forEach { add(it) } })
    }

fun planReplyOf(
    callId: String,
    goal: String,
    steps: List<JsonObject>,
    risks: String,
    assumptions: String,
    doneCriterion: String,
): ModelReply = toolReply(
    call(
        callId, "submit_plan",
        buildJsonObject {
            put("goal", goal)
            put("steps", buildJsonArray { steps.forEach { add(it) } })
            put("risks", buildJsonArray { add(risks) })
            put("assumptions", buildJsonArray { add(assumptions) })
            put("done_criterion", doneCriterion)
        },
    ),
)

fun withCrashPoint(point: String, callOrdinal: Int? = null, block: () -> Unit) {
    System.setProperty(CrashHook.ENV_POINT, point)
    if (callOrdinal != null) System.setProperty(CrashHook.ENV_CALL, callOrdinal.toString())
    try {
        block()
    } finally {
        System.clearProperty(CrashHook.ENV_POINT)
        if (callOrdinal != null) System.clearProperty(CrashHook.ENV_CALL)
    }
}

fun crashAt(point: String, callOrdinal: Int? = null, block: () -> Unit) {
    withCrashPoint(point, callOrdinal) { runCatching(block) }
}
