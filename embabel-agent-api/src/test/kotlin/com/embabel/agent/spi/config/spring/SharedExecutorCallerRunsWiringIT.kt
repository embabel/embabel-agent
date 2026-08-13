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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcess.Companion.withCurrent
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The caller-thread case wired the way a deployment reaches it, rather than by handing an
 * [Executor] straight to the asyncer.
 *
 * [com.embabel.agent.spi.support.ExecutorAsyncerCallerThreadTest] proves the propagation
 * behaviour on an executor it constructs itself. That leaves one thing unproven: that a
 * deployment can actually ARRIVE at that executor through configuration. Reaching it needs
 * `embabel.agent.platform.threading.shared=true` AND an application executor that can run a
 * task on the submitting thread - two settings in different places, neither of which mentions
 * the other. This boots the real [AsyncConfiguration] against a real
 * [ThreadPoolExecutor.CallerRunsPolicy] pool and saturates it, so the wiring and the load
 * shape are covered together.
 *
 * Runs with the IT suite, which surefire excludes from the normal build:
 * `mvn -Dtest='*IT,!LLMOllama*IT' -Dsurefire.failIfNoSpecifiedTests=false test`. Needs no LLM
 * keys, so it is safe in any environment.
 */
@Timeout(60)
class SharedExecutorCallerRunsWiringIT {

    /** One worker, queue of one: the third task submitted has nowhere to go but the caller. */
    @Configuration
    @EnableConfigurationProperties(AgentPlatformProperties::class)
    class SaturatedAppExecutorConfiguration {

        @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME, destroyMethod = "shutdownNow")
        fun applicationTaskExecutor(): ThreadPoolExecutor =
            ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(1),
                ThreadPoolExecutor.CallerRunsPolicy(),
            )
    }

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(SaturatedAppExecutorConfiguration::class.java, AsyncConfiguration::class.java)

    @AfterEach
    fun cleanup() {
        AgentProcess.remove()
    }

    @Test
    fun `a saturated shared pool runs the overflow on the caller and leaves its process intact`() {
        contextRunner
            .withPropertyValues("embabel.agent.platform.threading.shared=true")
            .run { context ->
                val asyncer = context.getBean(Asyncer::class.java)
                val release = CountDownLatch(1)
                val ranOnCaller = AtomicReference(false)
                val outer = mockk<AgentProcess>()

                try {
                    outer.withCurrent {
                        val callerThread = Thread.currentThread()
                        // Occupy the single worker, then fill the single queue slot.
                        val blocking = asyncer.async { release.await(30, TimeUnit.SECONDS) }
                        val queued = asyncer.async { "queued" }
                        // Nowhere left to put this one, so the caller runs it itself.
                        val overflow = asyncer.async {
                            ranOnCaller.set(Thread.currentThread() === callerThread)
                            AgentProcess.get()
                        }

                        assertSame(outer, overflow.get(30, TimeUnit.SECONDS), "the task must see the process")
                        assertTrue(
                            ranOnCaller.get(),
                            "precondition: the shared pool must have overflowed onto the submitting thread",
                        )
                        assertSame(
                            outer, AgentProcess.get(),
                            "the caller must still hold its process after the overflow task returns",
                        )

                        release.countDown()
                        blocking.get(30, TimeUnit.SECONDS)
                        queued.get(30, TimeUnit.SECONDS)
                    }
                } finally {
                    release.countDown()
                }
            }
    }

    /**
     * The same wiring, driving the primitive an action actually fans out with.
     *
     * `parallelMap` is how `OperationContext.parallelMap` and parallel actions reach the executor,
     * and it fails differently from a single `async`: every item goes through the same path, so a
     * clear on the way out empties the submitting thread partway through its OWN fan-out. Later
     * items then run with no process while earlier ones succeeded - a partly correct result from
     * one call, with nothing thrown.
     *
     * More items than the pool can hold, so some are certain to run on the caller.
     */
    @Test
    fun `a parallelMap over a saturated shared pool keeps the process for every item`() {
        contextRunner
            .withPropertyValues("embabel.agent.platform.threading.shared=true")
            .run { context ->
                val asyncer = context.getBean(Asyncer::class.java)
                val outer = mockk<AgentProcess>()

                outer.withCurrent {
                    val callerThread = Thread.currentThread()
                    val ranOn = Collections.synchronizedList(mutableListOf<Thread>())

                    val seen = asyncer.parallelMap((1..16).toList(), maxConcurrency = 16) {
                        ranOn += Thread.currentThread()
                        AgentProcess.get()
                    }

                    assertTrue(
                        ranOn.any { it === callerThread },
                        "precondition: the shared pool must have overflowed onto the submitting thread",
                    )
                    assertEquals(16, seen.size)
                    seen.forEachIndexed { i, p ->
                        assertSame(outer, p, "item ${i + 1} of the fan-out ran without the process")
                    }
                    assertSame(
                        outer, AgentProcess.get(),
                        "the caller must still hold its process after its own fan-out",
                    )
                }
            }
    }

    @Test
    fun `sharing is what exposes the caller thread - the default isolates the app's pool`() {
        // Same application executor, sharing left at its default of false. Embabel builds its own
        // cached pool instead, which always hands off, so the overflow shape is unreachable.
        contextRunner.run { context ->
            val asyncer = context.getBean(Asyncer::class.java)
            val ranOnCaller = AtomicReference(true)
            val outer = mockk<AgentProcess>()

            outer.withCurrent {
                val callerThread = Thread.currentThread()
                val seen = asyncer.async {
                    ranOnCaller.set(Thread.currentThread() === callerThread)
                    AgentProcess.get()
                }.get(30, TimeUnit.SECONDS)

                assertSame(outer, seen)
                assertTrue(!ranOnCaller.get(), "an isolated Embabel executor must not run on the caller")
                assertSame(outer, AgentProcess.get())
            }
        }
    }
}
