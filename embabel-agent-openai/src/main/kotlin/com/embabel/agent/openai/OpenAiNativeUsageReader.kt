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

import com.embabel.agent.spi.support.springai.SpringAiNativeUsageReader
import com.embabel.common.ai.model.NativeUsage
import com.openai.models.completions.CompletionUsage
import com.openai.models.responses.ResponseUsage

/**
 * Reads OpenAI Chat Completions and Responses usage of one call: cache writes and reasoning tokens,
 * which Spring AI's generic usage drops. Input counts include cached and written tokens.
 * ponytail: 12h cache-write TTL split is not read (no typed SDK accessor); all writes price at the short-TTL rate.
 */
object OpenAiNativeUsageReader : SpringAiNativeUsageReader {

    override fun read(usage: org.springframework.ai.chat.metadata.Usage): NativeUsage? =
        when (val native = usage.nativeUsage) {
            is CompletionUsage -> {
                val details = native._promptTokensDetails().asKnown().orElse(null)
                of(
                    input = native.promptTokens(),
                    output = native.completionTokens(),
                    cacheRead = details?._cachedTokens()?.asKnown()?.orElse(null),
                    cacheWrite = details?._cacheWriteTokens()?.asKnown()?.orElse(null),
                    reasoning = native._completionTokensDetails().asKnown().orElse(null)
                        ?._reasoningTokens()?.asKnown()?.orElse(null),
                )
            }
            is ResponseUsage -> {
                val details = native._inputTokensDetails().asKnown().orElse(null)
                of(
                    input = native.inputTokens(),
                    output = native.outputTokens(),
                    cacheRead = details?._cachedTokens()?.asKnown()?.orElse(null),
                    cacheWrite = details?._cacheWriteTokens()?.asKnown()?.orElse(null),
                    reasoning = native._outputTokensDetails().asKnown().orElse(null)
                        ?._reasoningTokens()?.asKnown()?.orElse(null),
                )
            }
            else -> null
        }

    private fun of(input: Long, output: Long, cacheRead: Long?, cacheWrite: Long?, reasoning: Long?): NativeUsage {
        val read = cacheRead ?: 0L
        val write = cacheWrite ?: 0L
        return NativeUsage(
            inputTokens = Math.toIntExact((input - read - write).coerceAtLeast(0)),
            outputTokens = Math.toIntExact(output),
            cacheReadInputTokens = Math.toIntExact(read),
            cacheWriteInputTokens = Math.toIntExact(write),
            thinkingTokens = Math.toIntExact(reasoning ?: 0L),
        )
    }
}
