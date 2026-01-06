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
package com.embabel.agent.api.dsl

import com.embabel.agent.api.common.OperationContext
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ParallelizationTest {

    private val context = mockk<OperationContext>()

    @Test
    fun `parallelMap should handle high concurrency workloads`() {
        val itemCount = 100
        val items = (1..itemCount).toList()
        val concurrencyLevel = 500

        // Track maximum concurrent executions
        val concurrentExecutions = AtomicInteger(0)
        val maxConcurrentExecutions = AtomicInteger(0)

        val results = items.parallelMap(
            context = context,
            concurrencyLevel = concurrencyLevel,
        ) { item ->
            val current = concurrentExecutions.incrementAndGet()
            val max = maxConcurrentExecutions.getAndUpdate { prev -> maxOf(prev, current) }

            // Small delay to ensure overlap
            Thread.sleep(5)

            concurrentExecutions.decrementAndGet()
            item * 2
        }

        // Verify results
        assertEquals(items.size, results.size)
        assertEquals(items.map { it * 2 }, results)

        assertTrue(
            maxConcurrentExecutions.get() <= concurrencyLevel + 5,
            "Expected no more than ${concurrencyLevel + 5} concurrent executions, but got ${maxConcurrentExecutions.get()}"
        )
    }

    @Test
    fun `mapAsync should handle high concurrency workloads`() = runBlocking {
        val itemCount = 1000
        val items = (1..itemCount).toList()
        val concurrencyLevel = 50

        // Track maximum concurrent executions
        val concurrentExecutions = AtomicInteger(0)
        val maxConcurrentExecutions = AtomicInteger(0)

        val results = items.mapAsync(context, concurrencyLevel = concurrencyLevel) { item ->
            val current = concurrentExecutions.incrementAndGet()
            val max = maxConcurrentExecutions.getAndUpdate { prev -> maxOf(prev, current) }

            // Small delay to ensure overlap
            delay(5)

            concurrentExecutions.decrementAndGet()
            item * 2
        }

        // Verify results
        assertEquals(items.size, results.size)
        assertEquals(items.map { it * 2 }, results)

        // Verify concurrency was utilized (should be close to our concurrencyLevel)
        assertTrue(
            maxConcurrentExecutions.get() >= concurrencyLevel / 2,
            "Expected at least ${concurrencyLevel / 2} concurrent executions, but got ${maxConcurrentExecutions.get()}"
        )
        assertTrue(
            maxConcurrentExecutions.get() <= concurrencyLevel + 5,
            "Expected no more than ${concurrencyLevel + 5} concurrent executions, but got ${maxConcurrentExecutions.get()}"
        )
    }

    @Test
    fun `parallelMap should enforce concurrency limits under high load`() {
        val itemCount = 500
        val items = (1..itemCount).toList()
        val concurrencyLevel = 10

        // Use a latch to synchronize all tasks to start at the same time
        val startLatch = CountDownLatch(1)
        val concurrentExecutions = AtomicInteger(0)
        val maxConcurrentExecutions = AtomicInteger(0)
        val random = Random()
        val results = items.parallelMap(
            context, concurrencyLevel = concurrencyLevel,
            dispatcher = Dispatchers.Default
        ) { item ->
            // Wait for the signal to start
            startLatch.await(
                random.nextInt(1, 100).toLong(), TimeUnit.MILLISECONDS
            )

            val current = concurrentExecutions.incrementAndGet()
            maxConcurrentExecutions.updateAndGet { prev -> maxOf(prev, current) }

            // Hold the permit for a bit
            Thread.sleep(random.nextInt(1, 20).toLong())
            concurrentExecutions.decrementAndGet()
            item * 2
        }

        // Release the latch to start all tasks
        startLatch.countDown()

        // Verify results
        assertEquals(items.size, results.size)

        // Verify concurrency was strictly limited
        assertTrue(
            maxConcurrentExecutions.get() <= concurrencyLevel,
            "Expected no more than $concurrencyLevel concurrent executions, but got ${maxConcurrentExecutions.get()}"
        )
    }

    @Test
    fun `mapAsync should maintain result order regardless of completion order`() = runBlocking {
        val items = (1..100).toList()

        val results = items.mapAsync(context, concurrencyLevel = 20) { item ->
            // Make items with even indices take longer to complete
            if (item % 2 == 0) {
                delay(50)
            } else {
                delay(10)
            }
            item * 2
        }

        // Verify results are in the correct order despite different completion times
        assertEquals(items.map { it * 2 }, results)
    }
}
