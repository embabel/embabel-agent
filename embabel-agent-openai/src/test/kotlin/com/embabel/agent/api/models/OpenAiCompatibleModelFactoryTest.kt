/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.models

import com.embabel.common.ai.model.PricingModel
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.openai.OpenAiChatModel

class OpenAiCompatibleModelFactoryTest {

    @Test
    fun `default base url`() {
        val mf = OpenAiCompatibleModelFactory(
            baseUrl = null,
            apiKey = null,
            completionsPath = null,
            embeddingsPath = null,
            observationRegistry = mockk(),
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
