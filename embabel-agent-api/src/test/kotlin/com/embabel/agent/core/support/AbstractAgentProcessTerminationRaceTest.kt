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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Documents a read-modify-write race on `AbstractAgentProcess._terminationRequest`.
 *
 * All tests are `@Disabled` — this file is documentation for the follow-up issue.
 * Each test was run without `@Disabled` and verified to fail deterministically on
 * current code; the observed failure is recorded in the `@Disabled` reason.
 * Remove `@Disabled` once the fix lands.
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
    @Disabled("Outstanding race on _terminationRequest. Verified failure: expected <signal-B> but was <null>.")
    fun `sequential - unconditional reset clobbers concurrent termination signal`() {
        val process = newProcess()

        // t=0 — consumer observes signal-A
        process.terminateAction("signal-A")
        val observedByConsumer = process.terminationRequest!!
        assertEquals("signal-A", observedByConsumer.reason)

        // t=1 — another writer overwrites with signal-B
        process.terminateAction("signal-B")

        // t=2 — consumer unconditionally clears the slot
        process.resetTerminationRequest()

        // With a CAS-based consumer: signal-B stays.
        // With the current unconditional reset: signal-B is dropped.
        assertEquals(
            "signal-B",
            process.terminationRequest?.reason,
            "signal-B must survive the consumer's reset. Fix: replace the " +
                "unconditional reset with compareAndResetTerminationRequest(observedByConsumer).",
        )
    }

    /**
     * Same race across real threads, sequenced by a CyclicBarrier:
     * consumer reads A → barrier #1 → writer posts B → barrier #2 → consumer resets.
     */
    @Test
    @Disabled("Outstanding race on _terminationRequest. Verified failure: expected <signal-B> but was <null>.")
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

                // (D) Buggy step: unconditional reset → clobbers signal-B.
                //     With a CAS-based implementation (expected=A), this would be a no-op.
                process.resetTerminationRequest()
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

        // Expected (correct) behaviour: the consumer's stale reset must NOT erase
        // signal-B — the consumer only ever observed signal-A, and signal-B was
        // posted after that. A CAS-based reset would leave B untouched.
        // On buggy code: slot is null → message says "was <null>".
        assertEquals(
            "signal-B",
            process.terminationRequest?.reason,
            "signal-B set by the external writer after the consumer's read must " +
                "not be dropped by the consumer's stale reset.",
        )
    }
}
