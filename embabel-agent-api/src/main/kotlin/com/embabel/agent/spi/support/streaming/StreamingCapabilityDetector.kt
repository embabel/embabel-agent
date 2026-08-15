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
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects and caches streaming capability.
 *
 * PromptRunner goes through [supportsStreaming] with operations and options. A definitive yes
 * is cached by model name; a no is not, so a timeout or missing key cannot pin that name to
 * non-streaming. [com.embabel.agent.spi.LlmService.supportsStreaming] goes through the
 * [ChatModel] overload, which runs [StreamingCapabilityVerifier] once per instance for a
 * definitive answer.
 */
@InternalStreamingApi
object StreamingCapabilityDetector {
    private val logger = loggerFor<StreamingCapabilityDetector>()
    private val byModelNameCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Definitive streaming answers per [ChatModel] instance. Identity, not equals: two beans
     * wrapping the same model name are probed separately.
     */
    @Volatile
    private var byChatModelCache: IdentityHashMap<ChatModel, Boolean> = IdentityHashMap()

    /**
     * ChatModels that have already logged a non-capability probe failure. Identity-keyed so
     * a keyless BYOK placeholder warns once per instance, not on every supportsStreaming() check.
     */
    @Volatile
    private var warnedChatModels: IdentityHashMap<ChatModel, Boolean> = IdentityHashMap()

    private const val CACHE_MISS_LOG_MESSAGE = "Cache miss for {}, testing streaming capability..."

    private val PROBE_FAILED_LOG_MESSAGE = """
        Streaming capability probe for {} failed for a reason unrelated to streaming ({}: {}).
        Reporting no streaming support for this call only; the next call probes again
        """.trimIndent()

    /**
     * Tests whether the LLM resolved from the given operations and options supports streaming.
     *
     * Only a definitive yes is stored under the model (or criteria) key. A no is re-checked
     * on the next call; if the underlying [ChatModel] already has a cached answer, that
     * lookup is cheap.
     *
     * @param llmOperations The LLM operations instance
     * @param llmOptions Options used to resolve the LLM
     * @return true if streaming is supported, false otherwise
     */
    fun supportsStreaming(llmOperations: LlmOperations, llmOptions: LlmOptions): Boolean {
        // Must be a StreamingLlmOperationsFactory to support streaming
        if (llmOperations !is StreamingLlmOperationsFactory) return false

        val cacheKey = llmOptions.model ?: llmOptions.criteria?.toString() ?: "default"
        byModelNameCache[cacheKey]?.let { return it }

        logger.debug(CACHE_MISS_LOG_MESSAGE, cacheKey)
        val supported = llmOperations.supportsStreaming(llmOptions)
        // Do not store false: computeIfAbsent would pin a timeout or missing key on this name.
        if (supported) {
            byModelNameCache[cacheKey] = true
        }
        return supported
    }

    /**
     * Probes [chatModel] via [StreamingCapabilityVerifier] and caches a definitive answer.
     *
     * A successful probe or [UnsupportedOperationException] is remembered. Other failures
     * (missing key, network) answer false for this call but are not cached, and are logged
     * once per instance so a provider outage does not present as a missing capability with
     * no explanation.
     */
    fun supportsStreaming(chatModel: ChatModel): Boolean {
        byChatModelCache[chatModel]?.let { return it }

        // Multiple probes possible on first access, harmless because result is deterministic.
        return try {
            StreamingCapabilityVerifier.probe(chatModel)
            rememberChatModel(chatModel, true)
            true
        } catch (_: UnsupportedOperationException) {
            rememberChatModel(chatModel, false)
            false
        } catch (e: Exception) {
            if (firstWarningFor(chatModel)) {
                // Message and type only: the full stack is noise when this fires on a missing key.
                logger.warn(
                    PROBE_FAILED_LOG_MESSAGE,
                    chatModel.javaClass.simpleName,
                    e.javaClass.simpleName,
                    e.message,
                )
            }
            false
        }
    }

    /** Clears memoized results. For tests only. */
    fun clearCache() {
        byModelNameCache.clear()
        synchronized(this) {
            byChatModelCache = IdentityHashMap()
            warnedChatModels = IdentityHashMap()
        }
    }

    private fun rememberChatModel(chatModel: ChatModel, supported: Boolean) {
        synchronized(this) {
            val next = IdentityHashMap(byChatModelCache)
            next[chatModel] = supported
            byChatModelCache = next
        }
    }

    /**
     * Whether this [chatModel] still needs the non-capability warning.
     *
     * The map is copy-on-write, so the unguarded read is a volatile load. Two threads can both
     * miss and both log once; that is harmless. Later calls see the new map and skip the lock.
     */
    private fun firstWarningFor(chatModel: ChatModel): Boolean {
        if (warnedChatModels.containsKey(chatModel)) return false
        synchronized(this) {
            if (warnedChatModels.containsKey(chatModel)) return false
            val next = IdentityHashMap(warnedChatModels)
            next[chatModel] = true
            warnedChatModels = next
            return true
        }
    }
}
