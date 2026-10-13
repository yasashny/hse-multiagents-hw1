package com.yasashny.agentshw1.loop

import com.yasashny.agentshw1.llm.models.MessageRole
import com.yasashny.agentshw1.llm.models.StoredMessage
import com.yasashny.agentshw1.llm.models.ToolCallReq
import com.yasashny.agentshw1.state.models.Attempt
import com.yasashny.agentshw1.state.models.AttemptOutcome
import com.yasashny.agentshw1.state.models.CallRecord
import com.yasashny.agentshw1.state.models.DebitRecord
import com.yasashny.agentshw1.tools.ErrorKind
import com.yasashny.agentshw1.tools.ToolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.isOk(): Boolean = this["ok"]?.jsonPrimitive?.contentOrNull == "true"

internal fun JsonObject.errorText(): String? = this["error"]?.jsonPrimitive?.contentOrNull

internal class CallLedger(
    private val env: RuntimeEnv,
    private val outputBudget: OutputBudget,
) {

    fun recordFor(callId: String): CallRecord? = env.state.calls.find { it.callId == callId }

    fun nextAttemptIndex(call: ToolCallReq): Int =
        recordFor(call.id)?.attempts?.size ?: 0

    fun dispatchOrdinal(): Int = env.state.calls.sumOf { it.attempts.size } + 1

    fun upsertRecord(call: ToolCallReq, attempt: Attempt) {
        val index = env.state.calls.indexOfFirst { it.callId == call.id }
        if (index >= 0) {
            val updated = env.state.calls[index].copy(attempts = env.state.calls[index].attempts + attempt)
            env.state.calls = env.state.calls.toMutableList().also { it[index] = updated }
        } else {
            env.state.calls += CallRecord(
                callId = call.id,
                name = call.name,
                args = call.args,
                phase = env.state.phase,
                taskId = env.state.currentTaskId,
                attempts = listOf(attempt),
            )
        }
    }

    fun updateLastAttempt(call: ToolCallReq, transform: (Attempt) -> Attempt) {
        val index = env.state.calls.indexOfFirst { it.callId == call.id }
        if (index < 0) return
        val record = env.state.calls[index]
        val attempts = record.attempts.toMutableList()
        if (attempts.isNotEmpty()) {
            attempts[attempts.lastIndex] = transform(attempts.last())
        }
        env.state.calls = env.state.calls.toMutableList().also { it[index] = record.copy(attempts = attempts) }
    }

    fun appendToolMessage(call: ToolCallReq, result: JsonObject) {
        val resultWithCallId = JsonObject(result + ("call_id" to JsonPrimitive(call.id)))
        env.state.messages += StoredMessage(
            role = MessageRole.TOOL,
            content = resultWithCallId.toString(),
            toolCallId = call.id,
            name = call.name,
        )
    }

    fun dequeue(callId: String) {
        val index = env.state.batchQueue.indexOfFirst { it.id == callId }
        if (index >= 0) {
            env.state.batchQueue = env.state.batchQueue.filterIndexed { i, _ -> i != index }
        }
    }

    fun recordTerminalAttempt(call: ToolCallReq, outcome: AttemptOutcome, result: JsonObject): JsonObject {
        env.state.budget = env.state.budget.copy(toolCallsSpent = env.state.budget.toolCallsSpent + 1)
        val capped = outputBudget.enforce(result)
        val outputBytes = outputBudget.debit(capped)
        upsertRecord(
            call,
            Attempt(
                index = nextAttemptIndex(call),
                outcome = outcome,
                result = capped,
                debit = DebitRecord(toolCalls = 1),
                outputBytes = outputBytes,
            ),
        )
        appendToolMessage(call, capped)
        dequeue(call.id)
        env.save()
        return capped
    }

    fun completeWithoutExecution(call: ToolCallReq, outcome: AttemptOutcome, result: JsonObject) {
        val capped = recordTerminalAttempt(call, outcome, result)
        env.ui.toolResult(call.id, call.name, ok = capped.isOk(), summary = capped.errorText()?.take(RESULT_SUMMARY_LENGTH) ?: outcome.name)
    }

    fun replayStored(call: ToolCallReq, record: CallRecord) {
        val stored = record.lastRealResult ?: ToolJson.error("no stored result for call ${call.id}")
        appendToolMessage(call, stored)
        dequeue(call.id)
        env.save()
        env.ui.toolResult(call.id, call.name, ok = false, summary = "replayed stored result (no re-execution)")
    }

    fun resultSummary(result: JsonObject): String {
        result.errorText()?.let { return it.take(RESULT_SUMMARY_LENGTH) }
        result["stdout"]?.jsonPrimitive?.contentOrNull?.let { stdout ->
            val stderr = result["stderr"]?.jsonPrimitive?.contentOrNull

            return "exit=${result["exit_code"]?.jsonPrimitive?.content}, ${stdout.length} bytes stdout" +
                (if (stderr != null) ", ${stderr.length} bytes stderr" else "")
        }
        result["content"]?.jsonPrimitive?.contentOrNull?.let { content ->
            return "${content.length} chars"
        }
        return result.keys.joinToString(",")
    }

    companion object {
        private const val RESULT_SUMMARY_LENGTH = 140
    }
}
