package com.yasashny.agentshw1

import com.yasashny.agentshw1.core.TextDiff
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextDiffTest {

    @Test
    fun `no phantom empty context line for newline-terminated files`() {
        val diff = TextDiff.unifiedDiff("one\ntwo\n", "one\ntwo\nthree\n", "f.txt")
        assertTrue(diff.contains("+three"))
        assertFalse(diff.contains("\n \n"), diff)
        assertFalse(diff.lines().any { it == " " || it == "+" || it == "-" }, diff)
    }

    @Test
    fun `created and deleted file diffs also drop the phantom trailing line`() {
        val created = TextDiff.unifiedDiff(null, "alpha\nbeta\n", "new.txt")
        assertFalse(created.lines().any { it == "+" }, created)

        val deleted = TextDiff.unifiedDiff("alpha\nbeta\n", null, "old.txt")
        assertFalse(deleted.lines().any { it == "-" }, deleted)
    }

    @Test
    fun `huge inputs fall back to a whole-file replacement instead of an OOM`() {
        val a = (1..20_000).joinToString("\n") { "line $it" }
        val b = (1..20_000).joinToString("\n") { "other $it" }
        val diff = TextDiff.unifiedDiff(a, b, "big.txt")
        assertTrue(diff.contains("-line 1"))
        assertTrue(diff.contains("+other 1"))
        assertTrue(diff.contains("-line 20000"))
        assertTrue(diff.contains("+other 20000"))
    }
}
