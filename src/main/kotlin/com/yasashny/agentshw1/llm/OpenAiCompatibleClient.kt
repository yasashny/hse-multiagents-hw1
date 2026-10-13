package com.yasashny.agentshw1.llm

import com.yasashny.agentshw1.llm.models.ModelReply
import com.yasashny.agentshw1.llm.models.StoredMessage
import com.yasashny.agentshw1.llm.models.ToolCallReq
import com.yasashny.agentshw1.llm.models.ToolDescriptor
import com.yasashny.agentshw1.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val retryBackoffMillis: List<Long> = DEFAULT_RETRY_BACKOFF_MILLIS.toList(),
) : LLMClient {

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build()
    }

    override fun chat(messages: List<StoredMessage>, tools: List<ToolDescriptor>): ModelReply {
        val body = buildJsonObject {
            put("model", model)
            put("messages", messagesJson(messages))
            if (tools.isNotEmpty()) {
                put("tools", toolsJson(tools))
                put("tool_choice", "auto")
            }
        }

        val request = try {
            HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/chat/completions"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        } catch (e: Exception) {
            throw LLMException("cannot build model request (is llm.baseUrl valid?): ${e.message}", e)
        }

        var lastError: LLMException? = null
        for (attempt in 0..retryBackoffMillis.size) {
            if (attempt > 0) sleepBeforeRetry(retryBackoffMillis[attempt - 1])
            val response: HttpResponse<String> = try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                lastError = LLMException("model request failed: ${e.message}", e)
                continue
            }
            if (response.statusCode() in HTTP_SUCCESS_RANGE) return parse(response.body())
            lastError = LLMException(
                "model returned HTTP ${response.statusCode()}: ${response.body().take(ERROR_PREVIEW_LENGTH)}",
            )
            if (response.statusCode() !in RETRYABLE_STATUS_CODES) throw lastError
        }
        throw lastError ?: LLMException("model request failed without a response")
    }

    private fun sleepBeforeRetry(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LLMException("model request interrupted while waiting to retry", e)
        }
    }

    private fun messagesJson(messages: List<StoredMessage>) = buildJsonArray {
        messages.forEach { message ->
            add(buildJsonObject {
                put("role", message.role.wire)
                message.content?.let { put("content", it) }
                if (message.toolCalls.isNotEmpty()) put("tool_calls", toolCallsJson(message.toolCalls))
                message.toolCallId?.let { put("tool_call_id", it) }
                message.name?.let { put("name", it) }
            })
        }
    }

    private fun toolCallsJson(calls: List<ToolCallReq>) = buildJsonArray {
        calls.forEach { call ->
            add(buildJsonObject {
                put("id", call.id)
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", call.name)
                    put("arguments", call.args.toString())
                })
            })
        }
    }

    private fun toolsJson(tools: List<ToolDescriptor>) = buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                })
            })
        }
    }

    private fun parse(body: String): ModelReply = try {
        parseReply(body)
    } catch (e: LLMException) {
        throw e
    } catch (e: Exception) {
        throw LLMException("malformed reply from the model API: ${e.message}", e)
    }

    private fun parseReply(body: String): ModelReply {
        val root = Json.parseToJsonElement(body).jsonObject
        root["error"]?.let { throw LLMException("model API error: $it") }
        val choices = root["choices"]?.jsonArray
            ?: throw LLMException("malformed reply: no choices")
        if (choices.isEmpty()) throw LLMException("malformed reply: empty choices")
        val message = choices[0].jsonObject["message"]?.jsonObject
            ?: throw LLMException("malformed reply: no message")
        val content = message["content"]?.jsonPrimitive?.takeIf { it.isString }?.content

        val calls = message["tool_calls"]?.jsonArray?.mapIndexedNotNull { index, tc ->
            val fn = tc.jsonObject["function"]?.jsonObject ?: return@mapIndexedNotNull null
            val id = tc.jsonObject["id"]?.jsonPrimitive?.content ?: "call_$index"
            val name = fn["name"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
            val argsRaw = fn["arguments"]?.jsonPrimitive?.content ?: "{}"
            val args = parseArgs(argsRaw)
            ToolCallReq(id, name, args)
        }.orEmpty()

        return if (calls.isNotEmpty()) ModelReply.ToolCalls(calls) else ModelReply.Text(content ?: "")
    }

    private fun parseArgs(argsRaw: String): JsonObject = try {
        Json.parseToJsonElement(argsRaw).jsonObject
    } catch (e: Exception) {
        buildJsonObject { put("_invalid_arguments", argsRaw) }
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val REQUEST_TIMEOUT_SECONDS = 300L
        private const val ERROR_PREVIEW_LENGTH = 500
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private val HTTP_SUCCESS_RANGE = 200..299
        private val RETRYABLE_STATUS_CODES = setOf(429, 500, 502, 503, 504)
        private val DEFAULT_RETRY_BACKOFF_MILLIS = longArrayOf(1_000, 4_000, 10_000, 25_000)

        fun fromSettings(settings: Settings): OpenAiCompatibleClient {
            val llm = settings.llm
            val model = llm.model
                ?: throw LLMException(
                    "model is not configured: set llm.model in settings.json (see settings.example.json)",
                )
            return OpenAiCompatibleClient(
                baseUrl = llm.baseUrl ?: DEFAULT_BASE_URL,
                apiKey = llm.apiKey.orEmpty(),
                model = model,
            )
        }
    }
}
