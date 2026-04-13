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

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.internal.streaming.StreamingLlmOperationsFactory
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.loggerFor
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility for detecting and caching streaming capability of LLM services.
 *
 * Not all LLM implementations provide meaningful streaming support. Some may throw
 * UnsupportedOperationException, return empty Flux, or provide stub implementations.
 *
 * This detector caches the results of streaming capability tests (delegated to
 * [LlmService.supportsStreaming]) using the provider and model name as cache key.
 */
internal object StreamingCapabilityDetector {
    private val logger = loggerFor<StreamingCapabilityDetector>()
    private val capabilityCache = ConcurrentHashMap<String, Boolean>()

    private const val CACHE_MISS_LOG_MESSAGE = "Cache miss for {}, testing streaming capability..."

    /**
     * Tests whether the LLM resolved from the given operations and options supports streaming.
     *
     * Results are cached by provider:name to avoid repeated tests.
     *
     * @param llmOperations The LLM operations instance
     * @param llmOptions Options used to resolve the LLM
     * @return true if streaming is supported, false otherwise
     */
    fun supportsStreaming(llmOperations: LlmOperations, llmOptions: LlmOptions): Boolean {
        // Must be a StreamingLlmOperationsFactory to support streaming
        if (llmOperations !is StreamingLlmOperationsFactory) return false

        // Resolve the LLM service
        val llmService = resolveLlmService(llmOperations, llmOptions) ?: return false

        // Cache by provider:name
        val cacheKey = "${llmService.provider}:${llmService.name}"
        return capabilityCache.computeIfAbsent(cacheKey) {
            logger.debug(CACHE_MISS_LOG_MESSAGE, cacheKey)
            llmService.supportsStreaming()
        }
    }

    private fun resolveLlmService(llmOperations: LlmOperations, llmOptions: LlmOptions): LlmService<*>? {
        // Currently only ChatClientLlmOperations can resolve LLM services
        if (llmOperations !is ChatClientLlmOperations) return null

        return llmOperations.getLlm(
            LlmInteraction(
                id = InteractionId("capability-check"),
                llm = llmOptions
            )
        )
    }
}
