package com.yasashny.agentshw1

import com.yasashny.agentshw1.core.sha256
import com.yasashny.agentshw1.core.unb64
import com.yasashny.agentshw1.journal.JournalEngine
import com.yasashny.agentshw1.state.models.RunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.yasashny.agentshw1.loop.ResolutionAction
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.RunMode

class JournalTest {

    @Test
    fun `modification is journaled with original and final state, diff is viewable, rollback restores after restart`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            textReply("fixed"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val runId = rt.state.runId
        val state = env.state(runId)
        assertEquals(RunStatus.FINISHED, state.status)
        val entry = state.journal.entries.first { it.path == "order.py" }
        assertTrue(entry.existedBefore)
        assertEquals(sha256(BROKEN_ORDER.toByteArray()), entry.originalHash)
        assertEquals(FIXED_ORDER, String(unb64(entry.finalContentB64!!)))

        val diff = rt.renderJournalDiff()
        assertTrue(diff.contains("order.py: MODIFIED by this run"))
        assertTrue(diff.contains("-    return sum(prices) - discount_percent"))
        assertTrue(diff.contains("+    if discount_percent < 0 or discount_percent > 100:"))

        val rt2 = env.attach(runId)
        val result = rt2.rollback()
        assertTrue(result is JournalEngine.RollbackResult.Success, result.toString())
        assertEquals(BROKEN_ORDER, env.wsFile("order.py").readText())
        assertEquals(RunStatus.ROLLED_BACK, env.state(runId).status)

        assertEquals(TEST_ORDER, env.wsFile("test_order.py").readText())
    }

    @Test
    fun `rollback refuses and touches nothing when a file was changed externally afterwards`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            textReply("fixed"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val runId = rt.state.runId

        val external = "# externally edited\n" + FIXED_ORDER
        env.wsFile("order.py").writeText(external)

        val rt2 = env.attach(runId)
        val result = rt2.rollback()
        assertTrue(result is JournalEngine.RollbackResult.Conflict, result.toString())
        assertTrue(result.toString().contains("modified externally"))
        assertEquals(external, env.wsFile("order.py").readText())
    }

