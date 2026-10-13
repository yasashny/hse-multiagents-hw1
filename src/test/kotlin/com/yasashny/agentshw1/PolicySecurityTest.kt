package com.yasashny.agentshw1

import com.yasashny.agentshw1.exec.BashExecutor
import com.yasashny.agentshw1.policy.CommandChecker
import com.yasashny.agentshw1.policy.Decision
import com.yasashny.agentshw1.policy.PathChecker
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.BudgetLimits
import com.yasashny.agentshw1.state.models.RunMode
import com.yasashny.agentshw1.state.models.RunStatus
import com.yasashny.agentshw1.state.models.RunState
import com.yasashny.agentshw1.tools.ReadFileTool
import com.yasashny.agentshw1.tools.ToolContext
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicySecurityTest {

    private fun lastAttemptOf(state: RunState, callId: String) =
        state.call(callId).lastAttempt

    @Test
    fun `allowed command executes, denied command rejected, unknown command rejected by default`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(
                call("ok1", "bash", varargJson("command" to "python3 --version", "cwd" to ".")),
                call("bad1", "bash", varargJson("command" to "curl http://evil.example", "cwd" to ".")),
                call("bad2", "bash", varargJson("command" to "python3 -m unittest -v --evil", "cwd" to ".")),
            ),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val state = env.state(rt.state.runId)

        assertEquals(AttemptOutcome.OK, lastAttemptOf(state, "ok1")?.outcome)
        assertEquals(AttemptOutcome.DENIED_POLICY, lastAttemptOf(state, "bad1")?.outcome)
        assertTrue(lastAttemptOf(state, "bad1")?.result.toString().contains("denied by policy"))
        assertEquals(AttemptOutcome.DENIED_POLICY, lastAttemptOf(state, "bad2")?.outcome)
    }

    @Test
    fun `shell syntax is rejected - pipes, redirects, substitutions, semicolons`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(
                call("p1", "bash", varargJson("command" to "python3 --version | cat", "cwd" to ".")),
                call("p2", "bash", varargJson("command" to "python3 --version > out.txt", "cwd" to ".")),
                call("p3", "bash", varargJson("command" to "python3 -m unittest -v; ls", "cwd" to ".")),
                call("p4", "bash", varargJson("command" to "bash -c ls", "cwd" to ".")),
                call("p5", "bash", varargJson("command" to "echo \$(whoami)", "cwd" to ".")),
            ),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val state = env.state(rt.state.runId)
        for (id in listOf("p1", "p2", "p3", "p4", "p5")) {
            assertEquals(AttemptOutcome.DENIED_POLICY, lastAttemptOf(state, id)?.outcome, "call $id must be denied")
        }
    }

    @Test
    fun `deny wins over allow`() {
        val policy = """
            {
              "commands": {
                "allow": ["ls", "python3 -m unittest -v"],
                "deny": ["ls"]
              },
              "directories": { "allow": ["workspace"], "deny": [] },
              "journal": { "ignore": ["__pycache__/", "*.pyc"] }
            }
        """.trimIndent()
        val env = TestEnv.create(policyJson = policy)
        val p = env.policy()
        assertTrue(CommandChecker.check(p, "ls") is Decision.Deny)
        assertEquals(Decision.Allow, CommandChecker.check(p, "python3 -m unittest -v"))
    }

    @Test
    fun `allowed command in a denied directory is rejected`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("c1", "bash", varargJson("command" to "python3 --version", "cwd" to "secret_notes"))),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val state = env.state(rt.state.runId)
        assertEquals(AttemptOutcome.DENIED_POLICY, lastAttemptOf(state, "c1")?.outcome)
        assertTrue(lastAttemptOf(state, "c1")?.result.toString().contains("denied directory"))
    }

    @Test
    fun `file tool denied in denied subdirectory, dotdot escape, absolute escape, symlink escape`() {
        val env = TestEnv.create()
        Files.createSymbolicLink(
            env.wsFile("outside_link").toPath(),
            env.root.toPath().parent.toAbsolutePath(),
        )

        val llm = FakeLlm(
            toolReply(
                call("d1", "read_file", varargJson("path" to "secret_notes/notes.txt")),
                call("d2", "read_file", varargJson("path" to "../policy.json")),
                call("d3", "read_file", varargJson("path" to env.policyFile.absolutePath)),
                call("d4", "read_file", varargJson("path" to "outside_link/policy.json")),
                call("d5", "write_file", varargJson("path" to "outside_link/pwned.py", "content" to "x")),
            ),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val state = env.state(rt.state.runId)
        for (id in listOf("d1", "d2", "d3", "d4", "d5")) {
            assertEquals(AttemptOutcome.DENIED_POLICY, lastAttemptOf(state, id)?.outcome, "$id must be denied")
        }
        assertTrue(!File(env.root, "pwned.py").exists())
    }

    @Test
    fun `policy file itself is protected from agent writes`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "../policy.json", "content" to "{}"))),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val state = env.state(rt.state.runId)
        assertEquals(AttemptOutcome.DENIED_POLICY, lastAttemptOf(state, "w1")?.outcome)
        assertEquals(DEFAULT_TEST_POLICY.trim(), env.policyFile.readText().trim())
    }

    @Test
    fun `safe mode requires confirmation for write and bash, auto mode does not`() {
        val envSafe = TestEnv.create()
        val llmSafe = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            textReply("done"),
        )
        val rtSafe = envSafe.newRun(llmSafe, mode = RunMode.SAFE)
        rtSafe.runLoop()
        var state = envSafe.state(rtSafe.state.runId)
        assertEquals(RunStatus.AWAITING_CONFIRMATION, state.status)
        assertEquals("write_file", state.pendingConfirmation?.name)
        assertEquals(BROKEN_ORDER, envSafe.wsFile("order.py").readText())

        rtSafe.setConfirmationDecision(rtSafe.state.runId, state.pendingConfirmation!!.callId, true)
        rtSafe.runLoop()
        state = envSafe.state(rtSafe.state.runId)
        assertEquals(RunStatus.FINISHED, state.status)
        assertEquals(FIXED_ORDER, envSafe.wsFile("order.py").readText())

        val envAuto = TestEnv.create()
        val llmAuto = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            textReply("done"),
        )
        val rtAuto = envAuto.newRun(llmAuto, mode = RunMode.AUTO)
        rtAuto.runLoop()
        assertEquals(RunStatus.FINISHED, envAuto.state(rtAuto.state.runId).status)
        assertEquals(FIXED_ORDER, envAuto.wsFile("order.py").readText())
    }

    @Test
    fun `deny of a confirmation gives the model a denial result and it can continue`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            toolReply(call("r1", "read_file", varargJson("path" to "order.py"))),
            textReply("I was not allowed to write; reporting that."),
        )
        val rt = env.newRun(llm, mode = RunMode.SAFE)
        rt.runLoop()
        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.AWAITING_CONFIRMATION, state.status)

        rt.setConfirmationDecision(rt.state.runId, state.pendingConfirmation!!.callId, false)
        rt.runLoop()

        val after = env.state(rt.state.runId)
        assertEquals(RunStatus.FINISHED, after.status)
        assertEquals(AttemptOutcome.USER_DENIED, lastAttemptOf(after, "w1")?.outcome)
        assertTrue(lastAttemptOf(after, "w1")?.result.toString().contains("denied by the user"))
        assertEquals(BROKEN_ORDER, env.wsFile("order.py").readText())
    }

    @Test
    fun `delete always requires confirmation even in auto mode`() {
        val env = TestEnv.create()
        env.wsFile("trash.txt").writeText("bye")
        val llm = FakeLlm(
            toolReply(call("del", "delete_file", varargJson("path" to "trash.txt"))),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.AWAITING_CONFIRMATION, state.status)
        assertEquals("delete_file", state.pendingConfirmation?.name)
        assertTrue(env.wsFile("trash.txt").exists())
    }

    @Test
    fun `bash runs with a timeout and output cap`() {
        val env = TestEnv.create(
            policyJson = DEFAULT_TEST_POLICY.replace("\"ls\",", "\"ls\",\n      \"yes h\","),
        )
        val llm = FakeLlm(
            toolReply(call("y1", "bash", varargJson("command" to "yes h", "cwd" to "."))),
            textReply("done"),
        )
        val rt = env.newRun(
            llm,
            task = "test",
            limits = BudgetLimits(modelRequests = 10, outputBytes = 2048),
            bashExecutor = BashExecutor(timeoutSeconds = 2),
        )
        rt.runLoop()
        val state = env.state(rt.state.runId)
        val result = lastAttemptOf(state, "y1")?.result.toString()
        assertEquals(AttemptOutcome.ERROR, lastAttemptOf(state, "y1")?.outcome)
        assertTrue(result.contains("timed_out"), result)
        assertTrue(result.contains("truncated"))
        assertTrue(result.contains("incomplete"))
    }

    @Test
    fun `journal ignore patterns cannot hide control project sources and tests`() {
        val env = TestEnv.create()
        env.policy().validate(env.workspace, env.runsDir) { error("default policy must validate: $it") }

        val bad = TestEnv.create(
            policyJson = DEFAULT_TEST_POLICY.replace("\"ignore\": [\"__pycache__/\", \"*.pyc\"]", "\"ignore\": [\"*.py\"]"),
        )
        val failure = runCatching {
            bad.policy().validate(bad.workspace, bad.runsDir) { throw IllegalArgumentException(it) }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected a validation failure, got $failure")
        assertTrue(failure!!.message!!.contains("journal.ignore"), failure.message)
        assertTrue(failure.message!!.contains("order.py"), failure.message)

        val startFailure = runCatching {
            bad.newRun(FakeLlm(textReply("done")))
        }.exceptionOrNull()
        assertTrue(startFailure is IllegalArgumentException, "expected startRun to reject the policy, got $startFailure")
    }

    @Test
    fun `read_file truncation cuts on a utf-8 character boundary`() {
        val env = TestEnv.create(workspaceFiles = mapOf("ru.txt" to "привет мир, привет мир, привет мир"))
        val pathChecker = PathChecker(env.policy(), listOf(env.policyFile, env.runsDir))
        val tool = ReadFileTool()
        val result = tool.execute(
            varargJson("path" to "ru.txt"),
            ToolContext(env.workspace, pathChecker, BashExecutor(), outputCapBytes = 43),
        )
        assertEquals("true", (result["truncated"] as JsonPrimitive).content)
        val content = (result["content"] as JsonPrimitive).content
        assertTrue('\uFFFD' !in content, "truncated content must not contain replacement chars: $content")
        assertTrue(content.isNotEmpty())
    }
}
