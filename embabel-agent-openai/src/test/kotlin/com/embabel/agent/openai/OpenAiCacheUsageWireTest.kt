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
package com.embabel.agent.openai

import com.embabel.agent.api.models.OpenAiModels
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant

/**
 * What embabel counts for OpenAI cached prompt tokens, from canned responses served by a local stand-in
 * for OpenAI. OpenAI's input count already includes cached tokens, so embabel must not add them again.
 */
class OpenAiCacheUsageWireTest {

    private var response = RESPONSE
    private lateinit var server: HttpServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val streaming = exchange.requestBody.readBytes().decodeToString().contains("\"stream\":true")
            val reply = when {
                exchange.requestURI.path.endsWith("/responses") -> response
                streaming -> CHAT_COMPLETION_STREAM
                else -> CHAT_COMPLETION
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", if (streaming) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private val factory by lazy {
        OpenAiCompatibleModelFactory(baseUrl = "http://localhost:${server.address.port}/v1", apiKey = "test-key")
    }

    private fun chatCompletions() = factory.openAiCompatibleLlm(
        model = MODEL, pricingModel = PRICING, provider = OpenAiModels.PROVIDER, knowledgeCutoffDate = null,
    ) as SpringAiLlmService

    private fun responses() = factory.openAiResponsesLlm(
        model = MODEL, provider = OpenAiModels.PROVIDER, pricingModel = PRICING,
    ) as SpringAiLlmService

    private fun call(llm: SpringAiLlmService): Usage =
        llm.createMessageSender(LlmOptions()).call(listOf(UserMessage("hi")), emptyList()).usage!!

    private fun assertCachedReadCounted(llm: SpringAiLlmService, usage: Usage, cacheWrite: Int? = null) {
        assertEquals(800, usage.cacheReadInputTokens)
        assertEquals(cacheWrite, usage.cacheWriteInputTokens)
        assertEquals(900 + (cacheWrite ?: 0), usage.promptTokens, "the input count already includes cached tokens")
        assertEquals(5, usage.completionTokens)
        // 100 uncached at $2, 800 cached at $0.50, writes at the input rate, 5 out at $8 per 1M.
        val expected = (100 * 2.0 + 800 * 0.5 + (cacheWrite ?: 0) * 2.0 + 5 * 8.0) / 1_000_000
        val cost = LlmInvocation(llm, usage, timestamp = Instant.now(), runningTime = Duration.ZERO).cost()
        assertEquals(expected, cost, 1e-12)
    }

    @Test
    fun `a Chat Completions call counts cached tokens without inflating prompt tokens`() {
        val llm = chatCompletions()
        assertCachedReadCounted(llm, call(llm))
    }

    @Test
    fun `a Chat Completions stream counts cached tokens from the final usage chunk`() {
        val llm = chatCompletions()
        val usage = llm.createMessageStreamer(LlmOptions())
            .streamInference(listOf(UserMessage("hi")), emptyList())
            .ofType(LlmInferenceStreamEvent.Complete::class.java)
            .blockLast(Duration.ofSeconds(10))!!.usage!!
        assertCachedReadCounted(llm, usage)
    }

    @Test
    fun `a Responses call counts cached and cache-write tokens without inflating prompt tokens`() {
        val llm = responses()
        assertCachedReadCounted(llm, call(llm), cacheWrite = 30)
    }

    @Test
    fun `a Responses call without input_tokens_details counts no cache tokens`() {
        response = RESPONSE.replace(Regex(""""input_tokens_details":\{[^}]*},\s*"""), "")
        val usage = call(responses())
        assertNull(usage.cacheReadInputTokens)
        assertNull(usage.cacheWriteInputTokens)
        assertEquals(930, usage.promptTokens)
        assertEquals(5, usage.completionTokens)
    }

    private companion object {
        const val MODEL = "gpt-6-luna"

        val PRICING = PerTokenPricingModel(
            usdPer1mInputTokens = 2.0,
            usdPer1mOutputTokens = 8.0,
            usdPer1mCacheReadInputTokens = 0.5,
        )

        const val CHAT_USAGE = """{"prompt_tokens":900,"completion_tokens":5,"total_tokens":905,
            "prompt_tokens_details":{"cached_tokens":800}}"""

        val CHAT_COMPLETION = """{"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"$MODEL",
            "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
            "usage":$CHAT_USAGE}"""

        val CHAT_COMPLETION_STREAM = listOf(
            """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":0,"model":"$MODEL",
                "choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}""",
            """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":0,"model":"$MODEL",
                "choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":0,"model":"$MODEL",
                "choices":[],"usage":$CHAT_USAGE}""",
        ).joinToString("") { "data: ${it.replace(Regex("\\s*\n\\s*"), "")}\n\n" } + "data: [DONE]\n\n"

        val RESPONSE = """{"id":"resp_1","object":"response","created_at":0,"model":"$MODEL","status":"completed",
            "output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",
              "content":[{"type":"output_text","text":"ok","annotations":[]}]}],
            "parallel_tool_calls":false,"tool_choice":"auto","tools":[],"error":null,"incomplete_details":null,
            "instructions":null,"metadata":null,"temperature":null,"top_p":null,
            "usage":{"input_tokens":930,"output_tokens":5,"total_tokens":935,
              "input_tokens_details":{"cached_tokens":800,"cache_write_tokens":30},
              "output_tokens_details":{"reasoning_tokens":0}}}"""
    }
}
