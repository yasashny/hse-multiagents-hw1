package com.yasashny.agentshw1.core

object TextDiff {

    private const val CONTEXT_LINES = 2

    private const val MAX_DP_CELLS = 4_000_000L

    private enum class Op(val marker: Char) {
        Unchanged(' '),
        Removed('-'),
        Added('+'),
    }

    private data class DiffLine(val op: Op, val text: String)

    private fun splitLines(text: String): List<String> {
        val lines = text.lines()
        return if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    fun unifiedDiff(original: String?, revised: String?, path: String): String {
        val sb = StringBuilder()
        if (original == null) {
            if (revised == null) return ""
            sb.appendLine("--- /dev/null")
            sb.appendLine("+++ $path  (created by this run)")
            splitLines(revised).forEach { sb.appendLine("+$it") }
            return sb.toString()
        }
        if (revised == null) {
            sb.appendLine("--- $path")
            sb.appendLine("+++ /dev/null  (deleted by this run)")
            splitLines(original).forEach { sb.appendLine("-$it") }
            return sb.toString()
        }
        val ops = lcsDiff(splitLines(original), splitLines(revised))
        if (ops.isEmpty()) return "(no textual changes)"
        sb.appendLine("--- $path (original)")
        sb.appendLine("+++ $path (final)")
        appendOps(sb, ops)
        return sb.toString().trimEnd('\n')
    }

    private fun appendOps(sb: StringBuilder, ops: List<DiffLine>) {
        var i = 0
        while (i < ops.size) {
            val context = mutableListOf<DiffLine>()
            val removed = mutableListOf<DiffLine>()
            val added = mutableListOf<DiffLine>()
            while (i < ops.size) {
                val (op, text) = ops[i]
                when (op) {
                    Op.Removed -> if (added.isNotEmpty()) break else removed.add(DiffLine(op, text))
                    Op.Added -> added.add(DiffLine(op, text))
                    Op.Unchanged -> if (removed.isNotEmpty() || added.isNotEmpty()) break else context.add(DiffLine(op, text))
                }
                i++
            }
            context.takeLast(CONTEXT_LINES).forEach { sb.appendLine("${it.op.marker}${it.text}") }
            removed.forEach { sb.appendLine("${it.op.marker}${it.text}") }
            added.forEach { sb.appendLine("${it.op.marker}${it.text}") }
        }
    }

    private fun lcsDiff(a: List<String>, b: List<String>): List<DiffLine> {
        val n = a.size
        val m = b.size
        if (n.toLong() * m.toLong() > MAX_DP_CELLS) {
            return a.map { DiffLine(Op.Removed, it) } + b.map { DiffLine(Op.Added, it) }
        }
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out.add(DiffLine(Op.Unchanged, a[i]))
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(DiffLine(Op.Removed, a[i]))
                    i++
                }
                else -> {
                    out.add(DiffLine(Op.Added, b[j]))
                    j++
                }
            }
        }
        while (i < n) {
            out.add(DiffLine(Op.Removed, a[i]))
            i++
        }
        while (j < m) {
            out.add(DiffLine(Op.Added, b[j]))
            j++
        }
        return trimmedToChanges(out)
    }

    private fun trimmedToChanges(ops: List<DiffLine>): List<DiffLine> {
        val firstChange = ops.indexOfFirst { it.op != Op.Unchanged }
        if (firstChange == -1) return emptyList()
        val lastChange = ops.indexOfLast { it.op != Op.Unchanged }
        val from = maxOf(0, firstChange - CONTEXT_LINES)
        val to = minOf(ops.size, lastChange + CONTEXT_LINES + 1)
        return ops.subList(from, to)
    }
}
