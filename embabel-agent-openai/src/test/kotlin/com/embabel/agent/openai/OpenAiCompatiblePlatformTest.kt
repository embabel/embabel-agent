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

import com.embabel.agent.api.models.DeepSeekModels
import com.embabel.agent.api.models.MistralAiModels
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.spi.LlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.CredentialEndpoint
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelKind
import com.embabel.common.ai.model.ModelName
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.model.ProviderCredential
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture

class OpenAiCompatiblePlatformTest {

    private companion object {

        /** Never sent to a real provider: the tests build clients, or call a local socket. */
        const val TEST_API_KEY = "sk-test-not-a-real-key"

        const val GATEWAY_PROVIDER = "OurGateway"

        /** A base URL for tests that build clients and make no request. */
        const val GATEWAY_URL = "https://gateway.example.com/v1"

        const val EMBEDDING_MODEL = "some-embedding"

        /** Not any real model's width, so a width assumed from the model name would not match it. */
        const val WIDTH_THE_SERVER_REPORTS = 7
    }

    private fun key(provider: String) = ProviderCredential(provider, TEST_API_KEY)

    @Nested
    inner class Kinds {

        @Test
        fun `OpenAI builds chat and embedding`() {
            assertEquals(OpenAiCompatiblePlatform.CHAT_AND_EMBEDDING, OpenAiPlatform().kinds)
        }

        @Test
        fun `DeepSeek builds chat only, because DeepSeek has no embeddings API`() {
            assertEquals(setOf(LlmService.KIND), DeepSeekPlatform().kinds)
        }

        @Test
        fun `Mistral AI builds chat and embedding`() {
            assertEquals(OpenAiCompatiblePlatform.CHAT_AND_EMBEDDING, MistralAiPlatform().kinds)
        }

        @Test
        fun `the catch-all builds both kinds unless told otherwise`() {
            assertEquals(
                OpenAiCompatiblePlatform.CHAT_AND_EMBEDDING,
                OpenAiCompatiblePlatform(GATEWAY_PROVIDER, GATEWAY_URL).kinds,
            )
        }

        @Test
        fun `a DeepSeek key asked for an embedding model gets none, with no request made`() {
            // Null, not an exception: the platform does not list the kind, so it never builds a
            // client. An HTTP call to DeepSeek here would fail with a provider error instead.
            assertNull(DeepSeekPlatform().model(key(DeepSeekModels.PROVIDER), EmbeddingService.KIND, EMBEDDING_MODEL))
        }

        @Test
        fun `a kind this class has no client for is rejected at construction`() {
            val reranking = ModelKind<EmbeddingService, ModelName>("reranking", EmbeddingService::class.java)

            assertThrows<IllegalArgumentException> {
                OpenAiCompatiblePlatform(GATEWAY_PROVIDER, GATEWAY_URL, setOf(reranking))
            }
            // An empty set would be a platform the model provider never asks.
            assertThrows<IllegalArgumentException> {
                OpenAiCompatiblePlatform(GATEWAY_PROVIDER, GATEWAY_URL, emptySet())
            }
        }
    }

