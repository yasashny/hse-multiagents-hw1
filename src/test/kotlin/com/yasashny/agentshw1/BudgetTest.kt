package com.yasashny.agentshw1

import com.yasashny.agentshw1.state.models.BudgetLimits
import com.yasashny.agentshw1.state.models.RunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import com.yasashny.agentshw1.loop.ResolutionAction
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.CallStatus
import com.yasashny.agentshw1.state.models.RunMode

class BudgetTest {

    @Test
    fun `model request limit stops the run before the request is made`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("r1", "read_file", varargJson("path" to "order.py"))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, limits = BudgetLimits(modelRequests = 1))
        rt.runLoop()
        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.BUDGET_EXCEEDED, state.status)
        assertTrue(state.failure!!.contains("model request budget exhausted"))
        assertEquals(1, state.budget.modelRequestsSpent)
        assertEquals(AttemptOutcome.OK, state.call("r1").lastAttempt?.outcome)
    }

    @Test
    fun `tool call limit is enforced per call`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("r1", "read_file", varargJson("path" to "order.py"))),
            toolReply(call("r2", "read_file", varargJson("path" to "test_order.py"))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, limits = BudgetLimits(modelRequests = 10, toolCalls = 1))
        rt.runLoop()
        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.BUDGET_EXCEEDED, state.status)
        assertTrue(state.failure!!.contains("tool call budget exhausted"))
        assertEquals(1, state.budget.toolCallsSpent)
    }

    @Test
    fun `side effect limit is enforced`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "a.txt", "content" to "1"))),
            toolReply(call("w2", "write_file", varargJson("path" to "b.txt", "content" to "2"))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, limits = BudgetLimits(modelRequests = 10, toolCalls = 10, sideEffects = 1))
        rt.runLoop()
        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.BUDGET_EXCEEDED, state.status)
        assertEquals(1, state.budget.sideEffectsSpent)
        assertTrue(env.wsFile("a.txt").exists())
        assertFalse(env.wsFile("b.txt").exists(), "the second side effect must not run")
    }

    @Test
    fun `batch that does not fit the budget is not executed at all`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(
                call("r1", "read_file", varargJson("path" to "order.py")),
                call("r2", "read_file", varargJson("path" to "test_order.py")),
                call("r3", "read_file", varargJson("path" to "order.py")),
            ),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, limits = BudgetLimits(modelRequests = 10, toolCalls = 2))
        rt.runLoop()
        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.BUDGET_EXCEEDED, state.status)
        assertTrue(state.failure!!.contains("NONE of the 3 batch calls was executed"))
        assertEquals(0, state.budget.toolCallsSpent, "no call of the batch was debited or executed")
        assertTrue(state.calls.isEmpty())
    }

    @Test
    fun `output limit truncates results and marks them incomplete`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("r1", "read_file", varargJson("path" to "order.py"))),
            toolReply(call("r2", "read_file", varargJson("path" to "test_order.py"))),
            textReply("done"),
        )
        val rt = env.newRun(
            llm, mode = RunMode.AUTO,
            limits = BudgetLimits(modelRequests = 10, toolCalls = 10, outputBytes = 300),
        )
        rt.runLoop()
        val state = env.state(rt.state.runId)

        assertEquals(RunStatus.FINISHED, state.status)
        val r2 = state.call("r2")
        assertEquals(AttemptOutcome.OK, r2.lastAttempt?.outcome)
        assertEquals(
            JsonPrimitive(true),
            r2.lastAttempt?.result?.get("truncated"),
            "the read that exceeded the remaining output budget must be truncated: ${r2.lastAttempt?.result}",
        )
        assertTrue(r2.lastAttempt?.result.toString().contains("incomplete"))
        assertEquals(300, state.budget.outputBytesSpent)
    }

    @Test
    fun `output cap is enforced in bytes for multi-byte content`() {
        val env = TestEnv.create(workspaceFiles = mapOf("big.txt" to "привет".repeat(200)))
        val llm = FakeLlm(
            toolReply(call("r1", "read_file", varargJson("path" to "big.txt"))),
            textReply("done"),
        )
        val rt = env.newRun(
            llm, mode = RunMode.AUTO,
            limits = BudgetLimits(modelRequests = 10, toolCalls = 10, outputBytes = 1000),
        )
        rt.runLoop()
        val state = env.state(rt.state.runId)

        assertEquals(RunStatus.FINISHED, state.status)
        val r1 = state.call("r1")
        assertEquals(JsonPrimitive(true), r1.lastAttempt?.result?.get("truncated"))
        val cappedBytes = r1.lastAttempt?.result.toString().toByteArray(Charsets.UTF_8).size
        assertTrue(cappedBytes <= 1000, "truncated result must fit the byte budget, was $cappedBytes")
        assertTrue(state.budget.outputBytesSpent in 800..1000, "debit must reflect the truncated size")
    }

    @Test
    fun `counters survive resume`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("r1", "read_file", varargJson("path" to "order.py"))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, limits = BudgetLimits(modelRequests = 10, toolCalls = 10))
        val runId = rt.state.runId
        crashAt("after_exec_before_save", 1) { rt.runLoop() }

        val llm2 = FakeLlm(textReply("done"))
        val rt2 = env.attach(runId, llm2)
        rt2.resolveIndeterminate(mapOf("r1" to ResolutionAction.FAIL), null)
        rt2.runLoop()

        val state = env.state(runId)
        assertEquals(RunStatus.FINISHED, state.status)
        assertEquals(2, state.budget.modelRequestsSpent)
        assertEquals(1, state.budget.toolCallsSpent)
    }

    @Test
    fun `crash between debit and save keeps the budget spent`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        val runId = rt.state.runId
        crashAt("after_debit_before_exec", 1) { rt.runLoop() }

        val state = env.state(runId)
        assertEquals(1, state.budget.sideEffectsSpent)
        assertEquals(1, state.budget.toolCallsSpent)
        assertEquals(CallStatus.INDETERMINATE, state.call("w1").status)
    }

    @Test
    fun `explicit retry is refused when the budget is exhausted - the action must not start`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
        )
        val rt = env.newRun(
            llm, mode = RunMode.AUTO,
            limits = BudgetLimits(modelRequests = 10, toolCalls = 1, sideEffects = 1),
        )
        val runId = rt.state.runId
        crashAt("after_debit_before_exec", 1) { rt.runLoop() }
        assertEquals(BROKEN_ORDER, env.wsFile("order.py").readText())

        val rt2 = env.attach(runId, FakeLlm(textReply("done")))
        rt2.resolveIndeterminate(mapOf("w1" to ResolutionAction.RETRY), null)
        rt2.runLoop()

        val state = env.state(runId)
        assertEquals(RunStatus.BUDGET_EXCEEDED, state.status)
        assertTrue(state.failure!!.contains("cannot start"))
        assertEquals(1, state.budget.toolCallsSpent)
        assertEquals(1, state.budget.sideEffectsSpent)
        assertEquals(BROKEN_ORDER, env.wsFile("order.py").readText())
    }

    @Test
    fun `list_files result is truncated to the remaining output budget and marked incomplete`() {
        val env = TestEnv.create(
            workspaceFiles = (0 until 60).associate { i -> "file_$i.txt" to "x" } +
                mapOf("order.py" to BROKEN_ORDER, "test_order.py" to TEST_ORDER),
        )
        val llm = FakeLlm(
            toolReply(call("l1", "list_files", varargJson("path" to "."))),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, limits = BudgetLimits(modelRequests = 10, outputBytes = 400))
        rt.runLoop()
        val state = env.state(rt.state.runId)

        val l1 = state.call("l1")
        assertEquals(AttemptOutcome.OK, l1.lastAttempt?.outcome)
        val result = l1.lastAttempt?.result.toString()
        assertEquals(JsonPrimitive(true), l1.lastAttempt?.result?.get("truncated"), "list_files must be truncated to the budget: $result")
        assertTrue(result.contains("incomplete"))
        assertTrue(state.budget.outputBytesSpent <= 400)
    }
}
