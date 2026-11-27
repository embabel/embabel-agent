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

import com.anthropic.models.messages.Usage as AnthropicSdkUsage
import com.embabel.agent.spi.support.springai.SpringAiNativeUsageReader
import com.embabel.common.ai.model.NativeUsage

/**
 * Reads the Anthropic SDK usage of one call: the 5m/1h cache-write split and thinking tokens.
 * Streams carry `MessageDeltaUsage` instead, which has no TTL split, so they fall back to generic counts.
 */
object AnthropicNativeUsageReader : SpringAiNativeUsageReader {
    override fun read(usage: org.springframework.ai.chat.metadata.Usage): NativeUsage? =
        (usage.nativeUsage as? AnthropicSdkUsage)?.toNativeUsage()
}

/** input_tokens excludes cache reads and writes; output_tokens includes thinking. */
internal fun AnthropicSdkUsage.toNativeUsage(): NativeUsage {
    val split = _cacheCreation().asKnown().orElse(null)
    val longWrite = split?._ephemeral1hInputTokens()?.asKnown()?.orElse(null)
    val shortWrite = split?._ephemeral5mInputTokens()?.asKnown()?.orElse(null)
    val totalWrite = _cacheCreationInputTokens().asKnown().orElse(null) ?: 0L
    return NativeUsage(
        inputTokens = Math.toIntExact(inputTokens()),
        outputTokens = Math.toIntExact(outputTokens()),
        cacheReadInputTokens = Math.toIntExact(_cacheReadInputTokens().asKnown().orElse(null) ?: 0L),
        cacheWriteInputTokens = Math.toIntExact(shortWrite ?: (totalWrite - (longWrite ?: 0L)).coerceAtLeast(0L)),
        cacheWriteLongInputTokens = Math.toIntExact(longWrite ?: 0L),
        thinkingTokens = Math.toIntExact(
            _outputTokensDetails().asKnown().orElse(null)?._thinkingTokens()?.asKnown()?.orElse(null) ?: 0L,
        ),
    )
}
