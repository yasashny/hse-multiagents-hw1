package com.yasashny.agentshw1

import com.yasashny.agentshw1.core.CrashHook
import com.yasashny.agentshw1.state.models.RunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import com.yasashny.agentshw1.loop.ResolutionAction
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.CallStatus
import com.yasashny.agentshw1.state.models.RunMode
import com.yasashny.agentshw1.llm.models.MessageRole

class RecoveryTest {

    @Test
    fun `crash between execution and save leaves an indeterminate call that blocks resume until resolved`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        val runId = rt.state.runId

        val crash = runCatching {
            withCrashPoint("after_exec_before_save", 1) { rt.runLoop() }
        }.exceptionOrNull()
        assertTrue(crash is CrashHook.HardCrash, "expected the simulated crash, got $crash")

        assertEquals(FIXED_ORDER, env.wsFile("order.py").readText())
        val state = env.state(runId)
        val w1 = state.call("w1")
        assertEquals(CallStatus.INDETERMINATE, w1.status)
        assertEquals(AttemptOutcome.RUNNING, w1.lastAttempt?.outcome)
        assertEquals(1, state.budget.sideEffectsSpent)
        assertEquals(1, state.budget.toolCallsSpent)

        val llm2 = FakeLlm(textReply("done"))
        val rt2 = env.attach(runId, llm2)
        rt2.runLoop()
        val still = env.state(runId)
        assertEquals(RunStatus.RUNNING, still.status, "run must stop and wait for a user decision")
        assertEquals(1, still.calls.size)
        assertEquals(1, still.calls[0].attempts.size)
    }

    @Test
    fun `resolve as fail - the model sees the failure and continues`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        val runId = rt.state.runId
        crashAt("after_exec_before_save", 1) { rt.runLoop() }

        val llm2 = FakeLlm(
            toolReply(call("w2", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            textReply("done"),
        )
        val rt2 = env.attach(runId, llm2)
        rt2.resolveIndeterminate(mapOf("w1" to ResolutionAction.FAIL), true)
        rt2.runLoop()

        val state = env.state(runId)
        assertEquals(RunStatus.FINISHED, state.status)
        val w1 = state.call("w1")
        assertEquals(AttemptOutcome.FAILED_BY_USER, w1.lastAttempt?.outcome)
        assertTrue(w1.lastAttempt?.result.toString().contains("marked failed by the user"))
        assertEquals(FIXED_ORDER, env.wsFile("order.py").readText())
    }

    @Test
    fun `resolve as retry - an explicit new attempt of the same tool call`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        val runId = rt.state.runId
        crashAt("after_debit_before_exec", 1) { rt.runLoop() }
        assertEquals(BROKEN_ORDER, env.wsFile("order.py").readText())
        val state = env.state(runId)
        assertEquals(CallStatus.INDETERMINATE, state.call("w1").status)
        assertEquals(1, state.budget.sideEffectsSpent)

        val rt2 = env.attach(runId, FakeLlm(textReply("done")))
        rt2.resolveIndeterminate(mapOf("w1" to ResolutionAction.RETRY), null)
        rt2.runLoop()

        val after = env.state(runId)
        assertEquals(RunStatus.FINISHED, after.status)
        val w1 = after.call("w1")
        assertEquals(2, w1.attempts.size)
        assertEquals(AttemptOutcome.RESOLVED_RETRY, w1.attempts[0].outcome)
        assertEquals(AttemptOutcome.OK, w1.attempts[1].outcome)
        assertEquals(FIXED_ORDER, env.wsFile("order.py").readText())
        assertEquals(2, after.budget.sideEffectsSpent)
        assertEquals(2, after.budget.toolCallsSpent)
    }

    @Test
    fun `resolve as abort - the run terminates`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        val runId = rt.state.runId
        crashAt("after_exec_before_save", 1) { rt.runLoop() }

        val rt2 = env.attach(runId)
        rt2.resolveIndeterminate(mapOf("w1" to ResolutionAction.ABORT), true)
        assertEquals(RunStatus.ABORTED_BY_USER, env.state(runId).status)
    }

    @Test
    fun `plain repeated tool_call_id replays the stored result and never re-modifies files`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "scratch.py", "content" to FIXED_ORDER))),
            toolReply(call("w1", "write_file", varargJson("path" to "scratch.py", "content" to FIXED_ORDER))),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val state = env.state(rt.state.runId)

        assertEquals(RunStatus.FINISHED, state.status)
        assertEquals(1, state.calls.size)
        assertEquals(1, state.calls[0].attempts.size)
        assertEquals(FIXED_ORDER, env.wsFile("scratch.py").readText())
    }

    @Test
    fun `repeated tool_call_id with different arguments is a protocol error`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "a.py", "content" to "1"))),
            toolReply(call("w1", "write_file", varargJson("path" to "b.py", "content" to "2"))),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val state = env.state(rt.state.runId)

        assertEquals(RunStatus.FINISHED, state.status)
        val attempts = state.call("w1").attempts
        assertEquals(2, attempts.size)
        assertEquals(AttemptOutcome.PROTOCOL_ERROR, attempts[1].outcome)
        assertTrue(attempts[1].result.toString().contains("different name/arguments"))
        assertFalse(env.wsFile("b.py").exists())
    }

    @Test
    fun `every duplicate tool_call_id inside one batch gets its own tool response`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(
                call("w1", "write_file", varargJson("path" to "a.txt", "content" to "x")),
                call("w1", "write_file", varargJson("path" to "a.txt", "content" to "x")),
            ),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val state = env.state(rt.state.runId)

        assertEquals(RunStatus.FINISHED, state.status)
        val answers = state.messages.filter { it.role == MessageRole.TOOL && it.toolCallId == "w1" }
        assertEquals(2, answers.size)
        assertEquals(1, state.call("w1").attempts.size)
        assertEquals("x", env.wsFile("a.txt").readText())
    }

    @Test
    fun `plain repeat after a protocol error replays the original successful result`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "a.py", "content" to "1"))),
            toolReply(call("w1", "write_file", varargJson("path" to "b.py", "content" to "2"))),
            toolReply(call("w1", "write_file", varargJson("path" to "a.py", "content" to "1"))),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val state = env.state(rt.state.runId)

        assertEquals(RunStatus.FINISHED, state.status)
        val w1 = state.call("w1")
        assertEquals(listOf(AttemptOutcome.OK, AttemptOutcome.PROTOCOL_ERROR), w1.attempts.map { it.outcome })
        val replay = state.messages.last { it.role == MessageRole.TOOL && it.toolCallId == "w1" }
        assertTrue(replay.content!!.contains("\"ok\":true"), replay.content)
        assertFalse(env.wsFile("b.py").exists())
    }

    @Test
    fun `full resume after crash continues the conversation without repeating completed calls`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("r1", "read_file", varargJson("path" to "order.py"))),
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        val runId = rt.state.runId
        crashAt("after_exec_before_save", 2) { rt.runLoop() }

        val llm2 = FakeLlm(
            toolReply(call("b1", "bash", varargJson("command" to "python3 -m unittest -v", "cwd" to "."))),
            textReply("done"),
        )
        val rt2 = env.attach(runId, llm2)
        rt2.resolveIndeterminate(mapOf("w1" to ResolutionAction.FAIL), true)
        rt2.runLoop()

        val state = env.state(runId)
        assertEquals(RunStatus.FINISHED, state.status)
        val lastRequest = llm2.requests.last().first
        assertTrue(lastRequest.any { it.role == MessageRole.TOOL && it.toolCallId == "r1" })
        assertEquals(3, state.calls.size)
    }

    @Test
    fun `resume revives a run failed by a model error instead of being a silent no-op`() {
        val env = TestEnv.create()
        val rt = env.newRun(FakeLlm(), mode = RunMode.AUTO)
        val runId = rt.state.runId
        rt.runLoop()

        assertEquals(RunStatus.FAILED, env.state(runId).status)
        assertEquals(1, env.state(runId).budget.modelRequestsSpent)

        val rt2 = env.attach(runId, FakeLlm(textReply("done")))
        assertTrue(rt2.prepareResume(), "an explicit resume must revive a FAILED run")
        rt2.runLoop()

        val state = env.state(runId)
        assertEquals(RunStatus.FINISHED, state.status)
        assertEquals("done", state.finalAnswer)
        assertEquals(2, state.budget.modelRequestsSpent, "resume must not reset budget counters")
    }

    @Test
    fun `prepareResume reports terminal statuses and does not revive them`() {
        val env = TestEnv.create()
        val rt = env.newRun(FakeLlm(textReply("done")), mode = RunMode.AUTO)
        val runId = rt.state.runId
        rt.runLoop()
        assertEquals(RunStatus.FINISHED, env.state(runId).status)

        val rt2 = env.attach(runId)
        assertFalse(rt2.prepareResume())
        assertEquals(RunStatus.FINISHED, env.state(runId).status)
    }
}
