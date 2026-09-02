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

import com.embabel.agent.cache.CacheCapability
import com.embabel.agent.cache.CacheCapabilityException
import com.embabel.agent.cache.CacheRegionConfig
import com.embabel.agent.cache.CachedValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.cache.concurrent.ConcurrentMapCacheManager

class SpringCacheAgentCacheProviderTest {

    private val provider = SpringCacheAgentCacheProvider(ConcurrentMapCacheManager())

    private fun region(name: String = "test-region") =
        provider.getRegion(CacheRegionConfig(name))

    private fun value(
        body: String,
        version: Long = 1,
    ) = CachedValue(
        payload = body.toByteArray(),
        version = version,
        contentType = "application/json",
    )

    @Test
    fun `stores and retrieves a value`() {
        val region = region()
        region.put("k", value("hello"))

        assertEquals(value("hello"), region.get("k"))
    }

    @Test
    fun `returns null for an absent key`() {
        assertNull(region().get("nope"))
    }

    @Test
    fun `returns the same region for repeated calls`() {
        assertTrue(region("same") === region("same"))
    }

    @Test
    fun `removes and clears`() {
        val region = region()
        region.put("k", value("v"))
        region.remove("k")
        assertNull(region.get("k"))

        region.put("k2", value("v"))
        region.clear()
        assertNull(region.get("k2"))
    }

    @Nested
    inner class Replace {

        @Test
        fun `applies when the stored version matches`() {
            val region = region("replace-match")
            region.put("k", value("first", version = 1))

            assertTrue(region.replace("k", expectedVersion = 1, value = value("second", version = 2)))
            assertEquals(2L, region.get("k")?.version)
        }

        @Test
        fun `refuses when the stored version differs`() {
            val region = region("replace-stale")
            region.put("k", value("first", version = 5))

            assertFalse(region.replace("k", expectedVersion = 1, value = value("second", version = 2)))
            assertEquals(5L, region.get("k")?.version, "stored value must be untouched")
        }

        @Test
        fun `applies a first write when no entry is expected`() {
            val region = region("replace-create")

            assertTrue(region.replace("k", expectedVersion = null, value = value("first", version = 1)))
            assertNotNull(region.get("k"))
        }

        @Test
        fun `refuses a first write when an entry already exists`() {
            val region = region("replace-conflict")
            region.put("k", value("existing", version = 1))

            assertFalse(region.replace("k", expectedVersion = null, value = value("other", version = 1)))
        }
    }

    @Nested
    inner class Index {

        @Test
        fun `adds and lists members`() {
            val index = region("index-add").index("by-parent")!!
            index.add("parent-1", "child-a")
            index.add("parent-1", "child-b")

            assertEquals(setOf("child-a", "child-b"), index.members("parent-1"))
        }

        @Test
        fun `isolates index keys`() {
            val index = region("index-isolate").index("by-parent")!!
            index.add("parent-1", "child-a")
            index.add("parent-2", "child-b")

            assertEquals(setOf("child-a"), index.members("parent-1"))
            assertEquals(setOf("child-b"), index.members("parent-2"))
        }

        @Test
        fun `is empty for an unknown key`() {
            assertEquals(emptySet<String>(), region("index-empty").index("by-parent")!!.members("nobody"))
        }

        @Test
        fun `tolerates duplicate adds and absent removes`() {
            val index = region("index-idempotent").index("by-parent")!!
            index.add("p", "c")
            index.add("p", "c")
            index.remove("p", "never-added")

            assertEquals(setOf("c"), index.members("p"))
        }

        @Test
        fun `removes members`() {
            val index = region("index-remove").index("by-parent")!!
            index.add("p", "c1")
            index.add("p", "c2")
            index.remove("p", "c1")

            assertEquals(setOf("c2"), index.members("p"))
        }

        @Test
        fun `index entries do not appear as data entries`() {
            val region = region("index-namespace")
            region.index("by-parent")!!.add("p", "c")

            assertNull(region.get("p"), "index entry must not collide with the data keyspace")
        }
    }

    @Nested
    inner class Capabilities {

        @Test
        fun `declares secondary index support only`() {
            assertEquals(setOf(CacheCapability.SECONDARY_INDEX), region("caps").capabilities)
        }

        @Test
        fun `refuses a region requiring atomic compare and set`() {
            val thrown = assertThrows(CacheCapabilityException::class.java) {
                provider.getRegion(
                    CacheRegionConfig(
                        name = "needs-cas",
                        requiredCapabilities = setOf(CacheCapability.ATOMIC_COMPARE_AND_SET),
                    )
                )
            }
            assertTrue(
                thrown.message!!.contains(CacheCapability.ATOMIC_COMPARE_AND_SET.name),
                "message should name the missing capability: ${thrown.message}",
            )
        }

        @Test
        fun `supplies a region requiring only secondary index`() {
            assertNotNull(
                provider.getRegion(
                    CacheRegionConfig(
                        name = "needs-index",
                        requiredCapabilities = setOf(CacheCapability.SECONDARY_INDEX),
                    )
                )
            )
        }
    }
}
