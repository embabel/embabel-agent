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

import com.embabel.agent.spi.config.spring.ProcessRepositoryProperties
import com.embabel.agent.core.AgentProcess
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InMemoryAgentProcessRepositoryTest {

    private lateinit var repository: InMemoryAgentProcessRepository

    @BeforeEach
    fun setUp() {
        repository = InMemoryAgentProcessRepository()
    }

    /**
     * Create a mock root process (no parent).
     */
    private fun mockRootProcess(
        id: String,
        finished: Boolean = true,
    ): AgentProcess = mockk {
        every { this@mockk.id } returns id
        every { parentId } returns null
        every { isRootProcess } returns true
        every { this@mockk.finished } returns finished
    }

    /**
     * Create a mock child process with a parent.
     */
    private fun mockChildProcess(
        id: String,
        parentId: String,
        finished: Boolean = true,
    ): AgentProcess = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.parentId } returns parentId
        every { isRootProcess } returns false
        every { this@mockk.finished } returns finished
    }

    @Test
    fun `save and find agent process`() {
        val process = mockRootProcess("test-id")

        val saved = repository.save(process)
        assertEquals(process, saved)
        assertEquals(1, repository.size())

        val found = repository.findById("test-id")
        assertEquals(process, found)
    }

    @Test
    fun `find non-existent process returns null`() {
        val found = repository.findById("non-existent")
        assertNull(found)
    }

    @Test
    fun `delete agent process`() {
        val process = mockRootProcess("test-id")

        repository.save(process)
        assertEquals(1, repository.size())

        repository.delete(process)
        assertEquals(0, repository.size())
        assertNull(repository.findById("test-id"))
    }

    @Test
    fun `eviction when window size exceeded`() {
        val windowSize = 3
        repository = InMemoryAgentProcessRepository(ProcessRepositoryProperties(windowSize = windowSize))

        // Add finished root processes up to the window size
        val processes = (1..windowSize).map { i ->
            mockRootProcess("process-$i", finished = true)
        }

        processes.forEach { repository.save(it) }
        assertEquals(windowSize, repository.size())

        // Verify all processes are findable
        processes.forEach { process ->
            assertNotNull(repository.findById(process.id))
        }

        // Add one more finished root process to trigger eviction
        val extraProcess = mockRootProcess("extra-process", finished = true)
        repository.save(extraProcess)

        // Repository should still be at window size
        assertEquals(windowSize, repository.size())

        // The first (oldest) process should be evicted
        assertNull(repository.findById("process-1"))

        // The remaining processes should still be there
        assertNotNull(repository.findById("process-2"))
        assertNotNull(repository.findById("process-3"))
        assertNotNull(repository.findById("extra-process"))
    }

    @Test
    fun `multiple evictions when many processes added`() {
        val windowSize = 2
        repository = InMemoryAgentProcessRepository(ProcessRepositoryProperties(windowSize = windowSize))

        // Add many finished root processes at once
        val processes = (1..5).map { i ->
            mockRootProcess("process-$i", finished = true)
        }

        processes.forEach { repository.save(it) }

        // Repository should be at window size
        assertEquals(windowSize, repository.size())

        // Only the last two processes should remain
        assertNull(repository.findById("process-1"))
        assertNull(repository.findById("process-2"))
        assertNull(repository.findById("process-3"))
        assertNotNull(repository.findById("process-4"))
        assertNotNull(repository.findById("process-5"))
    }

    @Test
    fun `updating existing process does not trigger eviction`() {
        val windowSize = 2
        repository = InMemoryAgentProcessRepository(ProcessRepositoryProperties(windowSize = windowSize))

        val process1 = mockRootProcess("process-1", finished = true)
        val process2 = mockRootProcess("process-2", finished = true)

        repository.save(process1)
        repository.save(process2)
        assertEquals(2, repository.size())

        // Update process1 (should move it to the end of access order)
        val updatedProcess1 = mockRootProcess("process-1", finished = true)
        repository.save(updatedProcess1)

        // Should still have 2 processes
        assertEquals(2, repository.size())
        assertNotNull(repository.findById("process-1"))
        assertNotNull(repository.findById("process-2"))

        // Add a third process
        val process3 = mockRootProcess("process-3", finished = true)
        repository.save(process3)

        // process2 should be evicted (it was oldest in access order)
        assertEquals(2, repository.size())
        assertNotNull(repository.findById("process-1"))
        assertNull(repository.findById("process-2"))
        assertNotNull(repository.findById("process-3"))
    }

    @Test
    fun `clear removes all processes`() {
        val processes = (1..5).map { i ->
            mockRootProcess("process-$i")
        }

        processes.forEach { repository.save(it) }
        assertEquals(5, repository.size())

        repository.clear()
        assertEquals(0, repository.size())

        processes.forEach { process ->
            assertNull(repository.findById(process.id))
        }
    }

    @Test
    fun `default window size is 1000`() {
        val defaultRepo = InMemoryAgentProcessRepository()
        val properties = ProcessRepositoryProperties()
        assertEquals(1000, properties.windowSize)
    }

    @Nested
    inner class FindByParentIdTests {

        @Test
        fun `findByParentId returns children`() {
            val parent = mockRootProcess("parent-1")
            val child1 = mockChildProcess("child-1", parentId = "parent-1")
            val child2 = mockChildProcess("child-2", parentId = "parent-1")
            val otherChild = mockChildProcess("other-child", parentId = "other-parent")

            repository.save(parent)
            repository.save(child1)
            repository.save(child2)
            repository.save(otherChild)

            val children = repository.findByParentId("parent-1")
            assertEquals(2, children.size)
            assertTrue(children.any { it.id == "child-1" })
            assertTrue(children.any { it.id == "child-2" })
        }

        @Test
        fun `findByParentId returns empty list when no children`() {
            val parent = mockRootProcess("parent-1")
            repository.save(parent)

            val children = repository.findByParentId("parent-1")
            assertTrue(children.isEmpty())
        }
    }

    @Nested
    inner class HierarchyEvictionTests {

        @Test
        fun `child processes do not count against window size`() {
            val windowSize = 2
            repository = InMemoryAgentProcessRepository(ProcessRepositoryProperties(windowSize = windowSize))

            // Add 2 root processes (at window limit)
            val root1 = mockRootProcess("root-1", finished = true)
            val root2 = mockRootProcess("root-2", finished = true)
            repository.save(root1)
            repository.save(root2)

            // Add multiple children - should NOT trigger eviction
            val child1 = mockChildProcess("child-1", parentId = "root-1", finished = true)
            val child2 = mockChildProcess("child-2", parentId = "root-1", finished = true)
            val child3 = mockChildProcess("child-3", parentId = "root-2", finished = true)
            repository.save(child1)
            repository.save(child2)
            repository.save(child3)

            // All 5 processes should exist (2 roots + 3 children)
            assertEquals(5, repository.size())
            assertNotNull(repository.findById("root-1"))
            assertNotNull(repository.findById("root-2"))
            assertNotNull(repository.findById("child-1"))
            assertNotNull(repository.findById("child-2"))
            assertNotNull(repository.findById("child-3"))
        }

        @Test
        fun `evicting root also evicts all children`() {
            val windowSize = 1
            repository = InMemoryAgentProcessRepository(ProcessRepositoryProperties(windowSize = windowSize))

            // Add root with children
            val root1 = mockRootProcess("root-1", finished = true)
            val child1 = mockChildProcess("child-1", parentId = "root-1", finished = true)
            val child2 = mockChildProcess("child-2", parentId = "root-1", finished = true)
            repository.save(root1)
            repository.save(child1)
            repository.save(child2)

            assertEquals(3, repository.size())

            // Add another root to trigger eviction
            val root2 = mockRootProcess("root-2", finished = true)
            repository.save(root2)

            // root-1 and its children should be evicted
            assertEquals(1, repository.size())
            assertNull(repository.findById("root-1"))
            assertNull(repository.findById("child-1"))
            assertNull(repository.findById("child-2"))
            assertNotNull(repository.findById("root-2"))
        }

        @Test
        fun `running root process is not evicted`() {
            val windowSize = 1
            repository = InMemoryAgentProcessRepository(ProcessRepositoryProperties(windowSize = windowSize))

            // Add running root process
            val runningRoot = mockRootProcess("running-root", finished = false)
            repository.save(runningRoot)

            // Try to add another root - should NOT evict running process
            val newRoot = mockRootProcess("new-root", finished = true)
            repository.save(newRoot)

            // Both should exist (exceeds window size, but can't evict running)
            assertEquals(2, repository.size())
            assertNotNull(repository.findById("running-root"))
            assertNotNull(repository.findById("new-root"))
        }

        @Test
        fun `hierarchy with running child is not evicted`() {
            val windowSize = 1
            repository = InMemoryAgentProcessRepository(ProcessRepositoryProperties(windowSize = windowSize))

            // Add finished root with running child
            val root1 = mockRootProcess("root-1", finished = true)
            val runningChild = mockChildProcess("running-child", parentId = "root-1", finished = false)
            repository.save(root1)
            repository.save(runningChild)

            // Try to add another root - should NOT evict hierarchy with running child
            val root2 = mockRootProcess("root-2", finished = true)
            repository.save(root2)

            // All should exist
            assertEquals(3, repository.size())
            assertNotNull(repository.findById("root-1"))
            assertNotNull(repository.findById("running-child"))
            assertNotNull(repository.findById("root-2"))
        }

        @Test
        fun `nested children evicted with grandparent`() {
            val windowSize = 1
            repository = InMemoryAgentProcessRepository(ProcessRepositoryProperties(windowSize = windowSize))

            // Add root with nested children (grandchildren)
            val root = mockRootProcess("root", finished = true)
            val child = mockChildProcess("child", parentId = "root", finished = true)
            val grandchild = mockChildProcess("grandchild", parentId = "child", finished = true)
            repository.save(root)
            repository.save(child)
            repository.save(grandchild)

            assertEquals(3, repository.size())

            // Add another root to trigger eviction
            val newRoot = mockRootProcess("new-root", finished = true)
            repository.save(newRoot)

            // Entire hierarchy should be evicted
            assertEquals(1, repository.size())
            assertNull(repository.findById("root"))
            assertNull(repository.findById("child"))
            assertNull(repository.findById("grandchild"))
            assertNotNull(repository.findById("new-root"))
        }

        @Test
        fun `running grandchild prevents hierarchy eviction`() {
            val windowSize = 1
            repository = InMemoryAgentProcessRepository(ProcessRepositoryProperties(windowSize = windowSize))

            // Add finished root and child, but running grandchild
            val root = mockRootProcess("root", finished = true)
            val child = mockChildProcess("child", parentId = "root", finished = true)
            val runningGrandchild = mockChildProcess("grandchild", parentId = "child", finished = false)
            repository.save(root)
            repository.save(child)
            repository.save(runningGrandchild)

            // Try to add another root
            val newRoot = mockRootProcess("new-root", finished = true)
            repository.save(newRoot)

            // Hierarchy should NOT be evicted due to running grandchild
            assertEquals(4, repository.size())
            assertNotNull(repository.findById("root"))
            assertNotNull(repository.findById("child"))
            assertNotNull(repository.findById("grandchild"))
            assertNotNull(repository.findById("new-root"))
        }
    }
}
