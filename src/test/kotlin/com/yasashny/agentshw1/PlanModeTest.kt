package com.yasashny.agentshw1

import com.yasashny.agentshw1.core.sha256
import com.yasashny.agentshw1.llm.models.ModelReply
import com.yasashny.agentshw1.state.models.RunPhase
import com.yasashny.agentshw1.state.models.RunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.RunMode

class PlanModeTest {

    private fun planReply(): ModelReply = planReplyOf(
        "p1",
        "Fix the order total calculation so tests pass",
        listOf(
            planStep("s1", "Inspect order.py and test_order.py", "read_file"),
            planStep("s2", "Fix the discount calculation", "write_file", listOf("s1")),
            planStep("s3", "Run tests and verify", "bash", listOf("s2")),
        ),
        "tests must stay unchanged",
        "python3 is available",
        "python3 -m unittest -v exits with code 0",
    )

    @Test
    fun `planning phase allows only reading - write and bash rejected, zero side effects`() {
        val env = TestEnv.create()
        val before = sha256(env.wsFile("order.py").readBytes())
        val llm = FakeLlm(
            toolReply(call("r1", "list_files", varargJson("path" to "."))),
            toolReply(
                call("bad1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER)),
                call("bad2", "delete_file", varargJson("path" to "order.py")),
                call("bad3", "bash", varargJson("command" to "python3 --version", "cwd" to ".")),
            ),
            planReply(),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, withPlan = true)
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.AWAITING_PLAN_APPROVAL, state.status)
        assertEquals(AttemptOutcome.OK, state.call("r1").lastAttempt?.outcome)
        for (id in listOf("bad1", "bad2", "bad3")) {
            val c = state.call(id)
            assertEquals(AttemptOutcome.DENIED_POLICY, c.lastAttempt?.outcome, "$id must be rejected in planning phase")
            assertTrue(c.lastAttempt?.result.toString().contains("not available in phase PLANNING"))
        }

        assertEquals(before, sha256(env.wsFile("order.py").readBytes()))
        assertEquals(0, state.budget.sideEffectsSpent)
        assertTrue(state.journal.entries.isEmpty(), "journal must be empty before approval")
    }

    @Test
    fun `plan is stored as structured data and execution starts only after approval`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("r1", "read_file", varargJson("path" to "order.py"))),
            planReply(),
            toolReply(call("r2", "read_file", varargJson("path" to "test_order.py"))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, withPlan = true)
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.AWAITING_PLAN_APPROVAL, state.status)
        val plan = state.plan!!
        assertEquals("Fix the order total calculation so tests pass", plan.goal)
        assertEquals(3, plan.steps.size)
        assertEquals(listOf("s1"), plan.steps[1].dependsOn)
        assertEquals("python3 -m unittest -v exits with code 0", plan.doneCriterion)
        assertTrue(state.tasks.isEmpty(), "tasks are created only on approval")

        assertEquals(BROKEN_ORDER, env.wsFile("order.py").readText())

        rt.rejectPlan()
        assertEquals(RunStatus.PLAN_REJECTED, env.state(rt.state.runId).status)
    }

    @Test
    fun `invalid plans are rejected - missing required fields, duplicate ids, steps without tools`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("b1", "submit_plan", buildJsonObject {
                put("goal", "g")
                put("steps", buildJsonArray {
                    add(buildJsonObject { put("id", "s1"); put("description", "d") })
                })
            })),
            toolReply(call("b2", "submit_plan", buildJsonObject {
                put("goal", "g")
                put("steps", buildJsonArray {
                    add(buildJsonObject { put("id", "s1"); put("description", "d"); put("tools", buildJsonArray { add("read_file") }) })
                    add(buildJsonObject { put("id", "s1"); put("description", "d2"); put("tools", buildJsonArray { add("bash") }) })
                })
                put("risks", buildJsonArray { add("r") })
                put("assumptions", buildJsonArray { add("a") })
                put("done_criterion", "tests pass")
            })),
            toolReply(call("b3", "submit_plan", buildJsonObject {
                put("goal", "g")
                put("steps", buildJsonArray {
                    add(buildJsonObject { put("id", "s1"); put("description", "d"); put("dependsOn", buildJsonArray {}) })
                })
                put("risks", buildJsonArray { add("r") })
                put("assumptions", buildJsonArray { add("a") })
                put("done_criterion", "tests pass")
            })),
            planReply(),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, withPlan = true)
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.AWAITING_PLAN_APPROVAL, state.status, "the valid plan (4th attempt) must be accepted")
        val b1 = state.call("b1")
        assertEquals(AttemptOutcome.DENIED_POLICY, b1.lastAttempt?.outcome, "missing required fields are rejected by schema validation")
        val b2 = state.call("b2")
        val b2Result = b2.lastAttempt?.result.toString()
        assertEquals(AttemptOutcome.ERROR, b2.lastAttempt?.outcome)
        assertTrue(b2Result.contains("duplicate step ids"), b2Result)
        val b3 = state.call("b3")
        assertEquals(AttemptOutcome.ERROR, b3.lastAttempt?.outcome)
        assertTrue(b3.lastAttempt?.result.toString().contains("tools[] is required"))
    }

    @Test
    fun `approve plan creates tasks and approval does not pre-approve dangerous actions`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            planReply(),
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
        )
        val rt = env.newRun(llm, mode = RunMode.SAFE, withPlan = true)
        rt.runLoop()
        assertEquals(RunStatus.AWAITING_PLAN_APPROVAL, env.state(rt.state.runId).status)

        rt.approvePlan()
        val state = env.state(rt.state.runId)
        assertEquals(RunPhase.EXECUTION, state.phase)
        assertEquals(RunStatus.RUNNING, state.status)
        assertEquals(3, state.tasks.size)
        assertEquals("s1", state.currentTaskId)

        rt.runLoop()
        val after = env.state(rt.state.runId)
        assertEquals(RunStatus.AWAITING_CONFIRMATION, after.status, "safe mode still requires confirmation after plan approval")
        assertEquals(BROKEN_ORDER, env.wsFile("order.py").readText())
    }

    @Test
    fun `submit_plan result is debited to the output budget like any other tool result`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("r1", "list_files", varargJson("path" to "."))),
            planReply(),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, withPlan = true)
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.AWAITING_PLAN_APPROVAL, state.status)
        assertTrue(state.budget.outputBytesSpent > 0, "submit_plan output must count toward the output budget")
    }
}
