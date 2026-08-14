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
package com.embabel.agent.spi.support.streaming

import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.internal.streaming.StreamingLlmOperationsFactory
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.loggerFor
import org.springframework.ai.chat.model.ChatModel
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects and caches streaming capability.
 *
 * PromptRunner goes through [supportsStreaming] with operations and options, cached by model
 * name. [com.embabel.agent.spi.LlmService.supportsStreaming] goes through the [ChatModel]
 * overload, which runs [StreamingCapabilityVerifier] once per instance.
 */
internal object StreamingCapabilityDetector {
    private val logger = loggerFor<StreamingCapabilityDetector>()
    private val byModelName = ConcurrentHashMap<String, Boolean>()
    private val byChatModel = Collections.synchronizedMap(IdentityHashMap<ChatModel, Boolean>())

    private const val CACHE_MISS_LOG_MESSAGE = "Cache miss for {}, testing streaming capability..."

    /**
     * Tests whether the LLM resolved from the given operations and options supports streaming.
     *
     * Results are cached by model to avoid repeated tests.
     *
     * @param llmOperations The LLM operations instance
     * @param llmOptions Options used to resolve the LLM
     * @return true if streaming is supported, false otherwise
     */
    fun supportsStreaming(llmOperations: LlmOperations, llmOptions: LlmOptions): Boolean {
        // Must be a StreamingLlmOperationsFactory to support streaming
        if (llmOperations !is StreamingLlmOperationsFactory) return false

        // Cache by model (or criteria string)
        val cacheKey = llmOptions.model ?: llmOptions.criteria?.toString() ?: "default"
        return byModelName.computeIfAbsent(cacheKey) {
            logger.debug(CACHE_MISS_LOG_MESSAGE, cacheKey)
            llmOperations.supportsStreaming(llmOptions)
        }
    }

    /**
     * Probes [chatModel] via [StreamingCapabilityVerifier] and caches a definitive answer.
     *
     * A successful probe or [UnsupportedOperationException] is remembered. Other failures
     * (missing key, network) answer false for this call but are not cached.
     */
    fun supportsStreaming(chatModel: ChatModel): Boolean {
        byChatModel[chatModel]?.let { return it }

        return try {
            StreamingCapabilityVerifier.probe(chatModel)
            byChatModel[chatModel] = true
            true
        } catch (_: UnsupportedOperationException) {
            byChatModel[chatModel] = false
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Clears memoized results. For tests only. */
    internal fun clearCache() {
        byModelName.clear()
        byChatModel.clear()
    }
}
