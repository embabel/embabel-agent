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
package com.embabel.agent.config.models.googlegenai

import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.support.springai.toEmbabelUsage
import com.google.genai.types.GenerateContentResponseUsageMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage

/**
 * Gemini reports its thinking in `thoughtsTokenCount`, apart from `candidatesTokenCount` (#2249).
 */
class GoogleGenAiThinkingTokenReaderTest {

    private fun gemini(thoughts: Int?, toolUsePrompt: Int = 0) = GoogleGenAiUsage.from(
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(60)
            .candidatesTokenCount(10)
            .apply { thoughts?.let { thoughtsTokenCount(it) } }
            .toolUsePromptTokenCount(toolUsePrompt)
            .totalTokenCount(60 + 10 + (thoughts ?: 0) + toolUsePrompt)
            .build()
    )

    @Test
    fun `reads Gemini's thoughts token count`() {
        assertEquals(30, GoogleGenAiThinkingTokenReader.read(gemini(30)))
    }

    @Test
    fun `tool-use prompt tokens in the total are not taken for thinking`() {
        assertEquals(30, GoogleGenAiThinkingTokenReader.read(gemini(30, toolUsePrompt = 500)))
    }

    @Test
    fun `no thinking reads none`() {
        assertNull(GoogleGenAiThinkingTokenReader.read(gemini(null)))
    }

    @Test
    fun `a usage that is not Gemini's reads none`() {
        assertNull(GoogleGenAiThinkingTokenReader.read(DefaultUsage(100, 50, 400)))
    }

    /** Fails if Spring AI starts counting thoughts in completionTokens: the reader would then count them twice. */
    @Test
    fun `usage matches Gemini's own total`() {
        val gemini = GoogleGenAiUsage.from(
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(60).candidatesTokenCount(1).thoughtsTokenCount(95).totalTokenCount(156)
                .build()
        )

        val usage = gemini.toEmbabelUsage(thinkingTokens = GoogleGenAiThinkingTokenReader.read(gemini))

        assertEquals(1 + 95, usage.completionTokens)
        assertEquals(gemini.totalTokens, usage.totalTokens)
    }

    @Test
    fun `every auto-configured Gemini model reads thinking`() {
        googleGenAiRunner().run { context ->
            val services = context.getBeansOfType(SpringAiLlmService::class.java).values
            assertTrue(services.isNotEmpty())
            services.forEach { assertSame(GoogleGenAiThinkingTokenReader, it.thinkingTokenReader, it.name) }
        }
    }
}
