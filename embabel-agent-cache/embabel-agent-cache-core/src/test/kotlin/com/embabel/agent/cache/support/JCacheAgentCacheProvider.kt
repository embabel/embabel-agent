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
import com.embabel.agent.cache.CacheRegionConfig
import com.embabel.agent.cache.CachedValue
import java.util.concurrent.ConcurrentHashMap
import javax.cache.Cache
import javax.cache.CacheManager
import javax.cache.configuration.MutableConfiguration

/**
 * Reference [AgentCacheProvider] over a JSR-107 [CacheManager].
 *
 * Intended as a test fixture demonstrating true [CacheCapability.ATOMIC_COMPARE_AND_SET]
 * via [Cache.replace]. Backends such as Ehcache 3, Infinispan, and Hazelcast honour
 * the JCache CAS contract at the API level.
 *
 * Caches are configured with [MutableConfiguration.setStoreByValue] `false` so that
 * [CachedValue] instances are stored by reference — no [java.io.Serializable] requirement.
 * For heap-only use in tests this is correct; a production provider targeting an
 * off-heap or distributed tier would need serialization.
 */
internal class JCacheAgentCacheProvider(
    private val cacheManager: CacheManager,
) : AgentCacheProvider {

    override val name: String = NAME

    private val regions = ConcurrentHashMap<String, AgentCacheRegion>()

    override fun getRegion(config: CacheRegionConfig): AgentCacheRegion =
        regions.computeIfAbsent(config.name) {
            val cache: Cache<String, CachedValue> =
                cacheManager.getCache(config.name, String::class.java, CachedValue::class.java)
                    ?: cacheManager.createCache(
                        config.name,
                        MutableConfiguration<String, CachedValue>()
                            .setTypes(String::class.java, CachedValue::class.java)
                            .setStoreByValue(false),
                    )
            JCacheAgentCacheRegion(cache)
        }

    override fun close() {
        cacheManager.close()
    }

    companion object {
        const val NAME = "jcache"
    }
}

/**
 * [AgentCacheRegion] backed by a JSR-107 [Cache].
 *
 * CAS is delegated to [Cache.replace] which is atomic at the JCache API level.
 * [CachedValue.equals] performs structural comparison (payload bytes, version,
 * contentType, metadata), which is what JCache uses to match the expected value.
 *
 * Secondary index entries are held in an in-process [ConcurrentHashMap] — they
 * are intentionally not in the distributed cache. This is a known limitation of
 * the reference implementation: index updates are not atomic with data writes and
 * are lost on pod restart. A production provider would store index entries in the
 * same distributed tier as data entries.
 */
internal class JCacheAgentCacheRegion(
    private val cache: Cache<String, CachedValue>,
) : AgentCacheRegion {

    override val name: String get() = cache.name

    override val capabilities: Set<CacheCapability> = setOf(
        CacheCapability.ATOMIC_COMPARE_AND_SET,
        CacheCapability.SECONDARY_INDEX,
    )

    override fun get(key: String): CachedValue? = cache.get(key)

    override fun put(key: String, value: CachedValue) {
        cache.put(key, value)
    }

    override fun replace(key: String, expectedVersion: Long?, value: CachedValue): Boolean {
        if (expectedVersion == null) {
            // Atomic insert: succeeds only when the key is absent.
            // JCache Cache.putIfAbsent returns true when inserted, false when already present.
            return cache.putIfAbsent(key, value)
        }
        val current = cache.get(key) ?: return false
        if (current.version != expectedVersion) return false
        // Atomic CAS: JCache compares by equals(), which CachedValue implements structurally.
        return cache.replace(key, current, value)
    }

    override fun remove(key: String) {
        cache.remove(key)
    }

    override fun clear() {
        cache.clear()
    }

    override fun index(name: String): AgentCacheIndex = JCacheAgentCacheIndex(name)

    private val indexData = ConcurrentHashMap<String, MutableSet<String>>()

    private inner class JCacheAgentCacheIndex(
        override val name: String,
    ) : AgentCacheIndex {

        override fun add(indexKey: String, memberKey: String) {
            indexData.computeIfAbsent(entryKey(indexKey)) {
                ConcurrentHashMap.newKeySet()
            }.add(memberKey)
        }

        override fun remove(indexKey: String, memberKey: String) {
            indexData[entryKey(indexKey)]?.remove(memberKey)
        }

        override fun members(indexKey: String): Set<String> =
            indexData[entryKey(indexKey)]?.toSet() ?: emptySet()

        private fun entryKey(indexKey: String): String = "$name:$indexKey"
    }
}
