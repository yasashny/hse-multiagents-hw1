package com.yasashny.agentshw1.exec

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BashExecutor(
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val hardOutputCapBytes: Int = DEFAULT_HARD_OUTPUT_CAP_BYTES,
) {

    data class BashOutcome(
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val timedOut: Boolean,
        val truncated: Boolean,
    )

    fun run(command: String, cwd: File, outputCapBytes: Long = hardOutputCapBytes.toLong()): BashOutcome {
        val cap = outputCapBytes.coerceIn(0, hardOutputCapBytes.toLong()).toInt()
        val process = ProcessBuilder("/bin/bash", "-c", command)
            .directory(cwd)
            .start()

        val stdoutCollector = StreamCollector(process.inputStream, cap)
        val stderrCollector = StreamCollector(process.errorStream, cap)
        val threads = listOf(Thread(stdoutCollector), Thread(stderrCollector))
        threads.forEach {
            it.isDaemon = true
            it.start()
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.toHandle().descendants().forEach { it.destroy() }
            process.destroyForcibly()
        }
        threads.forEach { it.join(THREAD_JOIN_MILLIS) }

        return BashOutcome(
            stdout = stdoutCollector.text(),
            stderr = stderrCollector.text(),
            exitCode = if (finished) process.exitValue() else null,
            timedOut = !finished,
            truncated = stdoutCollector.truncated || stderrCollector.truncated,
        )
    }

    fun resultJson(outcome: BashOutcome): JsonObject = buildJsonObject {
        put("ok", outcome.exitCode == 0 && !outcome.timedOut)
        put("exit_code", outcome.exitCode ?: -1)
        put("timed_out", outcome.timedOut)
        put("truncated", outcome.truncated)
        if (outcome.truncated) {
            put("note", "output was truncated to fit the output budget; result is incomplete")
        }
        put("stdout", outcome.stdout)
        put("stderr", outcome.stderr)
    }

    private class StreamCollector(
        private val stream: InputStream,
        private val cap: Int,
    ) : Runnable {

        var truncated = false
            private set

        private val buffer = ByteArrayOutputStream()

        override fun run() {
            val chunk = ByteArray(STREAM_CHUNK_BYTES)
            try {
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    val room = minOf(read, cap - buffer.size())
                    if (room > 0) buffer.write(chunk, 0, room)
                    if (room < read) truncated = true
                }
            } catch (_: Exception) {
            }
        }

        fun text(): String = buffer.toString(Charsets.UTF_8)
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 60L
        private const val DEFAULT_HARD_OUTPUT_CAP_BYTES = 512 * 1024
        private const val STREAM_CHUNK_BYTES = 8 * 1024
        private const val THREAD_JOIN_MILLIS = 5_000L
    }
}
