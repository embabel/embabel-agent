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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class AgentProcessAccessorTest {

    private val accessor = AgentProcessAccessor

    @AfterEach
    fun cleanup() {
        AgentProcess.remove()
    }

    @Test
    fun `getValue returns null when no AgentProcess set`() {
        assertNull(accessor.getValue())
    }

    @Test
    fun `getValue returns AgentProcess when set`() {
        val mockProcess = mock(AgentProcess::class.java)
        AgentProcess.set(mockProcess)

        assertEquals(mockProcess, accessor.getValue())
    }

    @Test
    fun `setValue sets AgentProcess`() {
        val mockProcess = mock(AgentProcess::class.java)
        accessor.setValue(mockProcess)

        assertEquals(mockProcess, AgentProcess.get())
    }

    @Test
    fun `reset removes AgentProcess`() {
        val mockProcess = mock(AgentProcess::class.java)
        AgentProcess.set(mockProcess)

        accessor.reset()

        assertNull(AgentProcess.get())
    }

    /**
     * [AgentProcessAccessor.with] stated on its own terms.
     *
     * [ExecutorAsyncerCallerThreadTest] covers the same guarantee through the executor, which is
     * where it is reached and why it matters. These pin the contract itself, so a change to `with`
     * fails against its own rules rather than against the behaviour of a saturated thread pool
     * several layers away.
     */
    @Nested
    inner class With {

        @AfterEach
        fun cleanup() {
            AgentProcess.remove()
        }

        @Test
        fun `restores the previous process after the block returns`() {
            val previous = mock(AgentProcess::class.java)
            val inner = mock(AgentProcess::class.java)
            accessor.setValue(previous)

            val seen = accessor.with(inner) { AgentProcess.get() }

            assertSame(inner, seen, "the block must run with the value it was given")
            assertSame(previous, AgentProcess.get(), "the thread must be left holding what it held before")
        }

        @Test
        fun `restores the previous process when the block throws`() {
            val previous = mock(AgentProcess::class.java)
            val inner = mock(AgentProcess::class.java)
            accessor.setValue(previous)

            assertFailsWith<IllegalStateException> {
                accessor.with(inner) { error("boom") }
            }

            assertSame(previous, AgentProcess.get())
        }

        @Test
        fun `restores the outer value after a nested with, not just the innermost`() {
            val outer = mock(AgentProcess::class.java)
            val middle = mock(AgentProcess::class.java)
            val inner = mock(AgentProcess::class.java)

            accessor.with(outer) {
                accessor.with(middle) {
                    accessor.with(inner) {
                        assertSame(inner, AgentProcess.get())
                    }
                    assertSame(middle, AgentProcess.get(), "unwinding one level must land on the middle value")
                }
                assertSame(outer, AgentProcess.get(), "unwinding again must land on the outer value")
            }
            assertNull(AgentProcess.get(), "and the outermost frame started from nothing")
        }

        @Test
        fun `clears the slot when the thread held nothing to begin with`() {
            val inner = mock(AgentProcess::class.java)
            assertNull(AgentProcess.get(), "precondition: this thread starts empty")

            accessor.with(inner) { }

            // The case clearing was written for: a pooled worker that arrived empty must not carry
            // this task's process into whatever lands on it next.
            assertNull(AgentProcess.get())
        }

        /**
         * A null value is not "clear the process for the duration". It means the caller had nothing
         * to propagate, which is what [ExecutorAsyncer] passes when a task is submitted from
         * outside any process - so the block runs against whatever the running thread already
         * holds, and `with` neither sets nor clears anything.
         */
        @Test
        fun `a null value leaves a process the thread already holds in place`() {
            val current = mock(AgentProcess::class.java)
            accessor.setValue(current)

            val seen = accessor.with(null) { AgentProcess.get() }

            assertSame(current, seen, "with(null) does not clear for the duration of the block")
            assertSame(current, AgentProcess.get(), "nor afterwards")
        }

        @Test
        fun `a null value leaves an empty thread empty, and still runs the block`() {
            val seen = accessor.with(null) { AgentProcess.get() to "result" }

            assertNull(seen.first)
            assertEquals("result", seen.second)
            assertNull(AgentProcess.get())
        }
    }
}

class ExecutorAsyncerContextPropagationTest {

    private val executor = Executors.newSingleThreadExecutor()
    private val asyncer = ExecutorAsyncer(executor)

