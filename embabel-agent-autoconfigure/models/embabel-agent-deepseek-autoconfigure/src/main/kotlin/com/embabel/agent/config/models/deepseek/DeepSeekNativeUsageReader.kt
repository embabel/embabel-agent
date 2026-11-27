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
package com.embabel.agent.config.models.deepseek

import com.embabel.agent.spi.support.springai.SpringAiNativeUsageReader
import com.embabel.common.ai.model.NativeUsage
import org.springframework.ai.deepseek.api.DeepSeekApi

/** Reads DeepSeek's cache hits, which Spring AI parses but never maps. Prompt tokens include them. */
internal object DeepSeekNativeUsageReader : SpringAiNativeUsageReader {
    override fun read(usage: org.springframework.ai.chat.metadata.Usage): NativeUsage? {
        val native = usage.nativeUsage as? DeepSeekApi.Usage ?: return null
        val cached = native.promptTokensDetails()?.cachedTokens() ?: 0
        return NativeUsage(
            inputTokens = ((native.promptTokens() ?: 0) - cached).coerceAtLeast(0),
            outputTokens = native.completionTokens() ?: 0,
            cacheReadInputTokens = cached,
        )
    }
}