    @Test
    fun `deletion is journaled and rollback restores the deleted file`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("d1", "delete_file", varargJson("path" to "order.py"))),
            textReply("removed"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val runId = rt.state.runId
        val before = env.state(runId)
        assertEquals(RunStatus.AWAITING_CONFIRMATION, before.status)
        rt.setConfirmationDecision(runId, before.pendingConfirmation!!.callId, true)
        rt.runLoop()
        assertFalse(env.wsFile("order.py").exists(), "the file was deleted after approval")

        val state = env.state(runId)
        val entry = state.journal.entries.first { it.path == "order.py" }
        assertTrue(entry.existedBefore)
        assertEquals(sha256(BROKEN_ORDER.toByteArray()), entry.originalHash)
        assertNull(entry.finalContentB64, "deleted at the end of the run")
        assertTrue(rt.renderJournalDiff().contains("DELETED by this run"))

        val rt2 = env.attach(runId)
        val result = rt2.rollback()
        assertTrue(result is JournalEngine.RollbackResult.Success, result.toString())
        assertEquals(BROKEN_ORDER, env.wsFile("order.py").readText())
    }

    @Test
    fun `bash-made changes are journaled too and rollback removes created files`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(
                call("w1", "write_file", varargJson("path" to "a.txt", "content" to "payload")),
                call("b1", "bash", varargJson("command" to "cp a.txt b.txt", "cwd" to ".")),
            ),
            textReply("done"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val runId = rt.state.runId

        val state = env.state(runId)
        assertTrue(env.wsFile("b.txt").exists(), "bash created b.txt")
        val bEntry = state.journal.entries.first { it.path == "b.txt" }
        assertFalse(bEntry.existedBefore, "b.txt was created by bash and must be journaled as created")
        val aEntry = state.journal.entries.first { it.path == "a.txt" }
        assertFalse(aEntry.existedBefore)

        val rt2 = env.attach(runId)
        val result = rt2.rollback()
        assertTrue(result is JournalEngine.RollbackResult.Success, result.toString())
        assertFalse(env.wsFile("b.txt").exists(), "created file removed")
        assertFalse(env.wsFile("a.txt").exists(), "created file removed")
    }

    @Test
    fun `external modification between agent actions stops the next action with a conflict`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
        )
        val rt = env.newRun(llm, mode = RunMode.SAFE)
        rt.runLoop()
        val runId = rt.state.runId
        assertEquals(RunStatus.AWAITING_CONFIRMATION, env.state(runId).status)

        val external = "# external edit\n" + BROKEN_ORDER
        env.wsFile("order.py").writeText(external)

        val rt2 = env.attach(runId, FakeLlm(textReply("cannot proceed: the workspace changed externally")))
        rt2.setConfirmationDecision(runId, env.state(runId).pendingConfirmation!!.callId, true)
        rt2.runLoop()

        val state = env.state(runId)
        val w1 = state.call("w1")
        assertEquals(AttemptOutcome.CONFLICT, w1.lastAttempt?.outcome)
        assertTrue(w1.lastAttempt?.result.toString().contains("changed externally"), w1.lastAttempt?.result.toString())
        assertEquals(external, env.wsFile("order.py").readText(), "the conflicting action was stopped")
        assertTrue(state.journal.conflicts.isNotEmpty())
    }

    @Test
    fun `rollback requires indeterminate calls to be resolved first, with explicit change attribution`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        val runId = rt.state.runId
        crashAt("after_exec_before_save", 1) { rt.runLoop() }
        assertEquals(FIXED_ORDER, env.wsFile("order.py").readText())

        val rt2 = env.attach(runId)
        assertTrue(rt2.rollback() is JournalEngine.RollbackResult.Conflict)

        val rt3 = env.attach(runId)
        val e = assertFailsWith<IllegalArgumentException>("attribution decision must be required") {
            rt3.resolveIndeterminate(mapOf("w1" to ResolutionAction.FAIL), null)
        }
        assertTrue(e.message!!.contains("--attribute"))

        val rt4 = env.attach(runId)
        rt4.resolveIndeterminate(mapOf("w1" to ResolutionAction.FAIL), true)
        val result = rt4.rollback()
        assertTrue(result is JournalEngine.RollbackResult.Success, result.toString())
        assertEquals(BROKEN_ORDER, env.wsFile("order.py").readText())
    }

    @Test
    fun `attribution no keeps external changes out of the journal and rollback reports a conflict for them`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "scratch.py", "content" to "new file"))),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        val runId = rt.state.runId
        crashAt("after_exec_before_save", 1) { rt.runLoop() }

        val rt2 = env.attach(runId)
        rt2.resolveIndeterminate(mapOf("w1" to ResolutionAction.FAIL), false)

        val state = env.state(runId)
        assertTrue(state.journal.entries.isEmpty(), "changes attributed as external must not enter the journal")
        assertEquals("new file", env.wsFile("scratch.py").readText())

        val result = rt2.rollback()
        assertTrue(result is JournalEngine.RollbackResult.Success)
        assertEquals("new file", env.wsFile("scratch.py").readText())
    }

    @Test
    fun `diff after a successful rollback states that the changes were undone`() {
        val env = TestEnv.create()
        val llm = FakeLlm(
            toolReply(call("w1", "write_file", varargJson("path" to "order.py", "content" to FIXED_ORDER))),
            textReply("fixed"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()
        val runId = rt.state.runId

        val rt2 = env.attach(runId)
        assertTrue(rt2.rollback() is JournalEngine.RollbackResult.Success)

        val diff = env.attach(runId).renderJournalDiff()
        assertTrue(diff.contains("rolled back"), diff)
        assertTrue(diff.contains("order.py: MODIFIED by this run"), diff)
    }

    @Test
    fun `binary file changes get a size summary instead of a corrupted text diff`() {
        val env = TestEnv.create(
            policyJson = DEFAULT_TEST_POLICY.replace(
                "\"cp a.txt b.txt\"",
                "\"cp a.txt b.txt\",\n      \"cp blob.bin blob2.bin\"",
            ),
        )
        env.wsFile("blob.bin").writeBytes(
            intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0xFF, 0xFE, 0x00).map { it.toByte() }.toByteArray(),
        )
        val llm = FakeLlm(
            toolReply(call("b1", "bash", varargJson("command" to "cp blob.bin blob2.bin", "cwd" to "."))),
            textReply("copied"),
        )
        val rt = env.newRun(llm, mode = RunMode.AUTO)
        rt.runLoop()

        val state = env.state(rt.state.runId)
        assertEquals(RunStatus.FINISHED, state.status)
        assertTrue(state.journal.entries.any { it.path == "blob2.bin" })
        val diff = rt.renderJournalDiff()
        assertTrue(diff.contains("blob2.bin: CREATED by this run"), diff)
        assertTrue(diff.contains("binary content:"), diff)
        assertTrue(diff.contains("text diff omitted"), diff)
    }
}
