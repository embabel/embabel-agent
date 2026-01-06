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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class MapperParallelTest {
    private val context = mockk<OperationContext>()

    @Nested
    inner class ParallelMap {
        @Test
        fun `test parallelMap produces correct results`() {
            val list = (1..10).toList()
            val result = list.parallelMap(context) { it * 2 }
            assertEquals(list.map { it * 2 }, result)
        }

        @Test
        fun `test parallelMap is parallel`() {
            val n = 8
            val list = (1..n).toList()
            val started = AtomicInteger(0)
            val maxConcurrent = AtomicInteger(0)
            val concurrencyLevel = 4

            val results = assertTimeoutPreemptively(Duration.ofMillis(300)) {
                list.parallelMap(context, concurrencyLevel) { elt ->
                    val current = started.incrementAndGet()
                    maxConcurrent.updateAndGet { it.coerceAtLeast(current) }
                    Thread.sleep(100)
                    started.decrementAndGet()
                    elt * 2
                }
            }
            assertEquals(
                list.map { it * 2 },
                results.sorted(),
                "Results should match expected values"
            )
            // Should have reached at least the concurrency level
            assert(maxConcurrent.get() >= concurrencyLevel) { "Did not parallelize as expected" }
        }
    }

    @Nested
    inner class MapAsync {
        @Test
        fun `test mapAsync produces correct results`() = kotlinx.coroutines.runBlocking {
            val list = (1..10).toList()
            val result = list.mapAsync(context) { it + 1 }
            assertEquals(list.map { it + 1 }, result)
        }

//        @Test
//        fun `test mapAsync is parallel`() = kotlinx.coroutines.runBlocking {
//            val n = 8
//            val list = (1..n).toList()
//            val started = AtomicInteger(0)
//            val maxConcurrent = AtomicInteger(0)
//            val concurrencyLevel = 4
//            val results = CopyOnWriteArrayList<Int>()
//
//            assertTimeoutPreemptively(Duration.ofMillis(300)) {
//                list.mapAsync(context, concurrencyLevel, Dispatchers.Default) {
//                    val current = started.incrementAndGet()
//                    maxConcurrent.updateAndGet { Math.max(it, current) }
//                    kotlinx.coroutines.delay(100)
//                    results.add(it)
//                    started.decrementAndGet()
//                    it + 1
//                }
//            }
//            assertEquals(list.map { it + 1 }, results.sorted())
//            assert(maxConcurrent.get() >= concurrencyLevel) { "Did not parallelize as expected" }
//        }
    }
}
