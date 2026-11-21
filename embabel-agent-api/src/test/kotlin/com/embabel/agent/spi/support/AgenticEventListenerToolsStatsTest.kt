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

import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.event.ToolCallRequestEvent
import com.embabel.agent.api.event.ToolCallResponseEvent
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentProcessRunning
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgenticEventListenerToolsStatsTest {

    private fun newEvent(
        function: String,
        isFailure: Boolean = false,
        runningTime: Duration = Duration.ofMillis(100),
    ): ToolCallResponseEvent {
        val toolCallRequestEvent = ToolCallRequestEvent(
            agentProcess = dummyAgentProcessRunning(evenMoreEvilWizard()),
            action = null,
            tool = function,
            toolGroupMetadata = null,
            toolInput = "{}",
            llmOptions = mockk()
        )
        return ToolCallResponseEvent(
            request = toolCallRequestEvent,
            result = if (isFailure) Result.failure(Exception("fail")) else Result.success("ok"),
            runningTime = runningTime
        )
    }

    @Nested
    inner class BasicStatsTracking {
        @Test
        fun `records calls and failures correctly`() {
            val statsListener = AgenticEventListenerToolsStats()
            statsListener.onProcessEvent(newEvent("toolA"))
            statsListener.onProcessEvent(newEvent("toolA", isFailure = true, runningTime = Duration.ofMillis(200)))
            statsListener.onProcessEvent(newEvent("toolB"))

            val stats = statsListener.toolsStats
            assertEquals(2, stats["toolA"]?.calls)
            assertEquals(1, stats["toolA"]?.failures)
            assertEquals(1, stats["toolB"]?.calls)
            assertEquals(0, stats["toolB"]?.failures)
        }

        @Test
        fun `computes average response time correctly`() {
            val statsListener = AgenticEventListenerToolsStats()
            statsListener.onProcessEvent(newEvent("toolA", runningTime = Duration.ofMillis(100)))
            statsListener.onProcessEvent(newEvent("toolA", runningTime = Duration.ofMillis(300)))
            val avg = statsListener.toolsStats["toolA"]?.averageResponseTime
            assertEquals(200, avg)
        }
    }

    @Nested
    inner class ConcurrentRecording {

        @Test
        fun `counts every call recorded from several threads`() {
            val threads = 8
            val eventsPerThread = 20_000
            val statsListener = AgenticEventListenerToolsStats()
            val events = List(10) { newEvent("toolA", isFailure = it == 0) }
            val errors = CopyOnWriteArrayList<Throwable>()
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(threads)
            repeat(threads) {
                pool.submit {
                    try {
                        start.await()
                        repeat(eventsPerThread) { i -> statsListener.onProcessEvent(events[i % events.size]) }
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
            start.countDown()
            pool.shutdown()

            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not finish")
            assertEquals(emptyList<Throwable>(), errors)
            assertEquals(threads * eventsPerThread, statsListener.toolsStats["toolA"]?.calls)
            assertEquals(threads * eventsPerThread / 10, statsListener.toolsStats["toolA"]?.failures)
        }
    }
}
