package com.yasashny.agentshw1

import com.yasashny.agentshw1.core.sha256
import com.yasashny.agentshw1.loop.CaptureUi
import com.yasashny.agentshw1.state.models.RunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.RunMode
import com.yasashny.agentshw1.llm.models.MessageRole
import com.yasashny.agentshw1.state.models.TaskStatus

class AgentLoopTest {

    @Test
    fun `multi tool calls, tool error recovery, final answer, tests verified via bash`() {
        val env = TestEnv.create()
        val ui = CaptureUi()
        val llm = FakeLlm(
            toolReply(
                call("c1", "list_files", varargJson("path" to ".")),
                call("c2", "read_file", varargJson("path" to "order.py")),
            ),
            toolReply(call("c3", "read_file", varargJson("path" to "nope.py"))),
            toolReply(
                call("c4", "read_file", varargJson("path" to "test_order.py")),
                call("c5", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER)),
            ),
            toolReply(call("c6", "bash", varargJson("command" to "python3 -m unittest -v", "cwd" to "."))),
            textReply("Fixed: discount is now applied as a percentage with validation. Tests pass."),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, ui = ui)
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.FINISHED, state.status)
        assertEquals(6, state.calls.size)

        val c3 = state.call("c3")
        assertEquals(AttemptOutcome.ERROR, c3.lastAttempt?.outcome)
        assertEquals(false, c3.lastAttempt?.result?.get("ok")?.jsonPrimitive?.boolean)

        val c6 = state.call("c6")
        assertEquals("bash", c6.name)
        assertEquals(AttemptOutcome.OK, c6.lastAttempt?.outcome)
        assertEquals("0", c6.lastAttempt?.result?.get("exit_code")?.jsonPrimitive?.content)

        val toolMessages = state.messages.filter { it.role == MessageRole.TOOL }
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5", "c6"), toolMessages.map { it.toolCallId })

        assertEquals(FIXED_ORDER, env.wsFile("order.py").readText())
        assertEquals(sha256(TEST_ORDER.toByteArray()), sha256(env.wsFile("test_order.py")))
        assertTrue(ui.lines.any { it.contains("python3 -m unittest -v") })
    }

    @Test
    fun `tools are announced with json schemas and validated before execution`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("c1", "write_file", varargJson("path" to "order.py"))),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.FINISHED, state.status)
        assertEquals(AttemptOutcome.DENIED_POLICY, state.call("c1").lastAttempt?.outcome)
        assertEquals(BROKEN_ORDER, env.wsFile("order.py").readText())

        val (messages, tools) = llm.requests.first()
        assertEquals(MessageRole.SYSTEM, messages.first().role)
        assertTrue(tools.any { it.name == "bash" && it.parameters.containsKey("properties") })
        assertTrue(tools.any { it.name == "read_file" && it.parameters.containsKey("required") })
    }

    @Test
    fun `unknown tool is rejected and reported to the model`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("c1", "google_search", varargJson("q" to "how to fix"))),
            textReply("ok, I will use the available tools"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.FINISHED, state.status)
        assertEquals(AttemptOutcome.DENIED_POLICY, state.call("c1").lastAttempt?.outcome)
    }

    @Test
    fun `empty model reply is nudged and the run continues instead of finishing silently`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            textReply(""),
            toolReply(call("c1", "read_file", varargJson("path" to "order.py"))),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.FINISHED, state.status)
        assertEquals("done", state.finalAnswer)
        assertEquals(0, state.nudges)
        val nudge = state.messages.first { it.role == MessageRole.USER && it.content.orEmpty().contains("empty") }
        assertTrue(nudge.content.orEmpty().contains("neither text nor tool calls"))
    }

    @Test
    fun `repeated empty replies eventually fail the run instead of reporting success`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            textReply(""),
            textReply(""),
            textReply(""),
            textReply(""),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.FAILED, state.status)
        assertTrue(state.failure.orEmpty().contains("empty replies"))
    }

    @Test
    fun `tool call with broken json arguments is rejected and the model can retry`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("c1", "read_file", varargJson("_invalid_arguments" to "read_file(path='order."))),
            toolReply(call("c2", "read_file", varargJson("path" to "order.py"))),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.FINISHED, state.status)
        assertEquals(AttemptOutcome.DENIED_POLICY, state.call("c1").lastAttempt?.outcome)
        assertTrue(state.call("c1").lastAttempt?.result.toString().contains("not a valid JSON object"))
        assertEquals(AttemptOutcome.OK, state.call("c2").lastAttempt?.outcome)
    }

    @Test
    fun `nudge limits count consecutive unproductive replies, not a lifetime total`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            planReplyOf(
                "p1", "fix the tests",
                listOf(
                    planStep("s1", "Inspect the project", "read_file"),
                    planStep("s2", "Fix order.py", "write_file", listOf("s1")),
                    planStep("s3", "Run the tests", "bash", listOf("s2")),
                ),
                "tests may change", "python3 available", "unittest exits 0",
            ),
            textReply("premature final 1"),
            toolReply(call("r1", "read_file", varargJson("path" to "order.py"))),
            textReply("premature final 2"),
            toolReply(call("r1b", "read_file", varargJson("path" to "test_order.py"))),
            textReply("premature final 3"),
            toolReply(call("r1c", "list_files", varargJson("path" to "."))),
            textReply("premature final 4"),
            complete("c1", "s1", "r1"),
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            complete("c2", "s2", "w1"),
            toolReply(call("r3", "bash", varargJson("command" to "python3 -m unittest -v", "cwd" to "."))),
            complete("c3", "s3", "r3"),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, withPlan = true)
        rt.runLoop()
        rt.approvePlan()
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.FINISHED, state.status)
        assertEquals("done", state.finalAnswer)
        assertEquals(0, state.nudges)
        assertTrue(state.tasks.all { it.status == TaskStatus.DONE })
    }
}