    @AfterEach
    fun cleanup() {
        AgentProcess.remove()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `context propagates to worker thread`() {
        val mockProcess = mock(AgentProcess::class.java)
        AgentProcess.set(mockProcess)

        val future = asyncer.async {
            AgentProcess.get()
        }

        val result = future.get(5, TimeUnit.SECONDS)
        assertEquals(mockProcess, result)
    }

    @Test
    fun `worker thread has no context when main thread has none`() {
        assertNull(AgentProcess.get())

        val future = asyncer.async {
            AgentProcess.get()
        }

        val result = future.get(5, TimeUnit.SECONDS)
        assertNull(result)
    }

    @Test
    fun `worker thread context is cleaned up after execution`() {
        val mockProcess = mock(AgentProcess::class.java)
        AgentProcess.set(mockProcess)

        // First task propagates context
        asyncer.async {
            assertNotNull(AgentProcess.get())
        }.get(5, TimeUnit.SECONDS)

        // Remove from main thread
        AgentProcess.remove()

        // Second task should not see stale context (explicit cleanup in ExecutorAsyncer)
        val future = asyncer.async {
            AgentProcess.get()
        }

        val result = future.get(5, TimeUnit.SECONDS)
        assertNull(result)
    }

    @Test
    fun `context is cleaned up even when block throws exception`() {
        val mockProcess = mock(AgentProcess::class.java)
        AgentProcess.set(mockProcess)

        // First task throws exception
        val failingFuture = asyncer.async {
            assertNotNull(AgentProcess.get())
            throw RuntimeException("Intentional failure")
        }

        // Wait for it to complete (with exception)
        try {
            failingFuture.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Expected
        }

        // Remove from main thread
        AgentProcess.remove()

        // Second task should not see stale context - cleanup happened despite exception
        val future = asyncer.async {
            AgentProcess.get()
        }

        val result = future.get(5, TimeUnit.SECONDS)
        assertNull(result)
    }

    @Test
    fun `parallelMap propagates context to all workers`() {
        val mockProcess = mock(AgentProcess::class.java)
        AgentProcess.set(mockProcess)

        val items = listOf(1, 2, 3, 4, 5)
        val results = asyncer.parallelMap(items, maxConcurrency = 3) { item ->
            // Each worker should see the propagated AgentProcess
            val process = AgentProcess.get()
            assertNotNull(process)
            assertEquals(mockProcess, process)
            item * 2
        }

        assertEquals(listOf(2, 4, 6, 8, 10), results)
    }

    @Test
    fun `concurrent tasks each see their own captured context`() {
        val process1 = mock(AgentProcess::class.java)
        val process2 = mock(AgentProcess::class.java)

        // Capture snapshot with process1
        AgentProcess.set(process1)
        val future1 = asyncer.async {
            Thread.sleep(50) // Delay to ensure overlap
            AgentProcess.get()
        }

        // Capture snapshot with process2
        AgentProcess.set(process2)
        val future2 = asyncer.async {
            AgentProcess.get()
        }

        // Each task should see its own captured context
        val result2 = future2.get(5, TimeUnit.SECONDS)
        val result1 = future1.get(5, TimeUnit.SECONDS)

        assertEquals(process1, result1)
        assertEquals(process2, result2)
    }

    @Test
    fun `nested withCurrent restores outer context after inner completes`() {
        val outerProcess = mock(AgentProcess::class.java)
        val innerProcess = mock(AgentProcess::class.java)

        with(AgentProcess) {
            outerProcess.withCurrent {
                assertEquals(outerProcess, AgentProcess.get())

                innerProcess.withCurrent {
                    assertEquals(innerProcess, AgentProcess.get())
                }

                // After inner completes, outer should be restored
                assertEquals(outerProcess, AgentProcess.get())
            }
        }

        // After outer completes, should be null
        assertNull(AgentProcess.get())
    }

    @Test
    fun `tool decider can access AgentProcess on worker thread`() {
        val mockProcess = mock(AgentProcess::class.java)
        org.mockito.Mockito.`when`(mockProcess.id).thenReturn("process-123")
        AgentProcess.set(mockProcess)

        val delegateTool = object : com.embabel.agent.api.tool.Tool {
            override val definition = com.embabel.agent.api.tool.Tool.Definition(
                name = "context-checker",
                description = "Test tool",
                inputSchema = com.embabel.agent.api.tool.Tool.InputSchema.empty(),
            )
            override fun call(input: String) = com.embabel.agent.api.tool.Tool.Result.text("check")
        }

        var capturedProcessId: String? = null
        val conditionalTool = com.embabel.agent.api.tool.ConditionalReplanningTool(
            delegate = delegateTool,
            decider = { context ->
                capturedProcessId = context.agentProcess.id
                com.embabel.agent.api.tool.ReplanDecision(reason = "Captured context")
            }
        )

        // Execute tool on worker thread (simulates ParallelToolLoop)
        val future = asyncer.async {
            try {
                conditionalTool.call("{}")
            } catch (e: com.embabel.agent.core.ReplanRequestedException) {
                // Expected - decider triggered replan
            }
            capturedProcessId
        }

        val result = future.get(5, TimeUnit.SECONDS)
        assertEquals("process-123", result)
    }
}
