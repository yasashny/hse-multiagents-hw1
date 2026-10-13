package com.yasashny.agentshw1

import com.yasashny.agentshw1.core.sha256
import com.yasashny.agentshw1.journal.JournalEngine
import com.yasashny.agentshw1.state.models.RunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.yasashny.agentshw1.state.models.RunMode
import com.yasashny.agentshw1.state.models.TaskStatus
import kotlinx.serialization.json.jsonPrimitive

class EndToEndTest {

    @Test
    fun `control task - plan, approve, fix, verify with tests, diff, rollback`() {
        val env = TestEnv.create()
        val testsHashBefore = sha256(env.wsFile("test_order.py"))

        val llm = FakeLlm(
            toolReply(
                call("p1", "list_files", varargJson("path" to ".")),
                call("p2", "read_file", varargJson("path" to "order.py")),
                call("p3", "read_file", varargJson("path" to "test_order.py")),
            ),
            planReplyOf(
                "p4",
                "Fix order total calculation so tests pass without touching tests",
                listOf(
                    planStep("s1", "Inspect sources and tests", "read_file"),
                    planStep("s2", "Fix calculate_total: percent discount + validation", "write_file", listOf("s1")),
                    planStep("s3", "Run python3 -m unittest -v and verify exit code 0", "bash", listOf("s2")),
                ),
                "tests must remain unchanged",
                "python3 available in PATH",
                "python3 -m unittest -v exits with code 0",
            ),
            toolReply(call("e1", "read_file", varargJson("path" to "order.py"))),
            complete("e2", "s1", "e1"),
            toolReply(call("e3", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            complete("e4", "s2", "e3"),
            toolReply(call("e5", "bash", varargJson("command" to "python3 -m unittest -v", "cwd" to "."))),
            complete("e6", "s3", "e5"),
            textReply("Fixed calculate_total: percent discount with 0..100 validation. Tests pass."),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO, withPlan = true)
        val runId = rt.state.runId

        rt.runLoop()
        assertEquals(RunStatus.AWAITING_PLAN_APPROVAL, env.state(runId).status)
        assertEquals(BROKEN_ORDER, env.wsFile("order.py").readText())

        rt.approvePlan()
        rt.runLoop()

        val state = env.state(runId)
        assertEquals(RunStatus.FINISHED, state.status)
        assertTrue(state.tasks.all { it.status == TaskStatus.DONE })

        val e5 = state.call("e5")
        assertEquals("0", e5.lastAttempt?.result?.get("exit_code")?.jsonPrimitive?.content)

        val probeExit = ProcessBuilder("python3", "-m", "unittest", "-v")
            .directory(env.workspace)
            .start()
            .waitFor()
        assertEquals(0, probeExit, "tests must pass in the workspace after the agent's fix")

        assertEquals(testsHashBefore, sha256(env.wsFile("test_order.py")))

        assertTrue(rt.renderJournalDiff().contains("MODIFIED by this run"))
        val rt2 = env.attach(runId)
        assertTrue(rt2.rollback() is JournalEngine.RollbackResult.Success)
        assertEquals(BROKEN_ORDER, env.wsFile("order.py").readText())
    }
}
