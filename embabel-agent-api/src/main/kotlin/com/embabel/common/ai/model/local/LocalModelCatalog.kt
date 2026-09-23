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
package com.embabel.common.ai.model.local

import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.ConfigurableModelProvider
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.EmbeddingRoleResolver
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelMetadata
import com.embabel.common.ai.model.RoleResolver
import com.embabel.common.util.loggerFor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * What a [LocalModelSource] is serving, cached for long enough that asking is not an HTTP round
 * trip per embedding.
 *
 * Shared by [LocalModelRoleResolver] and [LocalModelEmbeddingRoleResolver] rather than held by
 * each, because the two answer from ONE runner and a runner asked twice per call is the cost this
 * class exists to avoid. They are separate classes only because `RoleResolver.resolve` and
 * `EmbeddingRoleResolver.resolve` take the same arguments and return different types, so one class
 * cannot implement both.
 *
 * Elapsed time here is [System.nanoTime], not a clock: this measures how long ago the runner was
 * asked, which must survive an NTP step mid-interval. [ticker] is a parameter so a test can drive
 * the interval rather than sit through it.
 *
 * @param source the runner to ask
 * @param discovery how stale an answer may be
 * @param ticker monotonic nanosecond source; override only in tests
 */
class LocalModelCatalog(
    private val source: LocalModelSource,
    private val discovery: LocalModelDiscoveryProperties,
    private val ticker: () -> Long = System::nanoTime,
) : LateArrivingModels {

    private val logger = loggerFor<LocalModelCatalog>()

    /** Provider name of the runner behind this catalog. */
    val provider: String get() = source.provider

    /**
     * Declared HERE rather than on the resolvers, because this is the object that knows whether a
     * model can arrive late at all.
     *
     * With discovery off nothing will ever ask the runner, so a role naming an unregistered model
     * under this provider is a typo again and must be fatal again - which is what "off restores the
     * startup-only behaviour" has to mean if it is to mean anything. A resolver cannot make that
     * distinction: it exists either way. See [LateArrivingModels].
     */
    override val lateArrivingProvider: String? get() = source.provider.takeIf { discovery.enabled }

    /**
     * Services built for models this catalog reported, held for the life of the process.
     *
     * Unbounded, unlike the credential caches in [ConfigurableModelProvider], and safely so: the
     * key space is the set of models one local runner serves, which is bounded by that machine's
     * disk rather than by how many users have ever supplied a key.
     */
    private val llmServices = ConcurrentHashMap<String, LlmService<*>>()
    private val embeddingServices = ConcurrentHashMap<String, EmbeddingService>()

    private val snapshot = AtomicReference<Snapshot?>(null)

    private data class Snapshot(
        val models: Set<LocalModel>,
        val takenAt: Long,
    ) {
        fun named(name: String): LocalModel? = models.firstOrNull { it.name == name }
    }

    /**
     * Whether the runner is serving [model] as far as this catalog can currently tell.
     *
     * A HIT is answered from the cached list. A MISS looks again, because a miss is exactly what a
     * model pulled since the last look produces, and making the operator wait out the refresh
     * interval would mean `pull` followed by a request fails once and works on the retry -
     * the outcome this whole mechanism is for. [LocalModelDiscoveryProperties.missRefreshInterval]
     * is what stops a role nothing will ever satisfy from turning every call into a request.
     */
    fun serves(model: String): Boolean = served(model) != null

    /**
     * The entry for [model] if the runner is serving it, or null.
     *
     * The single lookup [serves] and the by-name accessors all go through, so the refresh rules are
     * stated once.
     */
    private fun served(model: String): LocalModel? {
        if (!discovery.enabled) {
            return null
        }
        val current = current()
        current.named(model)?.let { return it }
        if (elapsedSince(current.takenAt) < discovery.missRefreshInterval.toNanos()) {
            return null
        }
        return refresh().named(model)
    }

    /**
     * Everything the runner is serving of this kind, for the platform's model listings.
     *
     * Read from the cached snapshot on the same terms as [serves], and empty when discovery is off
     * or the runner is unreachable - a listing that cannot be taken says nothing rather than
     * failing the call that asked.
     */
    fun servedNames(kind: LocalModelKind): Set<String> {
        if (!discovery.enabled) {
            return emptySet()
        }
        return current().models.filter { it.kind == kind }.map { it.name }.toSet()
    }

    /**
     * A chat service for [model] if the runner is serving it AS a chat model, or null.
     *
     * Kind is decisive here and nowhere else: a caller naming a model has given no other signal for
     * which of the two lists it meant, and answering an embedding request with a chat model - or
     * the reverse - is worse than declining.
     */
    fun llmNamed(model: String): LlmService<*>? =
        served(model)?.takeIf { it.kind == LocalModelKind.CHAT }?.let { llmService(model) }

    /** An embedding service for [model] if the runner is serving it AS an embedding model. */
    fun embeddingNamed(model: String): EmbeddingService? =
        served(model)?.takeIf { it.kind == LocalModelKind.EMBEDDING }?.let { embeddingService(model) }

    /**
     * A chat service for [model], built once and then reused.
     *
     * Read then put rather than `computeIfAbsent`, for the reason the credential caches give:
     * building can reach the runner, and holding a map bin's lock across that would block
     * unrelated lookups. A race costs one redundant build and never a wrong service.
     */
    fun llmService(model: String): LlmService<*>? =
        llmServices[model] ?: source.llmService(model)?.also {
            llmServices[model] = it
            logBuilt("chat", model)
        }

    /** An embedding service for [model], built once and then reused, on the same terms. */
    fun embeddingService(model: String): EmbeddingService? =
        embeddingServices[model] ?: source.embeddingService(model)?.also {
            embeddingServices[model] = it
            logBuilt("embedding", model)
        }

    /**
     * Once per model: the moment a runner's model first became usable in this process, which is
     * what an operator who has just pulled one is looking for in the log.
     */
    private fun logBuilt(kind: String, model: String) {
        logger.info("Built {} service for {} model '{}'", kind, source.provider, model)
    }

    private fun current(): Snapshot =
        snapshot.get()
            ?.takeIf { elapsedSince(it.takenAt) < discovery.refreshInterval.toNanos() }
            ?: refresh()

    /**
     * Ask the runner and keep the answer.
     *
     * Two threads arriving together both ask and the later `set` wins, which costs one duplicate
     * request to a process on the same machine. Serialising them would make every caller wait on
     * whichever one got there first.
     */
    private fun refresh(): Snapshot {
        val models = source.servedModels()
        val taken = Snapshot(models, ticker())
        val previous = snapshot.getAndSet(taken)
        if (previous != null && previous.models != models) {
            logger.info(
                "Locally served {} models changed: {} -> {}",
                source.provider,
                previous.models.map { it.name }.sorted(),
                models.map { it.name }.sorted(),
            )
        }
        return taken
    }

    private fun elapsedSince(nanos: Long): Long = ticker() - nanos
}

