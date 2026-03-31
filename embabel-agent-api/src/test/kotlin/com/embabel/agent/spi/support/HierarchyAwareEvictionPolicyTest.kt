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
package com.embabel.agent.spi.support

import com.embabel.agent.core.AgentProcess
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue

class HierarchyAwareEvictionPolicyTest {

    private lateinit var map: MutableMap<String, AgentProcess>
    private lateinit var accessOrder: ConcurrentLinkedQueue<String>

    @BeforeEach
    fun setUp() {
        map = mutableMapOf()
        accessOrder = ConcurrentLinkedQueue()
    }

    private fun mockRootProcess(id: String, finished: Boolean = true): AgentProcess = mockk {
        every { this@mockk.id } returns id
        every { parentId } returns null
        every { isRootProcess } returns true
        every { this@mockk.finished } returns finished
    }

    private fun mockChildProcess(id: String, parentId: String, finished: Boolean = true): AgentProcess = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.parentId } returns parentId
        every { isRootProcess } returns false
        every { this@mockk.finished } returns finished
    }

    private fun addProcess(process: AgentProcess) {
        map[process.id] = process
        if (process.isRootProcess) {
            accessOrder.offer(process.id)
        }
    }

    @Nested
    inner class BasicEvictionTests {

        @Test
        fun `does not evict when under window size`() {
            val policy = HierarchyAwareEvictionPolicy(windowSize = 3)
            addProcess(mockRootProcess("root-1"))
            addProcess(mockRootProcess("root-2"))

            policy.evictIfNeeded(accessOrder, map)

            assertEquals(2, map.size)
            assertEquals(2, accessOrder.size)
        }

        @Test
        fun `evicts oldest when window size exceeded`() {
            val policy = HierarchyAwareEvictionPolicy(windowSize = 2)
            addProcess(mockRootProcess("root-1"))
            addProcess(mockRootProcess("root-2"))
            addProcess(mockRootProcess("root-3"))

            policy.evictIfNeeded(accessOrder, map)

            assertEquals(2, map.size)
            assertNull(map["root-1"])
            assertNotNull(map["root-2"])
            assertNotNull(map["root-3"])
        }

        @Test
        fun `evicts multiple when many over window size`() {
            val policy = HierarchyAwareEvictionPolicy(windowSize = 1)
            addProcess(mockRootProcess("root-1"))
            addProcess(mockRootProcess("root-2"))
            addProcess(mockRootProcess("root-3"))

            policy.evictIfNeeded(accessOrder, map)

            assertEquals(1, map.size)
            assertNotNull(map["root-3"])
        }
    }

    @Nested
    inner class RunningProcessTests {

        @Test
        fun `does not evict running root process`() {
            val policy = HierarchyAwareEvictionPolicy(windowSize = 1)
            addProcess(mockRootProcess("running-root", finished = false))
            addProcess(mockRootProcess("new-root"))

            policy.evictIfNeeded(accessOrder, map)

            // Both should exist - can't evict running process
            assertEquals(2, map.size)
            assertNotNull(map["running-root"])
            assertNotNull(map["new-root"])
        }

        @Test
        fun `stops eviction at first running process`() {
            val policy = HierarchyAwareEvictionPolicy(windowSize = 1)
            addProcess(mockRootProcess("running-root", finished = false))
            addProcess(mockRootProcess("finished-root"))
            addProcess(mockRootProcess("another-root"))

            policy.evictIfNeeded(accessOrder, map)

            // Cannot evict running-root, so stops there (preserves FIFO order)
            assertEquals(3, map.size)
        }
    }

    @Nested
    inner class HierarchyTests {

        @Test
        fun `evicts entire hierarchy when root evicted`() {
            val policy = HierarchyAwareEvictionPolicy(windowSize = 1)
            addProcess(mockRootProcess("root-1"))
            addProcess(mockChildProcess("child-1", parentId = "root-1"))
            addProcess(mockChildProcess("child-2", parentId = "root-1"))
            addProcess(mockRootProcess("root-2"))

            policy.evictIfNeeded(accessOrder, map)

            // root-1 and all children should be evicted
            assertEquals(1, map.size)
            assertNull(map["root-1"])
            assertNull(map["child-1"])
            assertNull(map["child-2"])
            assertNotNull(map["root-2"])
        }

        @Test
        fun `evicts nested children with grandparent`() {
            val policy = HierarchyAwareEvictionPolicy(windowSize = 1)
            addProcess(mockRootProcess("root"))
            addProcess(mockChildProcess("child", parentId = "root"))
            addProcess(mockChildProcess("grandchild", parentId = "child"))
            addProcess(mockRootProcess("new-root"))

            policy.evictIfNeeded(accessOrder, map)

            // Entire hierarchy evicted
            assertEquals(1, map.size)
            assertNull(map["root"])
            assertNull(map["child"])
            assertNull(map["grandchild"])
            assertNotNull(map["new-root"])
        }

        @Test
        fun `running child prevents hierarchy eviction`() {
            val policy = HierarchyAwareEvictionPolicy(windowSize = 1)
            addProcess(mockRootProcess("root-1"))
            addProcess(mockChildProcess("running-child", parentId = "root-1", finished = false))
            addProcess(mockRootProcess("root-2"))

            policy.evictIfNeeded(accessOrder, map)

            // Hierarchy cannot be evicted due to running child
            assertEquals(3, map.size)
            assertNotNull(map["root-1"])
            assertNotNull(map["running-child"])
            assertNotNull(map["root-2"])
        }

        @Test
        fun `running grandchild prevents hierarchy eviction`() {
            val policy = HierarchyAwareEvictionPolicy(windowSize = 1)
            addProcess(mockRootProcess("root"))
            addProcess(mockChildProcess("child", parentId = "root"))
            addProcess(mockChildProcess("running-grandchild", parentId = "child", finished = false))
            addProcess(mockRootProcess("new-root"))

            policy.evictIfNeeded(accessOrder, map)

            // Hierarchy cannot be evicted due to running grandchild
            assertEquals(4, map.size)
        }
    }
}
