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
import com.embabel.agent.cache.AgentCacheRegion
import com.embabel.agent.cache.CacheCapability
import com.embabel.agent.cache.CacheCapabilityException
import com.embabel.agent.cache.CacheRegionConfig
import com.embabel.agent.cache.CachedValue
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import com.embabel.agent.spi.persistence.SerializedAgentProcessSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.http.MediaType
import java.time.Instant

class CacheBackedAgentProcessSnapshotStoreTest {

    private val provider = SpringCacheAgentCacheProvider(ConcurrentMapCacheManager())

    private fun store(regionName: String = "snapshots") =
        CacheBackedAgentProcessSnapshotStore(provider.getRegion(CacheRegionConfig(regionName)))

    private fun snapshot(
        processId: String = "p1",
        parentId: String? = null,
        version: Long = 1,
        status: AgentProcessStatusCode = AgentProcessStatusCode.WAITING,
        metadata: Map<String, String> = emptyMap(),
    ) = SerializedAgentProcessSnapshot(
        processId = processId,
        parentId = parentId,
        agentName = "test-agent",
        status = status,
        version = version,
        contentType = MediaType.APPLICATION_JSON,
        payload = """{"blackboard":"$processId"}""".toByteArray(),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-02T03:04:05Z"),
        metadata = metadata,
    )

    @Test
    fun `round trips every header field`() {
        val store = store()
        val original = snapshot(parentId = "parent-1", metadata = mapOf("tenant" to "acme"))

        store.save(original, expectedVersion = null)

        assertEquals(original, store.findLatestByProcessId("p1"))
    }

    @Test
    fun `returns null for an unknown process`() {
        assertNull(store().findLatestByProcessId("missing"))
    }

    @Test
    fun `returns stored metadata describing the write`() {
        val stored = store().save(snapshot(version = 7), expectedVersion = null)

        assertEquals("p1", stored.processId)
        assertEquals(7L, stored.version)
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), stored.updatedAt)
    }

    @Test
    fun `user metadata does not collide with header keys`() {
        val store = store()
        // A caller key that shadows a header name must survive untouched.
        val original = snapshot(metadata = mapOf("processId" to "not-the-real-one"))

        store.save(original, expectedVersion = null)
        val restored = store.findLatestByProcessId("p1")!!

        assertEquals("p1", restored.processId)
        assertEquals(mapOf("processId" to "not-the-real-one"), restored.metadata)
    }

    @Nested
    inner class OptimisticConcurrency {

        @Test
        fun `accepts a sequential update`() {
            val store = store("seq")
            store.save(snapshot(version = 1), expectedVersion = null)

            store.save(snapshot(version = 2), expectedVersion = 1)

            assertEquals(2L, store.findLatestByProcessId("p1")?.version)
        }

        @Test
        fun `rejects a stale update`() {
            val store = store("stale")
            store.save(snapshot(version = 1), expectedVersion = null)
            store.save(snapshot(version = 2), expectedVersion = 1)

            val thrown = assertThrows(AgentProcessPersistenceException::class.java) {
                store.save(snapshot(version = 2), expectedVersion = 1)
            }
            assertTrue(
                thrown.message!!.contains("p1"),
                "message should name the process: ${thrown.message}",
            )
            assertEquals(2L, store.findLatestByProcessId("p1")?.version, "stored value must be untouched")
        }

        @Test
        fun `rejects a create when the process already exists`() {
            val store = store("dup")
            store.save(snapshot(version = 1), expectedVersion = null)

            assertThrows(AgentProcessPersistenceException::class.java) {
                store.save(snapshot(version = 1), expectedVersion = null)
            }
        }
    }

    @Nested
    inner class ParentIndex {

        @Test
        fun `finds children of a parent`() {
            val store = store("children")
            store.save(snapshot(processId = "c1", parentId = "parent"), expectedVersion = null)
            store.save(snapshot(processId = "c2", parentId = "parent"), expectedVersion = null)
            store.save(snapshot(processId = "unrelated"), expectedVersion = null)

            assertEquals(
                setOf("c1", "c2"),
                store.findByParentId("parent").map { it.processId }.toSet(),
            )
        }

        @Test
        fun `is empty for a parent with no children`() {
            assertEquals(emptyList<SerializedAgentProcessSnapshot>(), store().findByParentId("lonely"))
        }

        @Test
        fun `drops a deleted child from the parent index`() {
            val store = store("delete-child")
            store.save(snapshot(processId = "c1", parentId = "parent"), expectedVersion = null)
            store.save(snapshot(processId = "c2", parentId = "parent"), expectedVersion = null)

            store.delete("c1")

            assertNull(store.findLatestByProcessId("c1"))
            assertEquals(listOf("c2"), store.findByParentId("parent").map { it.processId })
        }

        @Test
        fun `delete is idempotent`() {
            val store = store("idempotent-delete")
            store.delete("never-existed")
            store.save(snapshot(), expectedVersion = null)
            store.delete("p1")
            store.delete("p1")

            assertNull(store.findLatestByProcessId("p1"))
        }
    }

    @Test
    fun `refuses a region that cannot index`() {
        val thrown = assertThrows(CacheCapabilityException::class.java) {
            CacheBackedAgentProcessSnapshotStore(IndexlessRegion)
        }
        assertTrue(
            thrown.message!!.contains(CacheCapability.SECONDARY_INDEX.name),
            "message should name the missing capability: ${thrown.message}",
        )
    }

    /**
     * Region declaring no index support, to prove the store fails at construction
     * rather than at the first [CacheBackedAgentProcessSnapshotStore.findByParentId].
     */
    private object IndexlessRegion : AgentCacheRegion {
        override val name = "indexless"
        override val capabilities = emptySet<CacheCapability>()
        override fun get(key: String): CachedValue? = null
        override fun put(key: String, value: CachedValue) = Unit
        override fun replace(key: String, expectedVersion: Long?, value: CachedValue) = false
        override fun remove(key: String) = Unit
        override fun clear() = Unit
        override fun index(name: String): AgentCacheIndex? = null
    }
}
