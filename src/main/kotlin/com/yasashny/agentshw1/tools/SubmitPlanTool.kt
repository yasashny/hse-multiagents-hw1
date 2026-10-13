package com.yasashny.agentshw1.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class SubmitPlanTool : Tool {

    override val id = ToolName.SUBMIT_PLAN
    override val description =
        "Submit the structured work plan (planning phase only). Ends planning; the user then approves or rejects the plan."
    override val sideEffect = false
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonArray("required") {
            add("goal")
            add("steps")
            add("risks")
            add("assumptions")
            add("done_criterion")
        }
        putJsonObject("properties") {
            put("goal", stringParameter())
            putJsonObject("steps") {
                put("type", "array")
                put("description", "Ordered steps; each item: {id, description, tools[], dependsOn[]}")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonArray("required") {
                        add("id")
                        add("description")
                        add("tools")
                    }
                    putJsonObject("properties") {
                        put("id", stringParameter())
                        put("description", stringParameter())
                        put("tools", stringArraySchema())
                        put("dependsOn", stringArraySchema())
                    }
                }
            }
            put("risks", stringArraySchema())
            put("assumptions", stringArraySchema())
            put("done_criterion", stringParameter())
        }
    }

    override fun execute(args: JsonObject, ctx: ToolContext): JsonObject =
        ToolJson.error("submit_plan is a control tool and must be handled by the runtime", ErrorKind.PROTOCOL)

    private fun stringArraySchema() = buildJsonObject {
        put("type", "array")
        put("items", stringParameter())
    }
}
