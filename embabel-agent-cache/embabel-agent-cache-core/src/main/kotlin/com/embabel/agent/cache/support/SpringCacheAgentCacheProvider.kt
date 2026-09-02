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
package com.embabel.agent.cache.support

import com.embabel.agent.cache.AgentCacheIndex
import com.embabel.agent.cache.AgentCacheProvider
import com.embabel.agent.cache.AgentCacheRegion
import com.embabel.agent.cache.CacheCapability
import com.embabel.agent.cache.CacheCapabilityException
import com.embabel.agent.cache.CacheRegionConfig
import com.embabel.agent.cache.CachedValue
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic [AgentCacheProvider] over any Spring [CacheManager].
 *
 * This adapter exists so that every JSR-107 and Spring-supported backend works
 * without a dedicated module: Caffeine, Ehcache 3, Infinispan, Hazelcast via
 * JCache, and Redis via `RedisCacheManager`. Users configure the backend through
 * the standard `spring.cache.*` properties they already know.
 *
 * ## Guarantees
 *
 * Spring's [Cache] is a cache abstraction, not a store abstraction, so this
 * adapter cannot offer everything the SPI defines:
 *
 * - **No atomic compare-and-set.** [Cache] has `putIfAbsent` but no versioned
 *   CAS, so [AgentCacheRegion.replace] is a read-modify-write guarded by an
 *   in-JVM lock. That is correct for a single node and permits a lost update
 *   across nodes. Use a native provider where that matters.
 * - **Indexes are emulated** as companion entries under a reserved key prefix,
 *   and index updates are not atomic with the data write they accompany.
 * - **TTL is not settable here.** Configure it in the backend's own
 *   configuration, keyed by the region names in
 *   [com.embabel.agent.cache.CacheRegions].
 *
 * Values are stored as [CachedValue] instances, so the configured cache manager
 * must be able to serialize them. That is automatic for in-memory backends; for
 * Redis, configure a JSON serializer.
 */
class SpringCacheAgentCacheProvider(
    private val cacheManager: CacheManager,
) : AgentCacheProvider {

    override val name: String = NAME

    private val regions = ConcurrentHashMap<String, AgentCacheRegion>()

    override fun getRegion(config: CacheRegionConfig): AgentCacheRegion {
        val unsupported = config.requiredCapabilities - SUPPORTED
        if (unsupported.isNotEmpty()) {
            throw CacheCapabilityException(
                "Provider [$NAME] cannot supply region [${config.name}] with required " +
                        "capabilities $unsupported. Supported: $SUPPORTED. Use a native " +
                        "provider such as Redis or Hazelcast."
            )
        }
        return regions.computeIfAbsent(config.name) {
            val cache = cacheManager.getCache(config.name)
                ?: throw CacheCapabilityException(
                    "Cache manager [${cacheManager.javaClass.simpleName}] has no cache named " +
                            "[${config.name}]. Declare it in the backend configuration, or use a " +
                            "cache manager that creates caches on demand."
                )
            SpringCacheAgentCacheRegion(cache)
        }
    }

    companion object {

        const val NAME = "spring-cache"

        private val SUPPORTED = setOf(CacheCapability.SECONDARY_INDEX)
    }
}

/**
 * [AgentCacheRegion] over a single Spring [Cache].
 *
 * Index entries share the underlying cache with data entries and are namespaced
 * by [INDEX_KEY_PREFIX], which is therefore reserved.
 */
internal class SpringCacheAgentCacheRegion(
    private val cache: Cache,
) : AgentCacheRegion {

    override val name: String get() = cache.name

    override val capabilities: Set<CacheCapability> = setOf(CacheCapability.SECONDARY_INDEX)

    /**
     * Per-key locks making read-modify-write correct within this JVM. Cross-node
     * races remain possible; see the provider documentation.
     */
    private val locks = ConcurrentHashMap<String, Any>()

    override fun get(key: String): CachedValue? =
        cache.get(key, CachedValue::class.java)

    override fun put(key: String, value: CachedValue) {
        cache.put(key, value)
    }

    override fun replace(
        key: String,
        expectedVersion: Long?,
        value: CachedValue,
    ): Boolean =
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            if (get(key)?.version != expectedVersion) {
                false
            } else {
                put(key, value)
                true
            }
        }

    override fun remove(key: String) {
        cache.evict(key)
        locks.remove(key)
    }

    override fun clear() {
        cache.clear()
        locks.clear()
    }

    override fun index(name: String): AgentCacheIndex = SpringCacheAgentCacheIndex(name)

    /**
     * Set-valued index emulated as one companion entry per index key.
     */
    private inner class SpringCacheAgentCacheIndex(
        override val name: String,
    ) : AgentCacheIndex {

        override fun add(indexKey: String, memberKey: String) {
            mutate(indexKey) { it + memberKey }
        }

        override fun remove(indexKey: String, memberKey: String) {
            mutate(indexKey) { it - memberKey }
        }

        override fun members(indexKey: String): Set<String> =
            cache.get(entryKey(indexKey), IndexEntry::class.java)?.members ?: emptySet()

        private fun mutate(
            indexKey: String,
            change: (Set<String>) -> Set<String>,
        ) {
            val key = entryKey(indexKey)
            synchronized(locks.computeIfAbsent(key) { Any() }) {
                val updated = change(members(indexKey))
                if (updated.isEmpty()) {
                    cache.evict(key)
                } else {
                    cache.put(key, IndexEntry(updated))
                }
            }
        }

        private fun entryKey(indexKey: String): String = "$INDEX_KEY_PREFIX$name:$indexKey"
    }

    internal data class IndexEntry(
        val members: Set<String>,
    )

    companion object {

        /**
         * Reserved key prefix for emulated index entries. Data keys must not use it.
         */
        const val INDEX_KEY_PREFIX = "embabel.idx:"
    }
}
