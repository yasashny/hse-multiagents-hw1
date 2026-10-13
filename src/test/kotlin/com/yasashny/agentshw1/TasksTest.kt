package com.yasashny.agentshw1

import com.yasashny.agentshw1.llm.models.MessageRole
import com.yasashny.agentshw1.llm.models.ModelReply
import com.yasashny.agentshw1.loop.CaptureUi
import com.yasashny.agentshw1.state.models.RunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import com.yasashny.agentshw1.loop.ResolutionAction
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.RunMode
import com.yasashny.agentshw1.state.models.RunPhase
import com.yasashny.agentshw1.state.models.TaskStatus

class TasksTest {

    private fun planReply(): ModelReply = planReplyOf(
        "p1",
        "Fix the order total so tests pass",
        listOf(
            planStep("s1", "Inspect the project", "read_file"),
            planStep("s2", "Fix calculate_total", "write_file", listOf("s1")),
            planStep("s3", "Run the tests", "bash", listOf("s2")),
        ),
        "tests must stay unchanged",
        "python3 is available",
        "unittest exits 0",
    )

    @Test
    fun `tasks run in dependency order and complete only with runtime-verified evidence`() {
        val env = TestEnv.create()
        val ui = CaptureUi()
        val llm = FakeLlm(
            toolReply(call("r0", "read_file", varargJson("path" to "order.py"))),
            planReply(),
            toolReply(call("r1", "read_file", varargJson("path" to "test_order.py"))),
            complete("c1", "s1", "r1"),
            toolReply(call("w2", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            complete("c2", "s2", "w2"),
            toolReply(call("b3", "bash", varargJson("command" to "python3 -m unittest -v", "cwd" to "."))),
            complete("c3", "s3", "b3"),
            textReply("All tasks done: calculation fixed and verified by tests."),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, withPlan = true, ui = ui)
        rt.runLoop()
        assertEquals(RunStatus.AWAITING_PLAN_APPROVAL, env.state(rt.state.runId).status)
        rt.approvePlan()
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.FINISHED, state.status)
        assertEquals(FIXED_ORDER, env.wsFile("order.py").readText())

        val r0 = state.call("r0")
        assertNull(r0.taskId)
        assertEquals(RunPhase.PLANNING, r0.phase)
        assertEquals("s1", state.call("r1").taskId)
        assertEquals("s2", state.call("w2").taskId)
        assertEquals("s3", state.call("b3").taskId)

        assertEquals(listOf("s1", "s2", "s3"), state.tasks.map { it.id })
        assertTrue(state.tasks.all { it.status == TaskStatus.DONE })
        assertEquals("r1", state.tasks[0].evidenceCallId)
        assertEquals("b3", state.tasks[2].evidenceCallId)

        assertTrue(ui.lines.joinToString("\n").contains("[x] s3: Run the tests"))
    }

    @Test
    fun `completion without valid evidence is rejected`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            planReply(),
            toolReply(call("r1", "read_file", varargJson("path" to "order.py"))),
            complete("bad1", "s1", "no_such_call"),
            complete("bad3", "s2", "r1"),
            complete("c1", "s1", "r1"),
            toolReply(call("w2", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            complete("c2", "s2", "w2"),
            toolReply(call("b3", "bash", varargJson("command" to "python3 -m unittest -v", "cwd" to "."))),
            complete("c3", "s3", "b3"),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, withPlan = true)
        rt.runLoop()
        rt.approvePlan()
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.FINISHED, state.status)
        val bad1 = state.call("bad1")
        assertEquals(AttemptOutcome.ERROR, bad1.lastAttempt?.outcome)
        assertTrue(bad1.lastAttempt?.result.toString().contains("evidence call not found"))
        assertEquals(AttemptOutcome.ERROR, state.call("bad3").lastAttempt?.outcome)
        assertEquals(TaskStatus.DONE, state.tasks.first { it.id == "s1" }.status)
    }

    @Test
    fun `a task with unfinished dependencies is not started`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            planReply(),
            complete("c3", "s3", "r0"),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, withPlan = true)
        rt.runLoop()
        rt.approvePlan()
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals("s1", state.currentTaskId)
        assertEquals(AttemptOutcome.ERROR, state.call("c3").lastAttempt?.outcome)
        assertTrue(state.tasks.none { it.status == TaskStatus.DONE })
    }

    @Test
    fun `complete_task inside a batch keeps tool results contiguous in the message stream`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            planReply(),
            toolReply(call("r1", "read_file", varargJson("path" to "order.py"))),
            toolReply(
                call("c1", "complete_task", varargJson("task_id" to "s1", "evidence_call_id" to "r1")),
                call("r2", "read_file", varargJson("path" to "test_order.py")),
            ),
            toolReply(call("w2", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            complete("c2", "s2", "w2"),
            toolReply(call("b3", "bash", varargJson("command" to "python3 -m unittest -v", "cwd" to "."))),
            complete("c3", "s3", "b3"),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, withPlan = true)
        rt.runLoop()
        rt.approvePlan()
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.FINISHED, state.status)

        llm.requests.forEach { (messages, _) ->
            messages.forEachIndexed { i, m ->
                if (m.role == MessageRole.ASSISTANT && m.toolCalls.isNotEmpty()) {
                    val expected = m.toolCalls.map { it.id }.toSet()
                    val following = messages.drop(i + 1).take(m.toolCalls.size)
                    assertEquals(expected, following.mapNotNull { it.toolCallId }.toSet(), "at message $i")
                    assertTrue(following.all { it.role == MessageRole.TOOL }, "at message $i")
                }
            }
        }

        val currentTaskMsg = state.messages.indexOfFirst {
            it.role == MessageRole.USER && it.content?.contains("Current task: s2") == true
        }
        val r2Result = state.messages.indexOfFirst { it.toolCallId == "r2" }
        assertTrue(currentTaskMsg > r2Result, "next-task announcement must follow the answered batch")
        assertEquals("s1", state.call("r2").taskId)
        assertTrue(state.tasks.all { it.status == TaskStatus.DONE })
    }

    @Test
    fun `after resume completed tasks are not restarted`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            planReply(),
            toolReply(call("r1", "read_file", varargJson("path" to "order.py"))),
            complete("c1", "s1", "r1"),
            toolReply(call("w2", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            complete("c2", "s2", "w2"),
            toolReply(call("b3", "bash", varargJson("command" to "python3 -m unittest -v", "cwd" to "."))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, withPlan = true)
        rt.runLoop()
        rt.approvePlan()
        crashAt("after_debit_before_exec", 6) { rt.runLoop() }

        val state = env.state(rt.state.runId)
        assertEquals(TaskStatus.DONE, state.tasks.first { it.id == "s1" }.status)
        assertEquals(TaskStatus.DONE, state.tasks.first { it.id == "s2" }.status)
        assertEquals("s3", state.currentTaskId, "the interrupted task stays current")

        val llm2 = FakeLlm(
            toolReply(call("b4", "bash", varargJson("command" to "python3 -m unittest -v", "cwd" to "."))),
            complete("c3", "s3", "b4"),
            textReply("done"),
        )
        val rt2 = env.attach(rt.state.runId, llm2)
        rt2.resolveIndeterminate(mapOf("b3" to ResolutionAction.RETRY), null)
        rt2.runLoop()

        val after = env.state(rt.state.runId)
        assertEquals(RunStatus.FINISHED, after.status)
        assertTrue(after.tasks.all { it.status == TaskStatus.DONE })
        assertEquals(1, after.call("r1").attempts.size)
        assertEquals(1, after.call("w2").attempts.size)
    }
}
