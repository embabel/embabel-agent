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
import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.api.models.MistralAiModels
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.spi.LlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ProviderCredential
import com.embabel.common.byok.InvalidApiKeyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * IT tests that validate BYOK endpoints against real provider APIs.
 * Each `succeeds with valid key` and `with a valid key` test requires the corresponding API key in
 * the environment, and is skipped without it. The `responds ... to a key it did not issue` tests
 * need network access and no API key.
 *
 * Where to create each key (each provider's console, signed in to an account):
 * - `OPENAI_API_KEY`: https://platform.openai.com/api-keys
 * - `DEEPSEEK_API_KEY`: https://platform.deepseek.com/api_keys
 * - `MISTRAL_API_KEY`: https://console.mistral.ai/api-keys
 * - `GOOGLE_GENAI_API_KEY`: https://aistudio.google.com/apikey
 * - `ATLASCLOUD_API_KEY`: https://www.atlascloud.ai/console/api-keys
 *
 * The chat tests send one short message, and the embedding tests embed one word, so a key on a
 * low-limit or free-tier account is enough.
 */
class OpenAiCompatibleModelFactoryByokIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    fun `openAi buildValidated succeeds with valid key`() {
        val service = OpenAiCompatibleModelFactory.openAi(System.getenv("OPENAI_API_KEY"))
            .buildValidated()
        assertNotNull(service)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    fun `deepSeek buildValidated succeeds with valid key`() {
        val service = OpenAiCompatibleModelFactory.deepSeek(System.getenv("DEEPSEEK_API_KEY"))
            .buildValidated()
        assertNotNull(service)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MISTRAL_API_KEY", matches = ".+")
    fun `mistral buildValidated succeeds with valid key`() {
        val service = OpenAiCompatibleModelFactory.mistral(System.getenv("MISTRAL_API_KEY"))
            .buildValidated()
        assertNotNull(service)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GOOGLE_GENAI_API_KEY", matches = ".+")
    fun `gemini buildValidated succeeds with valid key`() {
        val service = OpenAiCompatibleModelFactory.gemini(System.getenv("GOOGLE_GENAI_API_KEY"))
            .buildValidated()
        assertNotNull(service)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ATLASCLOUD_API_KEY", matches = ".+")
    fun `atlasCloud buildValidated succeeds with valid key`() {
        val service = OpenAiCompatibleModelFactory.atlasCloud(System.getenv("ATLASCLOUD_API_KEY"))
            .buildValidated()
        assertNotNull(service)
        assertEquals("Atlas Cloud", service.provider)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    fun `openAiEmbedding buildValidated succeeds with valid key`() {
        val service = OpenAiCompatibleModelFactory
            .openAiEmbedding(System.getenv("OPENAI_API_KEY"), "text-embedding-3-small")
            .buildValidated()
        assertNotNull(service)
        assertEquals(1536, service.dimensions)
    }

    /**
     * Gemini embeddings over Google's OpenAI-compatible endpoint, configured as a stored
     * credential is: the base URL comes from [OpenAiCompatibleModelFactory.endpointFor] and the
     * model is named by the caller. Google's response has no `index` and no `usage`.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "GOOGLE_GENAI_API_KEY", matches = ".+")
    fun `gemini embedding buildValidated succeeds with valid key`() {
        val gemini = requireNotNull(OpenAiCompatibleModelFactory.endpointFor(GoogleGenAiModels.PROVIDER))
        val service = OpenAiCompatibleModelFactory
            .byokEmbedding(
                baseUrl = gemini.baseUrl,
                apiKey = System.getenv("GOOGLE_GENAI_API_KEY"),
                model = GoogleGenAiModels.GEMINI_EMBEDDING_001,
                provider = gemini.provider,
            )
            .buildValidated()
        assertEquals(3072, service.dimensions)
        assertEquals(3072, service.embed("hello").size)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    fun `openAiEmbedding rejects an invalid key`() {
        assertThrows<InvalidApiKeyException> {
            OpenAiCompatibleModelFactory
                .openAiEmbedding("sk-not-a-real-key", "text-embedding-3-small")
                .buildValidated()
        }
    }

    // The tests below build models through the named platforms, the way a per-user key is built,
    // and make one call with each. Each needs the provider's API key and is skipped without it.

    /**
     * Builds a chat model named [model] through [platform] for a key of [provider], sends it "Hi",
     * and returns the text of the reply. Fails if the platform builds no model, or if the model
     * reports a provider other than [provider].
     */
    private fun replyThrough(platform: OpenAiCompatiblePlatform, provider: String, apiKey: String, model: String): String {
        val chat = requireNotNull(platform.model(ProviderCredential(provider, apiKey), LlmService.KIND, model)) {
            "$platform built no chat model for $provider"
        }
        assertEquals(provider, chat.provider)
        return chat.createMessageSender(LlmOptions()).call(listOf(UserMessage("Hi")), emptyList()).textContent
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    fun `OpenAiPlatform builds a chat model that answers with a valid key`() {
        val reply = replyThrough(OpenAiPlatform(), OpenAiModels.PROVIDER, System.getenv("OPENAI_API_KEY"), OpenAiModels.GPT_41_MINI)
        assertTrue(reply.isNotBlank())
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    fun `OpenAiPlatform builds an embedding model that embeds with a valid key`() {
        val embedding = requireNotNull(
            OpenAiPlatform().model(
                ProviderCredential(OpenAiModels.PROVIDER, System.getenv("OPENAI_API_KEY")),
                EmbeddingService.KIND,
                OpenAiModels.TEXT_EMBEDDING_3_SMALL,
            )
        )
        // The platform reads the width from the server's answer, and the model embeds at that width.
        // 1536 is the width OpenAI documents for text-embedding-3-small.
        assertEquals(1536, embedding.dimensions)
        assertEquals(1536, embedding.embed("hello").size)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    fun `DeepSeekPlatform builds a chat model that answers with a valid key`() {
        val reply = replyThrough(DeepSeekPlatform(), DeepSeekModels.PROVIDER, System.getenv("DEEPSEEK_API_KEY"), DeepSeekModels.DEEPSEEK_V4_FLASH)
        assertTrue(reply.isNotBlank())
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MISTRAL_API_KEY", matches = ".+")
    fun `MistralAiPlatform builds a chat model that answers with a valid key`() {
        val reply = replyThrough(MistralAiPlatform(), MistralAiModels.PROVIDER, System.getenv("MISTRAL_API_KEY"), MistralAiModels.MINISTRAL_8B)
        assertTrue(reply.isNotBlank())
    }

    // The four tests below send a key that no provider issued to the real provider. They need
    // network access and no API key. Each provider responds that it does not know the key, and
    // InvalidApiKeyException.statusCode is the HTTP status code of that response: 401 from
    // OpenAI, DeepSeek and Mistral, and 400 from Google.

    /** Validates [spec] and returns the status code of the [InvalidApiKeyException] it throws. */
    private fun statusCodeFor(spec: OpenAiCompatibleModelFactory.ByokSpec): Int? =
        assertThrows<InvalidApiKeyException> { spec.buildValidated() }.statusCode

    @Test
    fun `openAi responds 401 to a key it did not issue`() {
        assertEquals(401, statusCodeFor(OpenAiCompatibleModelFactory.openAi(UNKNOWN_KEY)))
    }

    @Test
    fun `deepSeek responds 401 to a key it did not issue`() {
        assertEquals(401, statusCodeFor(OpenAiCompatibleModelFactory.deepSeek(UNKNOWN_KEY)))
    }

    @Test
    fun `mistral responds 401 to a key it did not issue`() {
        assertEquals(401, statusCodeFor(OpenAiCompatibleModelFactory.mistral(UNKNOWN_KEY)))
    }

    @Test
    fun `gemini responds 400 to a key it did not issue`() {
        assertEquals(400, statusCodeFor(OpenAiCompatibleModelFactory.gemini(UNKNOWN_KEY)))
    }

    private companion object {
        /** Not a key any provider issued. */
        const val UNKNOWN_KEY = "not-a-real-key"
    }
}
