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
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Confirms [ExecutorAsyncer] works correctly when backed by a virtual-thread executor
 * (`Executors.newVirtualThreadPerTaskExecutor()`), as used when
 * `spring.threads.virtual.enabled=true`.
 *
 * Answers the review question "Please confirm it works with virtual threads" by proving:
 * 1. work actually runs on a virtual thread,
 * 2. the [AgentProcess] ThreadLocal propagates into the worker block,
 * 3. an exception raised in the block surfaces unchanged through the VT path,
 * 4. the current Micrometer Observation propagates across the thread boundary,
 * 5. the no-semaphore fan-out path does NOT pin carriers: more concurrently blocked
 *    tasks than CPU cores still all make progress (the JVM unmounts blocked VTs),
 * 6. the semaphore path honours maxConcurrency on virtual threads without deadlocking.
 *
 * Cross-thread cleanup against thread *reuse* (the finally{reset()}) is meaningful only
 * with a reused thread, so it is covered on platform threads in
 * [ExecutorAsyncerContextPropagationTest], not here (a VT-per-task executor never reuses).
 */
class ExecutorAsyncerVirtualThreadTest {

    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val asyncer = ExecutorAsyncer(executor)

    @AfterEach
    fun cleanup() {
        AgentProcess.remove()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `work executes on a virtual thread`() {
        val isVirtual = asyncer.async {
            Thread.currentThread().isVirtual
        }.get(5, TimeUnit.SECONDS)

        assertTrue(isVirtual, "block should run on a virtual thread")
    }

    @Test
    fun `AgentProcess propagates to virtual thread worker`() {
        val mockProcess = mock(AgentProcess::class.java)
        AgentProcess.set(mockProcess)

        val result = asyncer.async {
            assertTrue(Thread.currentThread().isVirtual)
            AgentProcess.get()
        }.get(5, TimeUnit.SECONDS)

        assertEquals(mockProcess, result)
    }

    @Test
    fun `propagation works and the original exception surfaces on the virtual thread path`() {
        // A VT-per-task executor never reuses a thread, so a stale-leak check on a *second*
        // task is meaningless here (it would pass even without reset()). Cross-thread cleanup
        // against thread reuse is verified on platform threads in
        // ExecutorAsyncerContextPropagationTest. This test only asserts the VT path itself:
        // the process propagates into the block, and the block's own exception is what surfaces.
        val mockProcess = mock(AgentProcess::class.java)
        AgentProcess.set(mockProcess)

        val seenInsideBlock = AtomicInteger(0)
        val failing = asyncer.async {
            assertTrue(Thread.currentThread().isVirtual)
            seenInsideBlock.set(if (AgentProcess.get() === mockProcess) 1 else 0)
            throw IllegalStateException("boom")
        }

        val cause = runCatching { failing.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertEquals(1, seenInsideBlock.get(), "process must propagate into the worker block")
        // ExecutorAsyncer's finally{reset()} must not swallow or replace the real failure.
        assertTrue(cause?.cause is IllegalStateException, "original exception should surface, got $cause")
    }

    @Test
    fun `current Observation propagates to virtual thread worker`() {
        val registry = ObservationRegistry.create().apply {
            // A handler is required for the observation to become "current" (scoped).
            observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
            })
        }
        // Tell the ServiceLoader-registered accessor which registry to capture/restore.
        ObservationThreadLocalAccessor.getInstance().observationRegistry = registry
        try {
            val parent = Observation.start("parent", registry)
            parent.openScope().use {
                val seenOnWorker = asyncer.async {
                    assertTrue(Thread.currentThread().isVirtual)
                    registry.currentObservation
                }.get(5, TimeUnit.SECONDS)

                assertEquals(parent, seenOnWorker, "observation should propagate so spans nest across threads")
            }
            parent.stop()
        } finally {
            ObservationThreadLocalAccessor.getInstance().observationRegistry = ObservationRegistry.NOOP
        }
    }

    @Test
    fun `fan-out path does not pin carriers - more blocked tasks than CPU cores all run at once`() {
        // maxConcurrency >= items.size takes ExecutorAsyncer's no-semaphore branch: every item
        // is submitted via async()/supplyAsync on the VT executor. We launch far more tasks
        // than CPU cores and make each block until ALL have started. This can only complete if
        // a blocked virtual thread unmounts its carrier (no pinning); if carriers were pinned,
        // fewer than `taskCount` tasks could be live at once, the latch would never reach zero,
        // and the preemptive timeout would fire.
        val taskCount = Runtime.getRuntime().availableProcessors() * 8 + 16
        val allStarted = CountDownLatch(taskCount)

        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val results = asyncer.parallelMap(
                items = (1..taskCount).toList(),
                maxConcurrency = taskCount, // no-semaphore branch: all run, then block together
            ) { item ->
                assertTrue(Thread.currentThread().isVirtual)
                allStarted.countDown()
                assertTrue(allStarted.await(8, TimeUnit.SECONDS), "carrier appears pinned")
                item
            }
            assertEquals(taskCount, results.size)
        }
    }

    @Test
    fun `parallelMap honours maxConcurrency on virtual threads`() {
        val maxConcurrency = 3
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)

        val results = asyncer.parallelMap((1..30).toList(), maxConcurrency) { item ->
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                Thread.sleep(10)
                item * 2
            } finally {
                inFlight.decrementAndGet()
            }
        }

        assertEquals((1..30).map { it * 2 }, results)
        assertTrue(peak.get() <= maxConcurrency, "observed ${peak.get()} concurrent > $maxConcurrency")
    }
}
