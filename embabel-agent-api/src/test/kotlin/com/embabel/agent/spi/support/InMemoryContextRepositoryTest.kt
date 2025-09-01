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

import com.embabel.agent.config.ContextRepositoryProperties
import com.embabel.agent.core.Blackboard
import com.embabel.agent.spi.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryContextRepositoryTest {

    private lateinit var repository: InMemoryContextRepository

    @BeforeEach
    fun setUp() {
        repository = InMemoryContextRepository()
    }

    @Test
    fun `save and find context`() {
        val context = mockk<Context> {
            every { id } returns "test-id"
            every { withId(any()) } returns this
        }

        val saved = repository.save(context)
        assertEquals(context, saved)
        assertEquals(1, repository.size())

        val found = repository.findById("test-id")
        assertEquals(context, found)
    }

    @Test
    fun `save context without id assigns new id`() {
        val context = mockk<Context> {
            every { id } returns null
            every { withId(any()) } returns this
        }

        val saved = repository.save(context)
        assertEquals(context, saved)
        assertEquals(1, repository.size())
    }

    @Test
    fun `find non-existent context returns null`() {
        val found = repository.findById("non-existent")
        assertNull(found)
    }

    @Test
    fun `delete context`() {
        val context = mockk<Context> {
            every { id } returns "test-id"
            every { withId(any()) } returns this
        }

        repository.save(context)
        assertEquals(1, repository.size())

        repository.delete(context)
        assertEquals(0, repository.size())
        assertNull(repository.findById("test-id"))
    }

    @Test
    fun `eviction when window size exceeded`() {
        val windowSize = 3
        repository = InMemoryContextRepository(ContextRepositoryProperties(windowSize = windowSize))

        // Add contexts up to the window size
        val contexts = (1..windowSize).map { i ->
            mockk<Context> {
                every { id } returns "context-$i"
                every { withId(any()) } returns this
            }
        }

        contexts.forEach { repository.save(it) }
        assertEquals(windowSize, repository.size())

        // Verify all contexts are findable
        contexts.forEach { context ->
            assertNotNull(repository.findById(context.id!!))
        }

        // Add one more context to trigger eviction
        val extraContext = mockk<Context> {
            every { id } returns "extra-context"
            every { withId(any()) } returns this
        }
        repository.save(extraContext)

        // Repository should still be at window size
        assertEquals(windowSize, repository.size())

        // The first (oldest) context should be evicted
        assertNull(repository.findById("context-1"))

        // The remaining contexts should still be there
        assertNotNull(repository.findById("context-2"))
        assertNotNull(repository.findById("context-3"))
        assertNotNull(repository.findById("extra-context"))
    }

    @Test
    fun `multiple evictions when many contexts added`() {
        val windowSize = 2
        repository = InMemoryContextRepository(ContextRepositoryProperties(windowSize = windowSize))

        // Add many contexts at once
        val contexts = (1..5).map { i ->
            mockk<Context> {
                every { id } returns "context-$i"
                every { withId(any()) } returns this
            }
        }

        contexts.forEach { repository.save(it) }

        // Repository should be at window size
        assertEquals(windowSize, repository.size())

        // Only the last two contexts should remain
        assertNull(repository.findById("context-1"))
        assertNull(repository.findById("context-2"))
        assertNull(repository.findById("context-3"))
        assertNotNull(repository.findById("context-4"))
        assertNotNull(repository.findById("context-5"))
    }

    @Test
    fun `updating existing context does not trigger eviction`() {
        val windowSize = 2
        repository = InMemoryContextRepository(ContextRepositoryProperties(windowSize = windowSize))

        val context1 = mockk<Context> {
            every { id } returns "context-1"
            every { withId(any()) } returns this
        }
        val context2 = mockk<Context> {
            every { id } returns "context-2"
            every { withId(any()) } returns this
        }

        repository.save(context1)
        repository.save(context2)
        assertEquals(2, repository.size())

        // Update context1 (should move it to the end of access order)
        val updatedContext1 = mockk<Context> {
            every { id } returns "context-1"
            every { withId(any()) } returns this
        }
        repository.save(updatedContext1)

        // Should still have 2 contexts
        assertEquals(2, repository.size())
        assertNotNull(repository.findById("context-1"))
        assertNotNull(repository.findById("context-2"))

        // Add a third context
        val context3 = mockk<Context> {
            every { id } returns "context-3"
            every { withId(any()) } returns this
        }
        repository.save(context3)

        // context2 should be evicted (it was oldest in access order)
        assertEquals(2, repository.size())
        assertNotNull(repository.findById("context-1"))
        assertNull(repository.findById("context-2"))
        assertNotNull(repository.findById("context-3"))
    }

    @Test
    fun `clear removes all contexts`() {
        val contexts = (1..5).map { i ->
            mockk<Context> {
                every { id } returns "context-$i"
                every { withId(any()) } returns this
            }
        }

        contexts.forEach { repository.save(it) }
        assertEquals(5, repository.size())

        repository.clear()
        assertEquals(0, repository.size())

        contexts.forEach { context ->
            assertNull(repository.findById(context.id!!))
        }
    }

    @Test
    fun `default window size is 1000`() {
        val defaultRepo = InMemoryContextRepository()
        val properties = ContextRepositoryProperties()
        assertEquals(1000, properties.windowSize)
    }
}
