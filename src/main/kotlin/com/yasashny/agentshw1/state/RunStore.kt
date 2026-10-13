package com.yasashny.agentshw1.state

import com.yasashny.agentshw1.state.models.RunState
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RunStore(private val runsDir: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        runsDir.mkdirs()
    }

    private fun dirFor(runId: String): File = File(runsDir, runId).apply { mkdirs() }

    private fun stateFile(runId: String): File = File(dirFor(runId), STATE_FILE)

    fun save(state: RunState) {
        val target = stateFile(state.runId)
        val tmp = File(target.parentFile, "$STATE_FILE.tmp")
        tmp.outputStream().use { out ->
            out.write(json.encodeToString(state).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun load(runId: String): RunState? {
        val file = stateFile(runId)
        if (!file.exists()) return null
        return json.decodeFromString<RunState>(file.readText())
    }

    fun loadOrThrow(runId: String): RunState =
        load(runId) ?: throw IllegalArgumentException(
            "run not found: $runId (searched in ${runsDir.absolutePath})",
        )

    fun listRuns(): List<RunState> =
        runsDir.listFiles { dir -> dir.isDirectory }
            ?.sortedBy { it.name }
            ?.mapNotNull { dir ->
                File(dir, STATE_FILE).takeIf { it.exists() }
                    ?.let { json.decodeFromString<RunState>(it.readText()) }
            }
            .orEmpty()

    fun newRunId(): String {
        val stamp = System.currentTimeMillis()
        var suffix = 0
        var candidate = "run-$stamp"
        while (File(runsDir, candidate).exists()) {
            suffix += 1
            candidate = "run-$stamp-$suffix"
        }
        return candidate
    }

    private companion object {
        const val STATE_FILE = "state.json"
    }
}
