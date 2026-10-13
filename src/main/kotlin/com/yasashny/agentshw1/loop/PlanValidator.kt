package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.state.models.Plan
import com.yasashny.agentshw1.state.models.PlanStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

object PlanValidator {

    fun parse(args: JsonObject): Plan? = try {
        val goal = args["goal"]?.jsonPrimitive?.content ?: return null
        val steps = (args["steps"] as? JsonArray)?.mapNotNull { element ->
            parseStep(element as? JsonObject ?: return@mapNotNull null) ?: return null
        }.orEmpty()
        if (steps.isEmpty()) return null
        Plan(
            goal = goal,
            steps = steps,
            risks = args.stringList("risks"),
            assumptions = args.stringList("assumptions"),
            doneCriterion = args["done_criterion"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    } catch (_: Exception) {
        null
    }

    fun validate(plan: Plan): String? = validateStructure(plan) ?: validateDependencies(plan)

    private fun parseStep(step: JsonObject): PlanStep? {
        val id = step["id"]?.jsonPrimitive?.content ?: return null
        val description = step["description"]?.jsonPrimitive?.content ?: return null
        return PlanStep(
            id = id,
            description = description,
            tools = step.stringList("tools"),
            dependsOn = step.stringList("dependsOn"),
        )
    }

    private fun JsonObject.stringList(key: String): List<String> =
        this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun validateStructure(plan: Plan): String? {
        if (plan.doneCriterion.isBlank()) return "done_criterion (readiness criterion) is required"
        val step = plan.steps.firstOrNull { it.tools.isEmpty() } ?: return null
        return "step ${step.id} must list the tools it needs (tools[] is required)"
    }

    private fun validateDependencies(plan: Plan): String? {
        val ids = plan.steps.map { it.id }.toSet()
        val duplicates = plan.steps.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) return "duplicate step ids: $duplicates"
        for (step in plan.steps) {
            val unknown = step.dependsOn.filter { it !in ids }
            if (unknown.isNotEmpty()) return "step ${step.id} depends on unknown steps: $unknown"
        }
        return if (hasCycle(plan)) "dependency cycle among steps" else null
    }

    private fun hasCycle(plan: Plan): Boolean {
        val ids = plan.steps.map { it.id }.toSet()
        val remaining = plan.steps.associate { step ->
            step.id to step.dependsOn.filter { it in ids }.toMutableSet()
        }
        val queue = ArrayDeque(remaining.filterValues { it.isEmpty() }.keys)
        var ordered = 0
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            ordered++
            for ((stepId, deps) in remaining) {
                if (deps.remove(id) && deps.isEmpty()) queue.add(stepId)
            }
        }
        return ordered != ids.size
    }
}
