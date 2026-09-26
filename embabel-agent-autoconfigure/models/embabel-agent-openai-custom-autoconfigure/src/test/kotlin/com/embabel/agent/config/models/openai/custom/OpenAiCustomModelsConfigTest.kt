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
package com.embabel.agent.config.models.openai.custom

import com.embabel.agent.openai.withOpenAiReasoningEffort
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.LlmOptionsProperties
import com.embabel.common.util.ObjectProviders
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.config.ConfigurableBeanFactory

class OpenAiCustomModelsConfigTest {

    @Test
    fun `custom OpenAI-compatible models never receive an OpenAI reasoning effort`() {
        val registered = mutableMapOf<String, Any>()
        val beanFactory = mockk<ConfigurableBeanFactory>()
        every { beanFactory.registerSingleton(any(), any()) } answers { registered[firstArg()] = secondArg() }

        OpenAiCustomModelsConfig(
            envBaseUrl = "http://compatible.example",
            envApiKey = "test-key",
            envCustomModels = "compatible-model",
            envCompletionsPath = null,
            envEmbeddingsPath = null,
            observationRegistry = ObjectProviders.empty(),
            properties = OpenAiCustomProperties(),
            llmOptionsProperties = LlmOptionsProperties(),
            configurableBeanFactory = beanFactory,
            restClientBuilder = ObjectProviders.empty(),
            webClientBuilder = ObjectProviders.empty(),
        ).openAiCustomModelsInitializer()

        val llm = registered.getValue("compatible-model") as SpringAiLlmService
        val options = LlmOptions().withOpenAiReasoningEffort("low")
        assertNull((llm.optionsConverter.convertOptions(options, llm.name) as OpenAiChatOptions).reasoningEffort)
    }
}
