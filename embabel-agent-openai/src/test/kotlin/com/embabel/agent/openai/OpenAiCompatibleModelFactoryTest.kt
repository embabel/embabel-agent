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

import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PricingModel
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.client.RestClient
import java.util.function.Supplier

class OpenAiCompatibleModelFactoryTest {

    private val restClientBuilder = mockk<ObjectProvider<RestClient.Builder>> {
        every { getIfAvailable(any<Supplier<RestClient.Builder>>()) } returns RestClient.builder()
        every { ifAvailable(any()) } just Runs
    }

    @Test
    fun `default base url`() {

        val mf = OpenAiCompatibleModelFactory(
            baseUrl = null,
            apiKey = null,
            completionsPath = null,
            embeddingsPath = null,
            observationRegistry = mockk(),
            restClientBuilder = restClientBuilder,
        )
        val llm = mf.openAiCompatibleLlm(
            model = "foo", pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            provider = "Test", knowledgeCutoffDate = null,
        ) as SpringAiLlmService
        assertEquals("foo", llm.name)
        assertEquals("Test", llm.provider)
        assertTrue(llm.model is OpenAiChatModel)
    }

    @Test
    fun `custom base url`() {

        // openai-java 4.36.0 (used by Spring AI 2.0) eagerly parses the baseUrl in
        // OpenAIOkHttpClient.build() and routes through AzureUrlCategory.categorizeBaseUrl,
        // which NPEs on URLs without a host. Use a syntactically-valid URL so the factory
        // construction succeeds — the URL doesn't need to be reachable for this test.
        val mf = OpenAiCompatibleModelFactory(
            baseUrl = "http://foobar.example",
            apiKey = null,
            completionsPath = null,
            embeddingsPath = null,
            observationRegistry = mockk(),
            restClientBuilder = restClientBuilder,
        )
        val llm = mf.openAiCompatibleLlm(
            model = "foo", pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            provider = "Test", knowledgeCutoffDate = null,
        ) as SpringAiLlmService
        assertEquals("foo", llm.name)
        assertEquals("Test", llm.provider)
        assertTrue(llm.model is OpenAiChatModel)
    }

    @Test
    fun `factory bakes the service model into the chat model options`() {
        // SpringAiLlmService binds the model configured on the ChatModel onto request options,
        // so the factory must set the selected model there for it to reach the wire.
        val llm = openAiCompatibleLlm(
            model = "gemini-2.5-flash",
            optionsConverter = OpenAiChatOptionsConverter,
        )

        assertEquals("gemini-2.5-flash", llm.model.options.model)
    }

    @Test
    fun `message sender prompt includes the service model`() {
        // Prepare
        val promptSlot = slot<Prompt>()
        val chatModel = mockk<ChatModel> {
            // Mirror the production chat model, which carries the selected model in its options.
            every { options } returns OpenAiChatOptions.builder().model("gemini-2.5-flash").build()
            every { call(capture(promptSlot)) } returns ChatResponse(
                listOf(Generation(AssistantMessage("done")))
            )
        }
        val llm = SpringAiLlmService(
            name = "gemini-2.5-flash",
            provider = "Test",
            chatModel = chatModel,
            optionsConverter = OpenAiChatOptionsConverter,
        )

        // Execute
        llm.createMessageSender(LlmOptions()).call(listOf(UserMessage("Hi")), emptyList())

        // Verify
        assertEquals("gemini-2.5-flash", promptSlot.captured.options.model)
    }

    /**
     * Creates an OpenAI-compatible [SpringAiLlmService] through the production factory path
     * while allowing tests to supply the delegate options converter under test.
     */
    private fun openAiCompatibleLlm(
        model: String,
        optionsConverter: OptionsConverter<*>,
    ): SpringAiLlmService {
        val mf = OpenAiCompatibleModelFactory(
            baseUrl = "http://foobar.example",
            apiKey = null,
            completionsPath = null,
            embeddingsPath = null,
            observationRegistry = mockk(),
            restClientBuilder = restClientBuilder,
        )
        return mf.openAiCompatibleLlm(
            model = model,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            provider = "Test",
            knowledgeCutoffDate = null,
            optionsConverter = optionsConverter,
        ) as SpringAiLlmService
    }

}
