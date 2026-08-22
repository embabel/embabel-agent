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
import com.embabel.common.ai.model.AutoModelSelectionCriteria
import com.embabel.common.ai.model.ByNameModelSelectionCriteria
import com.embabel.common.ai.model.ByRoleModelSelectionCriteria
import com.embabel.common.ai.model.DefaultModelSelectionCriteria
import com.embabel.common.ai.model.FallbackByNameModelSelectionCriteria
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.ai.model.PreResolvedModelSelectionCriteria
import com.embabel.common.ai.model.RandomByNameModelSelectionCriteria
import com.embabel.common.util.loggerFor
import org.springframework.ai.chat.model.ChatModel
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects and caches streaming capability.
 *
 * PromptRunner goes through [supportsStreaming] with operations and options. A definitive yes
 * is cached by model name, and only when the options name one model for the life of the process
 * (see [modelNameCacheKey]); a no is never cached, so a timeout or a missing key cannot pin a
 * name to non-streaming.
 *
 * [com.embabel.agent.spi.LlmService.supportsStreaming] goes through the [ChatModel] overload,
 * which runs [StreamingCapabilityVerifier] once per instance for a definitive answer. That
 * overload is the source of truth: everything the name cache declines to key falls through to
 * it. Entries are weakly held so a per-request BYOK model can be collected.
 */
@InternalStreamingApi
object StreamingCapabilityDetector {
    private val logger = loggerFor<StreamingCapabilityDetector>()
    private val byModelNameCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Definitive streaming answers per [ChatModel] instance. Identity, not equals: two beans
     * wrapping the same model name are probed separately. Keys are weak so a factory that
     * builds a new ChatModel per call does not pin every instance for the life of the process.
     */
    private val byChatModelCache = IdentityWeakMap<ChatModel, Boolean>()

    /**
     * ChatModels that have already logged a non-capability probe failure. Identity-keyed and
     * weak so a keyless BYOK placeholder warns once per instance without retaining it.
     */
    private val warnedChatModels = IdentityWeakMap<ChatModel, Boolean>()

    private const val CACHE_MISS_LOG_MESSAGE = "Cache miss for {}, testing streaming capability..."

    private const val PROBE_FAILED_LOG_MESSAGE =
        "Streaming capability probe for {} failed for a reason unrelated to streaming ({}: {}). " +
            "Reporting no streaming support for this call only; the next call probes again"

    /**
     * Tests whether the LLM resolved from the given operations and options supports streaming.
     *
     * Only a definitive yes is stored, and only when [modelNameCacheKey] identifies a model.
     * A no is re-checked on the next call; if the underlying [ChatModel] already has a cached
     * answer, that lookup is cheap.
     *
     * @param llmOperations The LLM operations instance
     * @param llmOptions Options used to resolve the LLM
     * @return true if streaming is supported, false otherwise
     */
    fun supportsStreaming(llmOperations: LlmOperations, llmOptions: LlmOptions): Boolean {
        if (llmOperations !is StreamingLlmOperationsFactory) return false

        val cacheKey = modelNameCacheKey(llmOptions)
        if (cacheKey != null) {
            byModelNameCache[cacheKey]?.let { return it }
        }

        logger.debug(CACHE_MISS_LOG_MESSAGE, cacheKey ?: llmOptions.criteria)
        val supported = llmOperations.supportsStreaming(llmOptions)
        // Do not store false: computeIfAbsent would pin a timeout or missing key on this name.
        if (supported && cacheKey != null) {
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
            byChatModelCache[chatModel] = true
            true
        } catch (_: UnsupportedOperationException) {
            byChatModelCache[chatModel] = false
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
        byChatModelCache.clear()
        warnedChatModels.clear()
    }

    /**
     * The model name these options cache under, or null when they do not name one model.
     *
     * A name is only a sound key if the same model owns it for as long as the process runs, so
     * [ByNameModelSelectionCriteria] is the only criteria that qualifies. The rest resolve
     * later, or elsewhere, or differently each time:
     *
     * - [PreResolvedModelSelectionCriteria] renders every `withLlmService()` wrapper as
     *   `PreResolvedModelSelectionCriteria(SpringAiLlmService)`, so one BYOK user's yes would
     *   be read as the answer for the next user's model.
     * - [RandomByNameModelSelectionCriteria] resolves to a different member of `names` per call.
     * - [ByRoleModelSelectionCriteria] is keyed here before the role resolves, and resolution
     *   reads the ambient selection context and can land on a per-credential service, so one
     *   role means different models in the same process.
     * - [AutoModelSelectionCriteria] is stable under the default resolver, but
     *   `AutoLlmSelectionCriteriaResolver` exists so applications can vary it.
     * - [FallbackByNameModelSelectionCriteria] and [DefaultModelSelectionCriteria] resolve
     *   against whatever is registered, which a deployment awaiting a key changes after boot.
     *
     * Anything excluded here still avoids repeat probes: it resolves to a [ChatModel], and for
     * a Spring bean that is the same instance every time, so the identity cache answers. Only
     * a model built fresh per request pays a probe, which is what stamping capability on the
     * service would fix.
     *
     * Exhaustive on purpose, with no `else`. A new [ModelSelectionCriteria] then fails to
     * compile until someone chooses a side, rather than joining the cache by default, which is
     * how the pre-resolved case became a bug.
     */
    private fun modelNameCacheKey(llmOptions: LlmOptions): String? =
        when (val criteria = llmOptions.criteria) {
            is ByNameModelSelectionCriteria -> criteria.name
            is PreResolvedModelSelectionCriteria<*>,
            is RandomByNameModelSelectionCriteria,
            is ByRoleModelSelectionCriteria,
            is AutoModelSelectionCriteria,
            is FallbackByNameModelSelectionCriteria,
            is DefaultModelSelectionCriteria -> null
        }

    /**
     * Whether this [chatModel] still needs the non-capability warning.
     *
     * Two threads can both miss and both log once; that is harmless. Later calls see the
     * entry and skip.
     */
    private fun firstWarningFor(chatModel: ChatModel): Boolean {
        if (warnedChatModels[chatModel] != null) return false
        return warnedChatModels.putIfAbsent(chatModel, true) == null
    }
}

/**
 * Identity-keyed map whose keys do not keep the referent alive.
 *
 * [java.util.WeakHashMap] is equals-based, and a copy-on-write [java.util.IdentityHashMap]
 * strongly retains every ChatModel ever probed. This keeps identity lookup and lets a
 * per-request BYOK model be collected.
 */
private class IdentityWeakMap<K : Any, V : Any> {
    private val queue = ReferenceQueue<K>()
    private val map = ConcurrentHashMap<Key<K>, V>()

    operator fun get(key: K): V? {
        purge()
        return map[Key(key, null)]
    }

    operator fun set(key: K, value: V) {
        purge()
        map[Key(key, queue)] = value
    }

    fun putIfAbsent(key: K, value: V): V? {
        purge()
        return map.putIfAbsent(Key(key, queue), value)
    }

    fun clear() {
        map.clear()
        while (queue.poll() != null) {
            // drain
        }
    }

    private fun purge() {
        while (true) {
            @Suppress("UNCHECKED_CAST")
            val stale = queue.poll() as Key<K>? ?: return
            map.remove(stale)
        }
    }

    private class Key<T : Any>(
        referent: T,
        queue: ReferenceQueue<T>?,
    ) : WeakReference<T>(referent, queue) {
        private val identity = System.identityHashCode(referent)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key<*>) return false
            val a = get()
            val b = other.get()
            return a != null && a === b
        }

        override fun hashCode(): Int = identity
    }
}
