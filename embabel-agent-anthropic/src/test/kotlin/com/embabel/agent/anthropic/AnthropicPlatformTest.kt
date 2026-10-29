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

import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.CredentialEndpoint
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.model.ProviderCredential
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AnthropicPlatformTest {

    private companion object {
        /** Never sent anywhere: these tests build clients and make no calls. */
        const val TEST_API_KEY = "sk-ant-test-not-a-real-key"
    }

    private val platform = AnthropicPlatform()

    @Test
    fun `builds chat only, because Anthropic has no embeddings API`() {
        assertEquals(setOf(LlmService.KIND), platform.kinds)
    }

    @Test
    fun `an Anthropic key gets a chat model that reports Anthropic and is not charged to the deployment`() {
        // Lower case with surrounding spaces: the provider is matched ignoring both.
        val chat = platform.model(ProviderCredential("  anthropic ", TEST_API_KEY), LlmService.KIND, AnthropicModels.CLAUDE_HAIKU_4_5)

        assertEquals(AnthropicModels.CLAUDE_HAIKU_4_5, chat?.name)
        // The provider constant, not the credential's spelling.
        assertEquals(AnthropicModels.PROVIDER, chat?.provider)
        assertEquals(PricingModel.ALL_YOU_CAN_EAT, chat?.pricingModel)
    }

    @Test
    fun `an Anthropic key asked for an embedding model gets none`() {
        assertNull(platform.model(ProviderCredential(AnthropicModels.PROVIDER, TEST_API_KEY), EmbeddingService.KIND, "any"))
    }

    @Test
    fun `another provider's key gets nothing`() {
        assertNull(platform.model(ProviderCredential(OpenAiModels.PROVIDER, TEST_API_KEY), LlmService.KIND, OpenAiModels.GPT_41_MINI))
    }

    @Test
    fun `modelAt carries what the endpoint states to the built model`() {
        val endpoint = CredentialEndpoint.Anthropic(
            provider = "OurAnthropicGateway",
            baseUrl = "https://gateway.example.com",
            knowledgeCutoffDate = LocalDate.of(2026, 1, 31),
        )

        val chat = AnthropicPlatform.modelAt(endpoint, TEST_API_KEY, "some-model")

        assertEquals("OurAnthropicGateway", chat.provider)
        assertEquals(LocalDate.of(2026, 1, 31), chat.knowledgeCutoffDate)
    }
}
