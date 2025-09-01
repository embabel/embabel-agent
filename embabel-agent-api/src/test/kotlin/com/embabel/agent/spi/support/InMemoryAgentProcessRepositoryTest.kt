/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import com.embabel.agent.config.ProcessRepositoryProperties
import com.embabel.agent.core.AgentProcess
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryAgentProcessRepositoryTest {

    private lateinit var repository: InMemoryAgentProcessRepository

    @BeforeEach
    fun setUp() {
        repository = InMemoryAgentProcessRepository()
    }

    @Test
    fun `save and find agent process`() {
        val process = mockk<AgentProcess> {
            every { id } returns "test-id"
        }

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
        val process = mockk<AgentProcess> {
            every { id } returns "test-id"
        }

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

        // Add processes up to the window size
        val processes = (1..windowSize).map { i ->
            mockk<AgentProcess> {
                every { id } returns "process-$i"
            }
        }

        processes.forEach { repository.save(it) }
        assertEquals(windowSize, repository.size())

        // Verify all processes are findable
        processes.forEach { process ->
            assertNotNull(repository.findById(process.id))
        }

        // Add one more process to trigger eviction
        val extraProcess = mockk<AgentProcess> {
            every { id } returns "extra-process"
        }
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

        // Add many processes at once
        val processes = (1..5).map { i ->
            mockk<AgentProcess> {
                every { id } returns "process-$i"
            }
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

        val process1 = mockk<AgentProcess> {
            every { id } returns "process-1"
        }
        val process2 = mockk<AgentProcess> {
            every { id } returns "process-2"
        }

        repository.save(process1)
        repository.save(process2)
        assertEquals(2, repository.size())

        // Update process1 (should move it to the end of access order)
        val updatedProcess1 = mockk<AgentProcess> {
            every { id } returns "process-1"
        }
        repository.save(updatedProcess1)

        // Should still have 2 processes
        assertEquals(2, repository.size())
        assertNotNull(repository.findById("process-1"))
        assertNotNull(repository.findById("process-2"))

        // Add a third process
        val process3 = mockk<AgentProcess> {
            every { id } returns "process-3"
        }
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
            mockk<AgentProcess> {
                every { id } returns "process-$i"
            }
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
}
