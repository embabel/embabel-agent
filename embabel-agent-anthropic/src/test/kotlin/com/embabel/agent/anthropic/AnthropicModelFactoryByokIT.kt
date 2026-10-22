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

import com.anthropic.errors.AnthropicServiceException
import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.spi.LlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ProviderCredential
import com.embabel.common.byok.InvalidApiKeyException
import com.embabel.common.byok.firstOfType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Validates a key against the Anthropic API.
 */
class AnthropicModelFactoryByokIT {

    /**
     * Sends a key that Anthropic did not issue to the Anthropic API. The test needs network
     * access and no API key. Anthropic responds that it does not know the key, and
     * [InvalidApiKeyException.statusCode] is the HTTP status code of that response.
     */
    @Test
    fun `anthropic responds 401 to a key it did not issue`() {
        val e = assertThrows<InvalidApiKeyException> {
            AnthropicModelFactory(apiKey = UNKNOWN_KEY).buildValidated()
        }

        assertEquals(401, e.statusCode)
    }

    /**
     * Builds a chat model through [AnthropicPlatform] for a key that Anthropic did not issue, and
     * makes one call with it. The test needs network access and no API key. Building makes no
     * call, so the model is built, and Anthropic answers the call with a 401 response.
     */
    @Test
    fun `a chat model built by AnthropicPlatform gets 401 from anthropic for a key it did not issue`() {
        val chat = requireNotNull(
            AnthropicPlatform().model(
                ProviderCredential(AnthropicModels.PROVIDER, UNKNOWN_KEY),
                LlmService.KIND,
                AnthropicModels.CLAUDE_HAIKU_4_5,
            )
        )
        assertEquals(AnthropicModels.PROVIDER, chat.provider)

        val e = assertThrows<Exception> {
            chat.createMessageSender(LlmOptions()).call(listOf(UserMessage("Hi")), emptyList())
        }

        // The SDK's exception may be wrapped, so it is looked for among the causes as well.
        assertEquals(401, e.firstOfType<AnthropicServiceException>()?.statusCode())
    }

    private companion object {
        /** Not a key Anthropic issued. */
        const val UNKNOWN_KEY = "not-a-real-key"
    }
}
