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
package com.embabel.agent.core.support

import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Documents a read-modify-write race on `AbstractAgentProcess._terminationRequest`.
 *
 * These tests fail deterministically against the current unconditional-reset
 * implementation: a consumer reads signal-A, a concurrent writer posts signal-B,
 * then the consumer's unconditional `resetTerminationRequest()` silently clobbers B.
 * They pass once the reset becomes CAS-based (compare-and-reset against the observed signal).
 */
class AbstractAgentProcessTerminationRaceTest {

    private fun newProcess() = ConcurrentAgentProcess(
        id = "term-race-doc",
        agent = SimpleTestAgent,
        processOptions = ProcessOptions(),
        blackboard = InMemoryBlackboard(),
        platformServices = dummyPlatformServices(),
        parentId = null,
        plannerFactory = DefaultPlannerFactory,
    )

    /**
     * Sequential interleave: consumer reads A, another writer posts B,
     * consumer unconditionally resets → B is silently dropped.
     */
    @Test
    fun `sequential - unconditional reset clobbers concurrent termination signal`() {
        val process = newProcess()

        // t=0 — consumer observes signal-A
        process.terminateAction("signal-A")
        val observedByConsumer = process.terminationRequest!!
        assertEquals("signal-A", observedByConsumer.reason)

        // t=1 — another writer overwrites with signal-B
        process.terminateAction("signal-B")

        // t=2 — consumer attempts a CAS reset against the signal it observed (A).
        //       Slot now holds B, so CAS must fail and leave B untouched.
        val wasReset = process.compareAndResetTerminationRequest(observedByConsumer)
        assertFalse(wasReset, "CAS must fail because slot now holds signal-B, not signal-A")

        assertEquals(
            "signal-B",
            process.terminationRequest?.reason,
            "signal-B set after the consumer's read must survive the CAS reset.",
        )
    }

    /**
     * Same race across real threads, sequenced by a CyclicBarrier:
     * consumer reads A → barrier #1 → writer posts B → barrier #2 → consumer resets.
     */
    @Test
    fun `multi-thread - unconditional reset across threads clobbers concurrent signal`() {
        val process = newProcess()
        // Seed the slot: both threads will start from slot = signal-A.
        process.terminateAction("signal-A")

        // Two rendezvous points (each blocks both threads until both arrive).
        // We use them to force the exact interleave we want to prove buggy,
        // without relying on stochastic thread scheduling.
        val afterConsumerRead = CyclicBarrier(2)   // #1: consumer has read; writer may now post B
        val afterExternalWrite = CyclicBarrier(2)  // #2: writer has posted B; consumer may now reset

        // Collect exceptions raised inside workers. Without this, a failed
        // assertion inside pool.submit { ... } would be silently swallowed.
        val errors = CopyOnWriteArrayList<Throwable>()
        val pool = Executors.newFixedThreadPool(2)
        // Lets the main thread wait for both workers to finish.
        val done = CountDownLatch(2)

        // Consumer thread — mimics DefaultToolLoop.checkForActionTerminationSignal:
        // reads the slot, then (later) unconditionally clears it.
        pool.submit {
            try {
                // (A) Read slot → capture signal-A
                val observed = process.terminationRequest!!
                assertEquals("signal-A", observed.reason)

                // (B) Signal "I've read A" and wait for the writer to reach the same point.
                afterConsumerRead.await(5, TimeUnit.SECONDS)

                // (C) Wait until the writer has posted signal-B. At this point the slot = B.
                afterExternalWrite.await(5, TimeUnit.SECONDS)

                // (D) CAS reset against the observed signal (A). Slot holds B,
                //     so CAS must fail and preserve B.
                val wasReset = process.compareAndResetTerminationRequest(observed)
                assertFalse(wasReset, "CAS must fail because slot was overwritten to signal-B")
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                done.countDown()
            }
        }

        // External writer thread — simulates another actor (e.g. UI cancel,
        // timeout guard) posting a new termination signal while the consumer
        // is mid-cycle.
        pool.submit {
            try {
                // (E) Wait for the consumer to finish reading before we overwrite.
                afterConsumerRead.await(5, TimeUnit.SECONDS)

                // (F) Post signal-B — slot transitions A → B.
                process.terminateAction("signal-B")

                // (G) Tell the consumer "B is in the slot, you can now do your reset".
                afterExternalWrite.await(5, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                done.countDown()
            }
        }

        // Wait for both workers. If a barrier times out or a deadlock occurs,
        // this assertion fails fast instead of hanging the test forever.
        assertTrue(done.await(15, TimeUnit.SECONDS), "threads did not finish")
        pool.shutdown()
        // Surface any error that happened inside the workers.
        assertTrue(errors.isEmpty(), "errors: $errors")

        // Expected behaviour: the consumer's CAS reset fails (slot holds B, not A),
        // so signal-B survives and is still observable.
        assertEquals(
            "signal-B",
            process.terminationRequest?.reason,
            "signal-B set by the external writer after the consumer's read must " +
                "not be dropped by the consumer's CAS reset.",
        )
    }
}
