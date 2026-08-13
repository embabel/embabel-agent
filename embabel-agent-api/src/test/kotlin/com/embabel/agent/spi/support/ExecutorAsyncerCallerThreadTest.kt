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
import org.junit.jupiter.api.Assertions.assertNotSame
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
import com.embabel.agent.core.Blackboard
import io.mockk.every
import java.util.Collections

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

        /**
         * The symptom the defect actually presents as, rather than the thread local behind it.
         *
         * Nothing throws when the process is lost. The next read through [AgentProcess.get] is
         * typically a blackboard access some distance away, so what a user sees is "the blackboard
         * lost my object" with the cause several frames back. Asserted here so the failure this PR
         * removes is described in the suite in the terms someone would actually report it.
         */
        @Test
        fun `the blackboard is still reachable after an async on the caller's thread`() {
            val blackboard = mockk<Blackboard>(relaxed = true)
            val outer = mockk<AgentProcess>()
            every { outer.blackboard } returns blackboard

            outer.withCurrent {
                asyncer.async { "work" }.get(5, TimeUnit.SECONDS)

                // What an interceptor further along the same request does.
                val reachable = AgentProcess.get()?.blackboard
                assertSame(blackboard, reachable, "the blackboard read that would have returned null")
            }
        }

        /**
         * parallelMap on a caller-running executor, which erodes rather than fails.
         *
         * Every item goes through async(), so on this executor every item runs on the caller. With
         * a clear on the way out, the FIRST item empties the thread and items two onward see
         * nothing - one call, partially correct results, and no error anywhere. A single-async test
         * cannot show that shape.
         */
        @Test
        fun `every item of a parallelMap sees the process, not just the first`() {
            val outer = mockk<AgentProcess>()

            outer.withCurrent {
                val seen = asyncer.parallelMap((1..5).toList(), maxConcurrency = 5) { AgentProcess.get() }

                assertEquals(5, seen.size)
                seen.forEachIndexed { i, p -> assertSame(outer, p, "item ${i + 1} ran without the process") }
                assertSame(outer, AgentProcess.get(), "and the caller kept it afterwards")
            }
        }

        @Test
        fun `a concurrency-limited parallelMap does not erode the process either`() {
            // The semaphore branch: maxConcurrency < size takes a different path through
            // ExecutorAsyncer, and on this executor still runs every item on the caller.
            val outer = mockk<AgentProcess>()

            outer.withCurrent {
                val seen = asyncer.parallelMap((1..5).toList(), maxConcurrency = 2) { AgentProcess.get() }

                assertEquals(5, seen.size)
                seen.forEachIndexed { i, p -> assertSame(outer, p, "item ${i + 1} ran without the process") }
                assertSame(outer, AgentProcess.get(), "and the caller kept it afterwards")
            }
        }

        /**
         * A fan-out where one item fails, which is the ordinary case rather than an exotic one -
         * one tool call of several throwing does not abandon the request.
         *
         * The caller has to come out of that still inside its process, or the error handling that
         * runs next is the code that discovers the blackboard is empty. Failure and loss of context
         * arriving together is the worst version of this bug, because the exception looks like the
         * whole story.
         */
        @Test
        fun `a failing item in a fan-out does not take the caller's process with it`() {
            val outer = mockk<AgentProcess>()

            outer.withCurrent {
                assertThrows(RuntimeException::class.java) {
                    asyncer.parallelMap((1..5).toList(), maxConcurrency = 5) { item ->
                        if (item == 3) error("item 3 failed") else AgentProcess.get()
                    }
                }
                assertSame(outer, AgentProcess.get(), "the caller must still be inside its process to handle the failure")
            }
        }

        /**
         * A task that establishes its own process must not hand it back to the caller.
         *
         * Restoring means putting back what the CALLER had, not leaving whatever the task last set.
         * A test that only asserts "not null" would pass on an implementation that leaked the
         * task's process upward, which would be a worse bug than the one being fixed - work
         * attributed to, and billed against, the wrong process.
         */
        @Test
        fun `a task that sets its own process does not leak it to the caller`() {
            val outer = mockk<AgentProcess>()
            val other = mockk<AgentProcess>()

            outer.withCurrent {
                asyncer.async { AgentProcessAccessor.setValue(other) }.get(5, TimeUnit.SECONDS)

                assertSame(outer, AgentProcess.get(), "the caller's own process, not the one the task set")
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

        /**
         * The same executor a deployment actually configures, driving parallelMap rather than a
         * single async.
         *
         * `parallelMap` is how an action fans out, so this is the realistic way to meet the defect:
         * more items than the pool can take, the overflow running on the submitting thread, and -
         * with a clear on the way out - the caller losing its process partway through its own
         * fan-out while some items still succeed.
         */
        @Test
        fun `parallelMap over a saturated pool keeps the process for the overflow and the caller`() {
            val pool = ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(1),
                ThreadPoolExecutor.CallerRunsPolicy(),
            )
            val asyncer = ExecutorAsyncer(pool)
            val outer = mockk<AgentProcess>()

            try {
                outer.withCurrent {
                    val callerThread = Thread.currentThread()
                    val ranOn = Collections.synchronizedList(mutableListOf<Thread>())

                    val seen = asyncer.parallelMap((1..12).toList(), maxConcurrency = 12) {
                        ranOn += Thread.currentThread()
                        AgentProcess.get()
                    }

                    assertTrue(
                        ranOn.any { it === callerThread },
                        "precondition: the pool must have overflowed onto the caller",
                    )
                    assertEquals(12, seen.size)
                    seen.forEachIndexed { i, p -> assertSame(outer, p, "item ${i + 1} ran without the process") }
                    assertSame(outer, AgentProcess.get(), "the caller must still hold its process")
                }
            } finally {
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    /**
     * The two executors [com.embabel.agent.spi.config.spring.AsyncConfiguration] builds when
     * Embabel owns its executor - which is the default, since `threading.shared` is false.
     *
     * Neither can run a task on the submitting thread: a cached pool always hands off to a worker,
     * and a thread-per-task executor always starts a new virtual thread. So neither is affected by
     * the defect this class is about, before or after the fix. They are here to mark that boundary,
     * and to answer "does this happen on virtual threads" with a test rather than an argument.
     */
    @Nested
    inner class OwnedExecutorsNeverRunOnTheCaller {

        @Test
        fun `virtual thread per task - the task runs elsewhere and the caller keeps its process`() {
            val pool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())
            try {
                assertRunsElsewhereAndPreservesCaller(ExecutorAsyncer(pool))
            } finally {
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `cached platform pool - the task runs elsewhere and the caller keeps its process`() {
            val pool = Executors.newCachedThreadPool(Thread.ofPlatform().factory())
            try {
                assertRunsElsewhereAndPreservesCaller(ExecutorAsyncer(pool))
            } finally {
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }
        }

        private fun assertRunsElsewhereAndPreservesCaller(asyncer: ExecutorAsyncer) {
            val outer = mockk<AgentProcess>()
            outer.withCurrent {
                val callerThread = Thread.currentThread()
                val ranOn = AtomicReference<Thread>()
                val seen = asyncer.async {
                    ranOn.set(Thread.currentThread())
                    AgentProcess.get()
                }.get(10, TimeUnit.SECONDS)

                assertNotSame(callerThread, ranOn.get(), "this executor is not supposed to run on the caller")
                assertSame(outer, seen, "the worker must still see the caller's process")
                assertSame(outer, AgentProcess.get(), "and the caller must still hold it")
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