/**
 * Declares that a named provider's models may appear AFTER startup.
 *
 * Implemented alongside [RoleResolver] or [EmbeddingRoleResolver], or by a [LocalModelCatalog],
 * whichever object knows. It says nothing about how a role resolves; it answers a different
 * question, which only startup validation asks: is a role naming an unregistered model under this
 * provider a typo, or a model that has not arrived yet?
 *
 * Without it the answer is "typo", and it is fatal - correctly, for a deployment that holds its
 * models, because letting it start moves the failure to whichever unrelated call first asks for that
 * role. A provider whose models are pulled on the host is the exception: `roles.cheapest.docker`
 * naming a model nobody has pulled yet is the ordinary state of an appliance before setup, and
 * refusing to start is refusing to reach the point where the operator could pull it.
 *
 * This is why [ConfigurableModelProviderProperties.embeddingRoles] is not checked at all - every
 * entry there may be for a provider this process cannot serve - and it extends the same tolerance to
 * the ONE case the chat check still treats as fatal: an entry under the same provider the default
 * LLM comes from.
 *
 * Only the PROVIDER-QUALIFIED maps are covered. The flat `llms` and `embedding-services` maps name
 * no provider, so nothing there can be attributed to a runner rather than to a typo - which is the
 * reason a model that may arrive late belongs in a provider column.
 */
interface LateArrivingModels {

    /**
     * Provider whose models may appear after startup, matching [ModelMetadata.provider]. Compared
     * case-insensitively, as provider names are everywhere else.
     *
     * Null excuses nothing, for an implementation that can be configured OUT of late arrival -
     * [LocalModelCatalog] with discovery disabled is the shipped case. Nullable rather than a second
     * boolean, so there is one thing to read and no way to say "excused, provider unknown".
     */
    val lateArrivingProvider: String?
}
