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
import com.embabel.agent.core.AgentProcess.Companion.withCurrent
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * An [Executor] may run a task on the thread that submitted it. The [AgentProcess] thread local
 * therefore has to be RESTORED on the way out, not cleared: the submitting thread is already
 * inside a process, and clearing leaves it holding nothing for the rest of its work.
 *
 * Nothing throws when that happens. The next [AgentProcess.get] returns null - typically a
 * blackboard read, arbitrarily far from the async() that emptied the slot - so the symptom is
 * "the blackboard lost my object" and the cause is several frames away.
 */
@Timeout(30)
class ExecutorAsyncerCallerThreadTest {

    /**
     * The shape in miniature: the task runs on the caller's thread.
     */
    @Nested
    inner class DirectExecutor {

        private val asyncer = ExecutorAsyncer { it.run() }

        @Test
        fun `the caller still holds its process after the task returns`() {
            val outer = mockk<AgentProcess>()
            outer.withCurrent {
                asyncer.async { "work" }.get(5, TimeUnit.SECONDS)
                assertSame(outer, AgentProcess.get())
            }
        }

        @Test
        fun `the task itself sees the process`() {
            val outer = mockk<AgentProcess>()
            val seen = outer.withCurrent {
                asyncer.async { AgentProcess.get() }.get(5, TimeUnit.SECONDS)
            }
            assertSame(outer, seen)
        }

        @Test
        fun `a caller holding no process is left holding none`() {
            asyncer.async { "work" }.get(5, TimeUnit.SECONDS)
            assertNull(AgentProcess.get())
        }

        @Test
        fun `the process is restored even when the task throws`() {
            val outer = mockk<AgentProcess>()
            outer.withCurrent {
                assertThrows(ExecutionException::class.java) {
                    asyncer.async<String> { error("boom") }.get(5, TimeUnit.SECONDS)
                }
                assertSame(outer, AgentProcess.get())
            }
        }

        @Test
        fun `nesting restores each level, not just the innermost`() {
            val outer = mockk<AgentProcess>()
            val inner = mockk<AgentProcess>()

            outer.withCurrent {
                asyncer.async {
                    assertSame(outer, AgentProcess.get())
                    inner.withCurrent {
                        asyncer.async { AgentProcess.get() }.get(5, TimeUnit.SECONDS)
                    }.also { assertSame(inner, it) }
                    // The inner async must not have taken the outer process with it
                    assertSame(outer, AgentProcess.get())
                }.get(5, TimeUnit.SECONDS)
                assertSame(outer, AgentProcess.get())
            }
        }

        @Test
        fun `repeated asyncs do not erode the caller's process`() {
            val outer = mockk<AgentProcess>()
            outer.withCurrent {
                repeat(50) { asyncer.async { "work" }.get(5, TimeUnit.SECONDS) }
                assertSame(outer, AgentProcess.get())
            }
        }
    }

    /**
     * How this is actually reached in production. A saturated [ThreadPoolExecutor] configured with
     * [ThreadPoolExecutor.CallerRunsPolicy] runs the overflow task on the submitting thread - so
     * the behaviour appears under load and not before.
     */
    @Nested
    inner class CallerRunsUnderLoad {

        @Test
        fun `an overflowing pool does not empty the submitting thread`() {
            // One worker, queue of one: the third task overflows and runs on the caller.
            val pool = ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(1),
                ThreadPoolExecutor.CallerRunsPolicy(),
            )
            val asyncer = ExecutorAsyncer(pool)
            val release = CountDownLatch(1)
            val ranOnCaller = AtomicReference(false)
            val outer = mockk<AgentProcess>()

            try {
                outer.withCurrent {
                    val callerThread = Thread.currentThread()
                    // Occupy the worker, then fill the queue.
                    val blocking = asyncer.async { release.await(10, TimeUnit.SECONDS) }
                    val queued = asyncer.async { "queued" }
                    // This one has nowhere to go, so the caller runs it.
                    val overflow = asyncer.async {
                        ranOnCaller.set(Thread.currentThread() === callerThread)
                        AgentProcess.get()
                    }

                    assertSame(outer, overflow.get(10, TimeUnit.SECONDS), "the task must see the process")
                    assertTrue(ranOnCaller.get(), "precondition: the overflow task must have run on the caller")
                    assertSame(outer, AgentProcess.get(), "the caller must still hold its process")

                    release.countDown()
                    blocking.get(10, TimeUnit.SECONDS)
                    queued.get(10, TimeUnit.SECONDS)
                }
            } finally {
                release.countDown()
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    /**
     * The behaviour clearing was there to protect: a pooled thread must not carry one task's
     * process into the next task to land on it.
     */
    @Nested
    inner class PooledThreadIsolation {

        @Test
        fun `a task leaves no process behind for the next task on the same thread`() {
            val single = Executors.newSingleThreadExecutor()
            try {
                val asyncer = ExecutorAsyncer(single)
                val outer = mockk<AgentProcess>()

                outer.withCurrent {
                    asyncer.async { AgentProcess.get() }.get(5, TimeUnit.SECONDS)
                }

                // Same pooled thread, no process on the caller this time.
                val seen = asyncer.async { AgentProcess.get() }.get(5, TimeUnit.SECONDS)
                assertNull(seen, "the pooled thread carried a process into an unrelated task")
            } finally {
                single.shutdown()
                single.awaitTermination(5, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `two processes do not bleed into each other across reuse`() {
            val single = Executors.newSingleThreadExecutor()
            try {
                val asyncer = ExecutorAsyncer(single)
                val first = mockk<AgentProcess>()
                val second = mockk<AgentProcess>()

                val a = first.withCurrent { asyncer.async { AgentProcess.get() }.get(5, TimeUnit.SECONDS) }
                val b = second.withCurrent { asyncer.async { AgentProcess.get() }.get(5, TimeUnit.SECONDS) }

                assertSame(first, a)
                assertSame(second, b)
            } finally {
                single.shutdown()
                single.awaitTermination(5, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `parallelMap gives every worker the caller's process and leaves none behind`() {
            val pool = Executors.newCachedThreadPool()
            try {
                val asyncer = ExecutorAsyncer(pool)
                val outer = mockk<AgentProcess>()

                val seen = outer.withCurrent {
                    asyncer.parallelMap((1..8).toList(), 4) { AgentProcess.get() }
                }
                assertEquals(8, seen.size)
                seen.forEach { assertSame(outer, it) }

                // Threads are recycled; an unrelated task must not inherit
                val after = asyncer.parallelMap((1..8).toList(), 4) { AgentProcess.get() }
                after.forEach { assertNull(it) }
            } finally {
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }
}
