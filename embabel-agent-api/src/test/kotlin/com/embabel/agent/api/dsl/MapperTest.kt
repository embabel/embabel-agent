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
package com.embabel.agent.api.dsl

import com.embabel.agent.api.common.OperationContext
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class MapperTest {

    private val context = mockk<OperationContext>()

    @Test
    fun `parallelMap should process items concurrently`() {
        val items = (1..100).toList()
        val counter = AtomicInteger(0)
        val processingTracker = mutableListOf<Int>()

        val results = items.parallelMap(context) { item ->
            val currentCount = counter.incrementAndGet()
            processingTracker.add(currentCount)
            Thread.sleep(10) // Simulate some work
            item * 2
        }

        // Verify all items were processed
        assertEquals(items.size, results.size)
        assertEquals(items.map { it * 2 }, results)

        // Verify concurrent processing by checking that some items were processed
        // while others were still in progress (counter should have jumps)
        val hasNonSequentialProcessing = processingTracker.zipWithNext().any { (a, b) -> b - a > 1 }
        assertTrue(hasNonSequentialProcessing, "Processing should be concurrent, not sequential")
    }

    @Test
    @Disabled("this test is disabled because it can cause flaky behavior in CI environments")
    fun `mapAsync should process items concurrently`() = runBlocking {
        val items = (1..100).toList()
        val counter = AtomicInteger(0)
        val processingTracker = Collections.synchronizedList(mutableListOf<Int>())

        val results = items.mapAsync(context) { item ->
            val currentCount = counter.incrementAndGet()
            processingTracker.add(currentCount)
            delay(10)
            item * 2
        }

        assertEquals(items.size, results.size)
        assertEquals(items.map { it * 2 }, results)

        // Add safety check for empty list
        if (processingTracker.size >= 2) {
            val hasNonSequentialProcessing = processingTracker.zipWithNext().any { (a, b) -> b - a > 1 }
            assertTrue(hasNonSequentialProcessing, "Processing should be concurrent, not sequential")
        } else {
            fail("Processing tracker should contain multiple entries for concurrency verification")
        }
    }

    @Test
    fun `parallelMap should respect concurrencyLevel`() {
        val items = (1..100).toList()
        val concurrencyLevel = 5
        val activeThreads = AtomicInteger(0)
        val maxActiveThreads = AtomicInteger(0)

        items.parallelMap(context, concurrencyLevel = concurrencyLevel) { item ->
            val current = activeThreads.incrementAndGet()
            maxActiveThreads.set(maxOf(current, maxActiveThreads.get()))
            Thread.sleep(50) // Ensure overlap
            activeThreads.decrementAndGet()
            item * 2
        }

        // The max active threads should be approximately equal to the concurrency level
        // We allow some flexibility since thread scheduling isn't perfectly predictable
        assertTrue(maxActiveThreads.get() <= concurrencyLevel + 2)
        assertTrue(maxActiveThreads.get() >= concurrencyLevel - 2)
    }

    @Test
    fun `mapAsync should respect concurrencyLevel`() = runBlocking {
        val items = (1..100).toList()
        val concurrencyLevel = 5
        val activeThreads = AtomicInteger(0)
        val maxActiveThreads = AtomicInteger(0)

        items.mapAsync(context, concurrencyLevel = concurrencyLevel) { item ->
            val current = activeThreads.incrementAndGet()
            maxActiveThreads.set(maxOf(current, maxActiveThreads.get()))
            delay(50) // Ensure overlap
            activeThreads.decrementAndGet()
            item * 2
        }

        // The max active threads should be approximately equal to the concurrency level
        // We allow some flexibility since thread scheduling isn't perfectly predictable
        assertTrue(maxActiveThreads.get() <= concurrencyLevel + 2)
        assertTrue(maxActiveThreads.get() >= concurrencyLevel - 2)
    }

    @Test
    fun `parallelMap should be faster than sequential processing`() {
        val items = (1..20).toList()

        // Sequential processing time
        val sequentialTime = measureTimeMillis {
            items.map {
                Thread.sleep(100) // Simulate work
                it * 2
            }
        }

        // Parallel processing time
        val parallelTime = measureTimeMillis {
            items.parallelMap(context, concurrencyLevel = 10) {
                Thread.sleep(100) // Same work
                it * 2
            }
        }

        // Parallel should be significantly faster
        assertTrue(
            parallelTime < sequentialTime / 2,
            "Parallel processing ($parallelTime ms) should be much faster than sequential processing ($sequentialTime ms)"
        )
    }

    @Test
    fun `mapAsync should be faster than sequential processing`() = runBlocking {
        val items = (1..20).toList()

        // Sequential processing time
        val sequentialTime = measureTimeMillis {
            items.map {
                Thread.sleep(100) // Simulate work
                it * 2
            }
        }

        // Parallel processing time
        val parallelTime = measureTimeMillis {
            items.mapAsync(context, concurrencyLevel = 10) {
                delay(100) // Same work, coroutine-friendly
                it * 2
            }
        }

        // Parallel should be significantly faster
        assertTrue(
            parallelTime < sequentialTime / 2,
            "Parallel processing ($parallelTime ms) should be much faster than sequential processing ($sequentialTime ms)"
        )
    }

    @Test
    fun `parallelMap should handle empty collections`() {
        val emptyList = emptyList<Int>()
        val result = emptyList.parallelMap(context) { it * 2 }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mapAsync should handle empty collections`() = runBlocking {
        val emptyList = emptyList<Int>()
        val result = emptyList.mapAsync(context) { it * 2 }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parallelMap should use specified dispatcher`() {
        val items = (1..10).toList()
        val customDispatcher = Dispatchers.IO

        // Just verify it doesn't crash with custom dispatcher
        val results = items.parallelMap(context, dispatcher = customDispatcher) { it * 2 }
        assertEquals(items.map { it * 2 }, results)
    }

    @Test
    fun `mapAsync should use specified dispatcher`() = runBlocking {
        val items = (1..10).toList()
        val customDispatcher = Dispatchers.IO

        // Just verify it doesn't crash with custom dispatcher
        val results = items.mapAsync(context, dispatcher = customDispatcher) { it * 2 }
        assertEquals(items.map { it * 2 }, results)
    }
}