    @Nested
    inner class NamedPlatforms {

        @Test
        fun `each named platform uses the base URL embabel-agent-openai knows for its provider`() {
            // Read from the factory's endpoints, so the named platforms and endpointFor cannot disagree.
            listOf(OpenAiPlatform(), DeepSeekPlatform(), MistralAiPlatform()).forEach { platform ->
                assertEquals(OpenAiCompatibleModelFactory.endpointFor(platform.provider)?.baseUrl, platform.baseUrl, platform.provider)
            }
        }

        @Test
        fun `a named platform builds a chat model for its own provider's key, spelled the framework's way`() {
            val chat = MistralAiPlatform().model(key("  mistral ai "), LlmService.KIND, MistralAiModels.MINISTRAL_8B)

            // The credential's spelling is matched ignoring case and spaces.
            assertEquals(MistralAiModels.MINISTRAL_8B, chat?.name)
            // The model reports the provider constant, not the credential's spelling.
            assertEquals(MistralAiModels.PROVIDER, chat?.provider)
            // The user's key pays, so the deployment is not charged.
            assertEquals(PricingModel.ALL_YOU_CAN_EAT, chat?.pricingModel)
        }

        @Test
        fun `a named platform returns null for another provider's key`() {
            assertNull(DeepSeekPlatform().model(key(OpenAiModels.PROVIDER), LlmService.KIND, OpenAiModels.GPT_41_MINI))
            assertNull(OpenAiPlatform().model(key(DeepSeekModels.PROVIDER), LlmService.KIND, DeepSeekModels.DEEPSEEK_V4_FLASH))
        }

        @Test
        fun `withTimeouts keeps the named type, provider and kinds`() {
            val timeouts = OpenAiClientTimeouts(read = Duration.ofMinutes(5))
            val patient: DeepSeekPlatform = DeepSeekPlatform().withTimeouts(timeouts)

            // The new platform holds the timeouts it was given.
            assertSame(timeouts, patient.timeouts)
            // Everything else is the same as DeepSeekPlatform().
            assertEquals(DeepSeekModels.PROVIDER, patient.provider)
            assertEquals(setOf(LlmService.KIND), patient.kinds)
        }

        @Test
        fun `withTimeouts on OpenAI and Mistral AI keeps the named type and holds the new timeouts`() {
            val timeouts = OpenAiClientTimeouts(read = Duration.ofMinutes(5))
            // The declared types fail to compile if an override that keeps the named type is removed.
            val openAi: OpenAiPlatform = OpenAiPlatform().withTimeouts(timeouts)
            val mistral: MistralAiPlatform = MistralAiPlatform().withTimeouts(timeouts)

            assertSame(timeouts, openAi.timeouts)
            assertEquals(OpenAiModels.PROVIDER, openAi.provider)
            assertSame(timeouts, mistral.timeouts)
            assertEquals(MistralAiModels.PROVIDER, mistral.provider)
        }
    }

