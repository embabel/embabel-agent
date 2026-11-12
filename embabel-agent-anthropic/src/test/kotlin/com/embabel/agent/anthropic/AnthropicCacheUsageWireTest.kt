/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.anthropic

import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.Usage
import com.embabel.agent.spi.loop.streaming.LlmInferenceStreamEvent
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PerTokenPricingModel
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant

/**
 * What embabel counts for Anthropic prompt-cache tokens, from canned Messages API responses served by a
 * local stand-in for Anthropic and read through Spring AI's common usage fields.
 */
class AnthropicCacheUsageWireTest {

    private var reply = ""
    private lateinit var server: HttpServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            val body = reply.toByteArray()
            val sse = reply.startsWith("event:")
            exchange.responseHeaders.add("Content-Type", if (sse) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private fun llm(): SpringAiLlmService = AnthropicModelFactory(
        apiKey = "test-key", baseUrl = "http://localhost:${server.address.port}",
    ).build(MODEL, pricingModel = PRICING) as SpringAiLlmService

    private fun call(): Usage = llm().createMessageSender(LlmOptions()).call(listOf(UserMessage("hi")), emptyList()).usage!!

    private fun stream(): Usage = llm().createMessageStreamer(LlmOptions()).streamInference(listOf(UserMessage("hi")), emptyList())
        .ofType(LlmInferenceStreamEvent.Complete::class.java)
        .blockLast(Duration.ofSeconds(10))!!.usage!!

    private fun assertCacheCounted(usage: Usage) {
        assertEquals(800, usage.cacheReadInputTokens)
        assertEquals(50, usage.cacheWriteInputTokens)
        assertEquals(950, usage.promptTokens, "input_tokens excludes cache, so embabel adds read and write")
        assertEquals(5, usage.completionTokens)
        // 100 uncached at $3, 800 read at $0.30, 50 written at the default 1.25x ($3.75), 5 out at $15 per 1M.
        val expected = (100 * 3.0 + 800 * 0.3 + 50 * 3.75 + 5 * 15.0) / 1_000_000
        val llm = llm()
        val cost = LlmInvocation(llm, usage, timestamp = Instant.now(), runningTime = Duration.ZERO).cost()
        assertEquals(expected, cost, 1e-12)
    }

    @Test
    fun `a call counts cache reads and writes from the Messages response`() {
        reply = MESSAGE
        assertCacheCounted(call())
    }

    @Test
    fun `a stream counts cache reads and writes from the cumulative message_delta usage`() {
        reply = sse(startUsage = CACHE_USAGE, deltaUsage = CACHE_USAGE)
        assertCacheCounted(stream())
    }

    @Test
    @Disabled(
        "Upstream gap: Spring AI 2.0.1 AnthropicChatModel reads cache counts only from message_delta. " +
            "Anthropic's API repeats them there (cumulative), so this only bites a proxy that drops them."
    )
    fun `a stream counts cache reads and writes sent only in message_start`() {
        reply = sse(startUsage = CACHE_USAGE, deltaUsage = """{"output_tokens":5}""")
        assertCacheCounted(stream())
    }

    private companion object {
        const val MODEL = "claude-haiku-4-5"

        val PRICING = PerTokenPricingModel(
            usdPer1mInputTokens = 3.0,
            usdPer1mOutputTokens = 15.0,
            usdPer1mCacheReadInputTokens = 0.3,
        )

        const val CACHE_USAGE =
            """{"input_tokens":100,"cache_read_input_tokens":800,"cache_creation_input_tokens":50,"output_tokens":5}"""

        val MESSAGE = """{"id":"msg_1","type":"message","role":"assistant","model":"$MODEL",
            "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,
            "usage":$CACHE_USAGE}"""

        fun sse(startUsage: String, deltaUsage: String) = listOf(
            "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",""" +
                """"model":"$MODEL","content":[],"stop_reason":null,"stop_sequence":null,"usage":$startUsage}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":$deltaUsage}""",
            "message_stop" to """{"type":"message_stop"}""",
        ).joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }
    }
}
