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
package com.embabel.agent.config.models.gemini

import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.util.ObjectProviders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.beans.factory.support.DefaultListableBeanFactory

/**
 * Gemini's OpenAI-compatible endpoint leaves thinking out of `completion_tokens` but keeps it in
 * `total_tokens`, with no `reasoning_tokens` detail (#2249).
 */
class GeminiThinkingTokenReaderTest {

    @Test
    fun `thinking is what the total holds beyond the prompt and the completion`() {
        // Measured on gemini-3.8-flash: prompt 44, completion 1, total 179.
        assertEquals(179 - 44 - 1, GeminiThinkingTokenReader.read(DefaultUsage(44, 1, 179)))
    }

    @Test
    fun `a total of prompt and completion alone reads no thinking`() {
        assertNull(GeminiThinkingTokenReader.read(DefaultUsage(44, 10, 54)))
    }

    @Test
    fun `a usage without counts reads no thinking`() {
        assertNull(GeminiThinkingTokenReader.read(DefaultUsage(null, null, null)))
    }

    @Test
    fun `every auto-configured Gemini model reads thinking`() {
        val beanFactory = DefaultListableBeanFactory()
        GeminiModelsConfig(
            envBaseUrl = null,
            envApiKey = "test-key",
            observationRegistry = ObjectProviders.empty(),
            properties = GeminiProperties(),
            configurableBeanFactory = beanFactory,
            restClientBuilder = ObjectProviders.empty(),
            webClientBuilder = ObjectProviders.empty(),
        ).geminiModelsInitializer()

        val services = beanFactory.getBeansOfType(SpringAiLlmService::class.java).values
        assertTrue(services.isNotEmpty())
        services.forEach { assertSame(GeminiThinkingTokenReader, it.thinkingTokenReader, it.name) }
    }
}