    @Nested
    inner class CatchAll {

        @Test
        fun `chat requests go to the base URL it was given`() {
            withServer("{}") { baseUrl, requestPath ->
                val chat = OpenAiCompatiblePlatform(GATEWAY_PROVIDER, baseUrl)
                    .model(key(GATEWAY_PROVIDER), LlmService.KIND, "some-model")
                assertNotNull(chat)
                // "{}" is not a chat completion, so the call fails after the request arrived.
                runCatching {
                    chat?.createMessageSender(LlmOptions())
                        ?.call(listOf(UserMessage("Hi")), emptyList())
                }

                assertEquals("/chat/completions", requestPath.getNow(null))
                assertEquals(GATEWAY_PROVIDER, chat?.provider)
            }
        }

        @Test
        fun `an embedding model is probed at the base URL and reports the width the server returned`() {
            withServer(embeddingResponse(WIDTH_THE_SERVER_REPORTS)) { baseUrl, requestPath ->
                val embedding = OpenAiCompatiblePlatform(GATEWAY_PROVIDER, baseUrl)
                    .model(key(GATEWAY_PROVIDER), EmbeddingService.KIND, EMBEDDING_MODEL)

                // The probe went to the gateway's embeddings path.
                assertEquals("/embeddings", requestPath.getNow(null))
                // The width came from the response, not from a catalogue.
                assertEquals(WIDTH_THE_SERVER_REPORTS, embedding?.dimensions)
                assertEquals(GATEWAY_PROVIDER, embedding?.provider)
            }
        }

        @Test
        fun `a chat-only catch-all returns null for an embedding model`() {
            val chatOnly = OpenAiCompatiblePlatform(
                GATEWAY_PROVIDER,
                GATEWAY_URL,
                OpenAiCompatiblePlatform.CHAT_ONLY,
            )

            assertNull(chatOnly.model(key(GATEWAY_PROVIDER), EmbeddingService.KIND, EMBEDDING_MODEL))
        }

        @Test
        fun `the catch-all returns null for a key of another provider`() {
            val gateway = OpenAiCompatiblePlatform(GATEWAY_PROVIDER, GATEWAY_URL)

            assertNull(gateway.model(key(OpenAiModels.PROVIDER), LlmService.KIND, OpenAiModels.GPT_41_MINI))
        }

        @Test
        fun `a provider name given with surrounding spaces is stored without them and still matches a key`() {
            val gateway = OpenAiCompatiblePlatform("  $GATEWAY_PROVIDER ", GATEWAY_URL)

            // The spaces are removed from the name the platform stores.
            assertEquals(GATEWAY_PROVIDER, gateway.provider)
            // A key stored under the name without spaces is a key for this platform.
            assertTrue(gateway.serves(key(GATEWAY_PROVIDER)))
            // A model built for that key reports the name without spaces.
            assertEquals(
                GATEWAY_PROVIDER,
                gateway.model(key(GATEWAY_PROVIDER), LlmService.KIND, "some-model")?.provider,
            )
        }

        @Test
        fun `withTimeouts on the catch-all keeps its provider, base URL and kinds`() {
            val timeouts = OpenAiClientTimeouts(read = Duration.ofMinutes(5))
            val gateway = OpenAiCompatiblePlatform(GATEWAY_PROVIDER, GATEWAY_URL, OpenAiCompatiblePlatform.CHAT_ONLY)
                .withTimeouts(timeouts)

            // The new platform holds the timeouts it was given.
            assertSame(timeouts, gateway.timeouts)
            // Everything else is what the original platform was given.
            assertEquals(GATEWAY_PROVIDER, gateway.provider)
            assertEquals(GATEWAY_URL, gateway.baseUrl)
            assertEquals(OpenAiCompatiblePlatform.CHAT_ONLY, gateway.kinds)
        }

        @Test
        fun `modelAt returns null for a kind that is neither chat nor embedding`() {
            // The constructor rejects such a kind, so only a direct call to modelAt can pass one.
            val reranking = ModelKind<EmbeddingService, ModelName>("reranking", EmbeddingService::class.java)
            val endpoint = CredentialEndpoint.OpenAiCompatible(provider = GATEWAY_PROVIDER, baseUrl = GATEWAY_URL)

            assertNull(
                OpenAiCompatiblePlatform.modelAt(endpoint, TEST_API_KEY, reranking, "some-model", OpenAiClientTimeouts.DEFAULT),
            )
        }

        @Test
        fun `a blank provider name is rejected at construction`() {
            // Spaces only: blank once trimmed.
            assertThrows<IllegalArgumentException> {
                OpenAiCompatiblePlatform("   ", GATEWAY_URL)
            }
            // Empty.
            assertThrows<IllegalArgumentException> {
                OpenAiCompatiblePlatform("", GATEWAY_URL)
            }
        }

        /**
         * Runs [test] against a local socket that answers every request with [body].
         *
         * @param test given the socket's base URL and the path of the first request it received
         */
        private fun withServer(body: String, test: (baseUrl: String, requestPath: CompletableFuture<String>) -> Unit) {
            // Port 0 lets the operating system choose a free port. The server listens on the
            // IPv4 loopback address and the test calls it by that address, because "localhost" can
            // resolve to another address where a different process holds the same port.
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 0)
            val requestPath = CompletableFuture<String>()
            server.createContext("/") { exchange ->
                requestPath.complete(exchange.requestURI.path)
                exchange.requestBody.use { it.readBytes() }
                val bytes = body.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                test("http://$LOOPBACK:${server.address.port}", requestPath)
            } finally {
                server.stop(0)
            }
        }

        private val LOOPBACK = "127.0.0.1"

        /** One OpenAI embeddings response holding a vector of [width] numbers. */
        private fun embeddingResponse(width: Int): String {
            val vector = (0 until width).joinToString(",") { "0.1" }
            return """
                {"object":"list",
                 "model":"$EMBEDDING_MODEL",
                 "data":[{"object":"embedding","index":0,"embedding":[$vector]}],
                 "usage":{"prompt_tokens":1,"total_tokens":1}}
            """.trimIndent()
        }
    }
}
