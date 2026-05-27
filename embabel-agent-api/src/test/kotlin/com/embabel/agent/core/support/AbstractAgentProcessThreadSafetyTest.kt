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

import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Usage
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.ai.model.PricingModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Proof that `AbstractAgentProcess._llmInvocations` is not thread-safe.
 *
 * `ConcurrentAgentProcess` is annotated `@ThreadSafe` and executes actions
 * in parallel coroutines. Each action's LLM call reaches the process via:
 *
 *   ToolLoopLlmOperations.recordUsage(...)
 *     -> agentProcess.recordLlmInvocation(...)
 *       -> AbstractAgentProcess._llmInvocations.add(...)
 *
 * `_llmInvocations` is declared as `mutableListOf<LlmInvocation>()`, i.e.
 * a plain `ArrayList`. `ArrayList.add` is not thread-safe — concurrent
 * writers can lose updates, produce null holes in the backing array during
 * resize, or throw `ArrayIndexOutOfBoundsException`.
 *
 * We drive the race through the public `recordLlmInvocation` API rather
 * than by running real actions through the coroutine scheduler: the race
 * lives in the list, not in the scheduler, so going direct keeps the test
 * fast and deterministic.
 *
 * A `CountDownLatch` start-barrier releases every worker thread
 * simultaneously, maximising contention so the failure is reproducible on
 * every run rather than relying on interleaving luck.
 *
 * Expected behaviour:
 *   - FAILS on the current implementation (ArrayList backing).
 *   - PASSES once `_llmInvocations` is backed by a thread-safe list such
 *     as `CopyOnWriteArrayList` (the pattern already used for
 *     `replanRequests` in `ConcurrentAgentProcess`).
 *
 * Observed results on the current buggy implementation
 * (16 threads x 2000 adds = 32 000 expected, ArrayList backing):
 *
 *   Run 1: actual size = 18 782 -> 13 218 entries lost (41.3 % loss)
 *   Run 2: actual size = 19 732 -> 12 268 entries lost (38.3 % loss)
 *
 * No exceptions were thrown during these runs; all the data loss is
 * silent, caused by the non-atomic `elementData[size++] = e` sequence
 * in `ArrayList.add` racing across threads. This means in production,
 * with `process-type=CONCURRENT`, cost/usage/modelsUsed reporting can
 * silently under-count LLM invocations by a similar order of magnitude.
 */
class AbstractAgentProcessThreadSafetyTest {

    @Test
    fun `recordLlmInvocation loses entries under concurrent writers`() {
        val process = ConcurrentAgentProcess(
            id = "race-test",
            agent = SimpleTestAgent,
            processOptions = ProcessOptions(),
            blackboard = InMemoryBlackboard(),
            platformServices = dummyPlatformServices(),
            parentId = null,
            plannerFactory = DefaultPlannerFactory,
        )

        val mockLlm = mockk<LlmService<*>>()
        every { mockLlm.name } returns "mock-llm"
        every { mockLlm.pricingModel } returns PricingModel.ALL_YOU_CAN_EAT

        val threadCount = 16
        val addsPerThread = 2_000
        val expectedTotal = threadCount * addsPerThread

        val startBarrier = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        val failures = CopyOnWriteArrayList<Throwable>()

        repeat(threadCount) {
            pool.submit {
                try {
                    startBarrier.await()
                    repeat(addsPerThread) {
                        process.recordLlmInvocation(
                            LlmInvocation(
                                llmMetadata = mockLlm,
                                usage = Usage(1, 1, null),
                                timestamp = Instant.now(),
                                runningTime = Duration.ZERO,
                            )
                        )
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    done.countDown()
                }
            }
        }

        startBarrier.countDown()
        assertTrue(
            done.await(30, TimeUnit.SECONDS),
            "worker threads did not finish within 30s — possible deadlock",
        )
        pool.shutdown()

        assertTrue(
            failures.isEmpty(),
            "Exceptions thrown from concurrent recordLlmInvocation(): " +
                failures.joinToString("; ") { "${it.javaClass.simpleName}: ${it.message}" },
        )

        val actualSize = process.llmInvocations.size
        assertEquals(
            expectedTotal,
            actualSize,
            "Lost ${expectedTotal - actualSize} of $expectedTotal invocations — " +
                "AbstractAgentProcess._llmInvocations (ArrayList) is not thread-safe " +
                "under ConcurrentAgentProcess execution",
        )
    }
}
