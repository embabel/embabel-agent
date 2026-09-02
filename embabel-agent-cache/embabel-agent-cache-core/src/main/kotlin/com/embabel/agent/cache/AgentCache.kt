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
package com.embabel.agent.cache

import java.time.Duration

/**
 * Optional behaviour an [AgentCacheRegion] may support.
 *
 * Backends differ in what they can guarantee. A region declares what it actually
 * provides through [AgentCacheRegion.capabilities], and consumers that require a
 * guarantee ask for it through [CacheRegionConfig.requiredCapabilities] rather
 * than discovering the shortfall at runtime.
 */
enum class CacheCapability {

    /**
     * [AgentCacheRegion.replace] is atomic with respect to concurrent writers.
     *
     * Backends without this can still implement [AgentCacheRegion.replace] as a
     * read-modify-write, which is adequate for a single writer but permits a lost
     * update when two nodes checkpoint the same process concurrently.
     */
    ATOMIC_COMPARE_AND_SET,

    /**
     * [AgentCacheRegion.index] returns a usable index rather than null.
     */
    SECONDARY_INDEX,

    /**
     * [CacheRegionConfig.timeToLive] is honoured by the backend.
     */
    TIME_TO_LIVE,
}

/**
 * Raised when a provider cannot supply a region meeting the requested capabilities.
 */
class CacheCapabilityException(
    message: String,
) : RuntimeException(message)

/**
 * Requested characteristics of a cache region.
 *
 * @param name unqualified region name; providers may qualify it with a prefix
 * @param timeToLive entry lifetime, or null to retain entries indefinitely
 * @param requiredCapabilities capabilities the consumer cannot function without.
 * A provider unable to meet them must throw [CacheCapabilityException] rather
 * than returning a region that silently degrades.
 */
data class CacheRegionConfig(
    val name: String,
    val timeToLive: Duration? = null,
    val requiredCapabilities: Set<CacheCapability> = emptySet(),
)

/**
 * Contract for integrating a third-party cache or key-value store.
 *
 * This is the single integration point for a backend such as Redis, Hazelcast,
 * or any Spring CacheManager. Implementations are responsible only for storing
 * and retrieving opaque payloads; all agent semantics live in the support layer
 * of this module, so a backend is integrated once and serves every consumer.
 *
 * Implementations must be threadsafe.
 */
interface AgentCacheProvider {

    /**
     * Short name used in logging and in the
     * `embabel.agent.platform.cache.provider` property, for example `redis`.
     */
    val name: String

    /**
     * Obtain the region for the given configuration, creating it if required.
     *
     * Repeated calls with the same [CacheRegionConfig.name] must return a region
     * over the same underlying storage.
     *
     * @throws CacheCapabilityException if [CacheRegionConfig.requiredCapabilities]
     * cannot be met by this backend.
     */
    fun getRegion(config: CacheRegionConfig): AgentCacheRegion

    /**
     * Release provider-level resources. Called on context shutdown.
     */
    fun close() {}
}

/**
 * A named area of storage within a provider, holding opaque versioned payloads.
 *
 * Regions exist so that data with different retention needs can be configured
 * independently: process snapshots are durable, contexts expire, runtime process
 * state is short-lived. See [CacheRegions] for the regions the framework uses.
 */
interface AgentCacheRegion {

    val name: String

    /**
     * What this region actually guarantees. Consumers should branch on this
     * rather than assuming a backend's behaviour.
     */
    val capabilities: Set<CacheCapability>

    fun get(key: String): CachedValue?

    /**
     * Unconditional write. Prefer [replace] where concurrent writers are possible.
     */
    fun put(key: String, value: CachedValue)

    /**
     * Write [value] only if the currently stored version matches [expectedVersion].
     *
     * @param expectedVersion the version the caller believes is stored, or null
     * when the caller believes no entry exists
     * @return true if the write was applied, false if the stored version differed
     */
    fun replace(key: String, expectedVersion: Long?, value: CachedValue): Boolean

    fun remove(key: String)

    fun clear()

    /**
     * A secondary index over the keys in this region, or null when this region
     * does not declare [CacheCapability.SECONDARY_INDEX].
     *
     * Indexes let a consumer answer "which keys relate to X" without scanning,
     * for example finding the child processes of a parent.
     */
    fun index(name: String): AgentCacheIndex? = null
}

/**
 * A set-valued secondary index over the keys of a region.
 *
 * Implementations must tolerate duplicate [add] calls and [remove] of absent
 * members, since callers may retry after a partial failure.
 */
interface AgentCacheIndex {

    val name: String

    fun add(indexKey: String, memberKey: String)

    fun remove(indexKey: String, memberKey: String)

    fun members(indexKey: String): Set<String>
}

/**
 * An opaque, versioned payload stored in a region.
 *
 * The cache layer never interprets [payload]. Callers that need to filter or
 * index without deserializing should promote the relevant fields into
 * [metadata], which backends with native query support may index.
 *
 * @param payload serialized bytes
 * @param version monotonic version used for optimistic concurrency by
 * [AgentCacheRegion.replace]
 * @param contentType media type of [payload], for example `application/json`
 * @param metadata small string-valued attributes describing the payload
 */
data class CachedValue(
    val payload: ByteArray,
    val version: Long,
    val contentType: String,
    val metadata: Map<String, String> = emptyMap(),
) {

    override fun equals(other: Any?): Boolean =
        this === other ||
                other is CachedValue &&
                version == other.version &&
                contentType == other.contentType &&
                payload.contentEquals(other.payload) &&
                metadata == other.metadata

    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}

/**
 * Region names used by the framework.
 *
 * These are part of the configuration surface: users name them when setting
 * per-region TTL or when pre-declaring caches in a backend's own configuration,
 * so they must not change.
 */
object CacheRegions {

    /**
     * Durable agent process checkpoints. Requires compare-and-set; normally no TTL.
     */
    const val AGENT_PROCESS_SNAPSHOTS = "agent-process-snapshots"

    /**
     * Long-lived context state shared across processes.
     */
    const val AGENT_CONTEXTS = "agent-contexts"

    /**
     * Runtime agent process state shared between nodes. Short TTL.
     */
    const val AGENT_PROCESSES_RUNTIME = "agent-processes-runtime"
}
