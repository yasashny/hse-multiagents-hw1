package com.yasashny.agentshw1

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.yasashny.agentshw1.llm.LLMException
import com.yasashny.agentshw1.llm.OpenAiCompatibleClient
import com.yasashny.agentshw1.llm.models.ModelReply
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class OpenAiCompatibleClientTest {

    private lateinit var server: HttpServer
    private var requestsReceived = 0
    private var responses: MutableList<Int> = mutableListOf()
    private var customBodies: MutableList<String> = mutableListOf()

    private val okBody = """
        {"choices":[{"message":{"role":"assistant","content":"hello"}}]}
    """.trimIndent()

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange: HttpExchange ->
            requestsReceived += 1
            val status = if (responses.isNotEmpty()) responses.removeAt(0) else 200
            val body = if (status in 200..299) {
                if (customBodies.isNotEmpty()) customBodies.removeAt(0) else okBody
            } else {
                """{"error":{"code":"1302","message":"Rate limit reached for requests"}}"""
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun client(backoff: List<Long> = listOf(20, 20, 20)): OpenAiCompatibleClient =
        OpenAiCompatibleClient(
            baseUrl = "http://127.0.0.1:${server.address.port}/v1",
            apiKey = "test-key",
            model = "test-model",
            retryBackoffMillis = backoff,
        )

    @Test
    fun `transient 429 responses are retried within the same model request`() {
        responses.addAll(listOf(429, 429))
        val reply = client().chat(emptyList(), emptyList())
        assertTrue(reply is ModelReply.Text)
        assertEquals("hello", (reply as ModelReply.Text).text)
        assertEquals(3, requestsReceived)
    }

    @Test
    fun `server error 503 is also retried`() {
        responses.addAll(listOf(503))
        val reply = client().chat(emptyList(), emptyList())
        assertTrue(reply is ModelReply.Text)
        assertEquals(2, requestsReceived)
    }

    @Test
    fun `permanent client error fails fast without retry`() {
        responses.addAll(listOf(401))
        assertFailsWith<LLMException> { client().chat(emptyList(), emptyList()) }
        assertEquals(1, requestsReceived)
    }

    @Test
    fun `exhausted retries surface the last error`() {
        responses.addAll(listOf(429, 429, 429, 429))
        val e = assertFailsWith<LLMException> { client().chat(emptyList(), emptyList()) }
        assertTrue(e.message!!.contains("HTTP 429"))
        assertEquals(4, requestsReceived)
    }

    @Test
    fun `tool call with non-JSON arguments becomes a recoverable marker instead of an exception`() {
        customBodies.add(
            buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("message", buildJsonObject {
                            put("role", "assistant")
                            put("tool_calls", buildJsonArray {
                                add(buildJsonObject {
                                    put("id", "call_1")
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", "read_file")
                                        put("arguments", "not json {")
                                    })
                                })
                            })
                        })
                    })
                })
            }.toString(),
        )
        val reply = client().chat(emptyList(), emptyList())
        assertTrue(reply is ModelReply.ToolCalls)
        val call = (reply as ModelReply.ToolCalls).calls.single()
        assertEquals("read_file", call.name)
        assertEquals("not json {", call.args["_invalid_arguments"]?.jsonPrimitive?.content)
    }

    @Test
    fun `structurally invalid response body fails as LLMException not a raw runtime error`() {
        customBodies.add("<html>gateway said no</html>")
        val e = assertFailsWith<LLMException> { client().chat(emptyList(), emptyList()) }
        assertTrue(e.message!!.contains("malformed reply"))
    }
}
