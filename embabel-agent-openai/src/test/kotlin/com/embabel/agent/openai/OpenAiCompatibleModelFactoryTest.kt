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

import com.embabel.common.ai.model.PricingModel
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import java.util.function.Supplier

class OpenAiCompatibleModelFactoryTest {

    private val requestFactory = mockk<ObjectProvider<ClientHttpRequestFactory>> {
        every { getIfAvailable(any<Supplier<ClientHttpRequestFactory>>()) } returns SimpleClientHttpRequestFactory()
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
            requestFactory = requestFactory,
        )
        val llm = mf.openAiCompatibleLlm(
            model = "foo", pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            provider = "Test", knowledgeCutoffDate = null,
        )
        assertEquals("foo", llm.name)
        assertEquals("Test", llm.provider)
        assertTrue(llm.model is OpenAiChatModel)
    }

    @Test
    fun `custom base url`() {

        val mf = OpenAiCompatibleModelFactory(
            baseUrl = "foobar",
            apiKey = null,
            completionsPath = null,
            embeddingsPath = null,
            observationRegistry = mockk(),
            requestFactory = requestFactory,
        )
        val llm = mf.openAiCompatibleLlm(
            model = "foo", pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            provider = "Test", knowledgeCutoffDate = null,
        )
        assertEquals("foo", llm.name)
        assertEquals("Test", llm.provider)
        assertTrue(llm.model is OpenAiChatModel)
    }

}
