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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.Usage
import com.embabel.agent.core.support.safelyGetTools
import com.embabel.agent.core.support.safelyGetToolsFrom
import org.springframework.ai.tool.ToolCallback

/**
 * Extract tools and convert to Spring AI ToolCallbacks.
 * Internal use only - external code should use [safelyGetTools] and convert at the Spring AI boundary.
 */
internal fun safelyGetToolCallbacks(instances: Collection<ToolObject>): List<ToolCallback> =
    safelyGetTools(instances).map { it.toSpringToolCallback() }

/**
 * Extract tools from a single ToolObject and convert to Spring AI ToolCallbacks.
 * Internal use only - external code should use [safelyGetToolsFrom].
 */
internal fun safelyGetToolCallbacksFrom(toolObject: ToolObject): List<ToolCallback> =
    safelyGetToolsFrom(toolObject).map { it.toSpringToolCallback() }

/**
 * Spring AI does not define whether promptTokens includes cache tokens:
 * OpenAI counts cached reads inside it, Anthropic reports reads and writes on top of it.
 * @param promptTokensExcludeCache true when cache tokens are reported on top of promptTokens
 */
@JvmOverloads
fun org.springframework.ai.chat.metadata.Usage.toEmbabelUsage(promptTokensExcludeCache: Boolean = false): Usage {
    val cacheRead = cacheReadInputTokens?.let { Math.toIntExact(it) }
    val cacheWrite = cacheWriteInputTokens?.let { Math.toIntExact(it) }
    return Usage(
        promptTokens = if (promptTokensExcludeCache) {
            (promptTokens ?: 0) + (cacheRead ?: 0) + (cacheWrite ?: 0)
        } else {
            promptTokens
        },
        completionTokens = completionTokens,
        nativeUsage = nativeUsage,
        cacheReadInputTokens = cacheRead,
        cacheWriteInputTokens = cacheWrite,
    )
}

/**
 * [toEmbabelUsage] with the provider's own counts. A separate overload without defaults, so the
 * released `toEmbabelUsage` and its `toEmbabelUsage$default` bridge keep their JVM descriptors.
 * @param nativeUsageReader reads the provider's own counts; when it does, they set every count
 */
fun org.springframework.ai.chat.metadata.Usage.toEmbabelUsage(
    promptTokensExcludeCache: Boolean,
    nativeUsageReader: SpringAiNativeUsageReader,
): Usage {
    val native = nativeUsageReader.read(this) ?: return toEmbabelUsage(promptTokensExcludeCache)
    val cacheRead = cacheReadInputTokens?.let { Math.toIntExact(it) }
    val cacheWrite = cacheWriteInputTokens?.let { Math.toIntExact(it) }
    return Usage(
        promptTokens = native.promptTokens,
        completionTokens = native.outputTokens,
        nativeUsage = nativeUsage,
        cacheReadInputTokens = native.cacheReadInputTokens.takeIf { it > 0 } ?: cacheRead,
        cacheWriteInputTokens = (native.cacheWriteInputTokens + native.cacheWriteLongInputTokens).takeIf { it > 0 }
            ?: cacheWrite,
        billableUsage = native,
    )
}
