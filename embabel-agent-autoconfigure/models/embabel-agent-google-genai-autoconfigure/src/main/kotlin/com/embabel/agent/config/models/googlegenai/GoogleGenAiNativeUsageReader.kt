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

import com.embabel.agent.spi.support.springai.SpringAiNativeUsageReader
import com.embabel.common.ai.model.NativeUsage
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage

/**
 * Reads Gemini usage of one call. Prompt tokens include cached content; tool-use prompt tokens are
 * billed as input; thoughts are billed as output but sit outside Spring AI's completion count.
 * Spring AI's own merge turns it into a plain usage, which falls back to generic counts.
 */
object GoogleGenAiNativeUsageReader : SpringAiNativeUsageReader {
    override fun read(usage: org.springframework.ai.chat.metadata.Usage): NativeUsage? {
        if (usage !is GoogleGenAiUsage) return null
        val cached = usage.cachedContentTokenCount ?: 0
        val thoughts = usage.thoughtsTokenCount ?: 0
        return NativeUsage(
            inputTokens = ((usage.promptTokens ?: 0) - cached).coerceAtLeast(0) + (usage.toolUsePromptTokenCount ?: 0),
            outputTokens = (usage.completionTokens ?: 0) + thoughts,
            cacheReadInputTokens = cached,
            thinkingTokens = thoughts,
        )
    }
}
