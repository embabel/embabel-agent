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
package com.embabel.agent.sandbox.process

import com.embabel.agent.sandbox.ExecutionRequest
import com.embabel.agent.sandbox.ExecutionResult
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessExecutionTest {
    @TempDir
    lateinit var root: Path

    private fun command(mode: String): List<String> {
        val source = root.resolve("ProcessProducer.java")
        Files.writeString(source, """
            class ProcessProducer {
                public static void main(String[] args) throws Exception {
                    switch (args[0]) {
                        case "lines":
                            System.out.println("first"); System.out.print("last"); break;
                        case "large":
                            for (int i = 0; i < 512; i++) {
                                System.out.print("x".repeat(4096));
                                System.err.print("y".repeat(4096));
                            }
                            break;
                        case "duplex":
                            System.out.print("x".repeat(2 * 1024 * 1024));
                            System.err.print(System.in.readAllBytes().length); break;
                        case "sleep":
                            System.out.println("ready"); System.out.flush(); Thread.sleep(30000); break;
                        case "environment": System.out.print(System.getenv("SANDBOX_TEST_VALUE")); break;
                        case "reject": System.err.print("rejected"); System.exit(42); break;
                    }
                }
            }
        """.trimIndent())
        return listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), source.toString(), mode)
    }

    @Nested
    inner class Streaming {
        @Test
        fun `host executor streams lines including the last unterminated line without capture`() {
            val lines = mutableListOf<String>()
            val result = ProcessExecutor().execute(ExecutionRequest(command("lines"), timeout = 20.seconds,
                captureOutput = false, stdoutCallback = lines::add))
            assertIs<ExecutionResult.Completed>(result)
            assertEquals(0, result.exitCode)
            assertEquals("", result.stdout)
            assertEquals("", result.stderr)
            assertEquals(listOf("first", "last"), lines)
        }

        @Test
        fun `callback failure does not prevent output draining`() {
            var calls = 0
            val result = ProcessExecutor().execute(ExecutionRequest(command("lines"), timeout = 20.seconds,
                stdoutCallback = { calls++; error("consumer failed") }))
            assertIs<ExecutionResult.Completed>(result)
            assertEquals(0, result.exitCode)
            assertEquals(2, calls)
            assertContains(result.stdout, "last")
        }

        @Test
        fun `callback receives output while the process is still running`() {
            val ready = CountDownLatch(1)
            val interrupted = AtomicBoolean()
            val result = AtomicReference<ExecutionResult>()
            val request = ExecutionRequest(command("sleep"), timeout = 20.seconds, stdoutCallback = { ready.countDown() })
            val worker = Thread {
                result.set(ProcessExecutor().execute(request))
                interrupted.set(Thread.currentThread().isInterrupted)
            }.apply { start() }
            try {
                assertTrue(ready.await(15, TimeUnit.SECONDS))
                assertTrue(worker.isAlive)
                worker.interrupt()
                worker.join(3000)
                assertFalse(worker.isAlive)
                assertIs<ExecutionResult.Failed>(result.get())
                assertTrue(interrupted.get())
            } finally { worker.interrupt() }
        }

        @Test
        fun `callback cannot retain an unbounded line`() {
            val result = ProcessExecutor().execute(ExecutionRequest(command("large"), timeout = 20.seconds,
                stdoutCallback = {}))
            assertIs<ExecutionResult.Failed>(result)
            assertContains(result.error, "callback line exceeded")
        }

        @Test
        fun `a blocked callback cannot hold execution past its timeout`() {
            val release = CountDownLatch(1)
            try {
                val result = ProcessExecutor().execute(ExecutionRequest(command("lines"), timeout = 3.seconds,
                    stdoutCallback = { release.await() }))
                assertIs<ExecutionResult.TimedOut>(result)
                assertTrue(result.duration < 6.seconds)
            } finally { release.countDown() }
        }
    }

    @Nested
    inner class Bounds {
        @Test
        fun `nonzero exit is preserved when a child closes stdin before consuming input`() {
            val result = ProcessExecutor().execute(ExecutionRequest(command("reject"),
                stdin = "z".repeat(4 * 1024 * 1024), timeout = 20.seconds))
            assertIs<ExecutionResult.Completed>(result)
            assertEquals(42, result.exitCode)
            assertEquals("rejected", result.stderr)
        }

        @Test
        fun `bounds both streams while continuing to drain the child`() {
            val result = ProcessExecutor().execute(ExecutionRequest(command("large"), timeout = 20.seconds))
            assertIs<ExecutionResult.Completed>(result)
            assertEquals(0, result.exitCode)
            assertContains(result.stdout, "[stdout truncated after 1048576 characters]")
            assertContains(result.stderr, "[stderr truncated after 1048576 characters]")
            assertTrue(result.stdout.length < 1_049_000)
            assertTrue(result.stderr.length < 1_049_000)
        }

        @Test
        fun `drains output while writing input larger than the pipe buffer`() {
            val input = "z".repeat(2 * 1024 * 1024)
            val result = ProcessExecutor().execute(ExecutionRequest(command("duplex"), stdin = input, timeout = 20.seconds))
            assertIs<ExecutionResult.Completed>(result)
            assertEquals(0, result.exitCode)
            assertEquals(input.length.toString(), result.stderr)
        }

        @Test
        fun `timeout includes writing stdin to a process that never reads it`() {
            val result = ProcessExecutor().execute(ExecutionRequest(command("sleep"),
                stdin = "z".repeat(4 * 1024 * 1024), timeout = 3.seconds))
            assertIs<ExecutionResult.TimedOut>(result)
            assertTrue(result.duration < 6.seconds)
        }

        @Test
        fun `failed startup retains duration and cause`() {
            val result = ProcessExecutor().execute(ExecutionRequest(listOf(root.resolve("missing").toString()), timeout = 20.seconds))
            assertIs<ExecutionResult.Failed>(result)
            assertTrue(result.duration.isPositive())
            assertNotNull(result.cause)
        }

        @Test
        fun `expired budget does not start a process`() {
            val request = ExecutionRequest(listOf("missing-command"), timeout = 0.milliseconds)
            assertIs<ExecutionResult.TimedOut>(ProcessExecution.run(ProcessBuilder(request.command), request))
        }

        @Test
        fun `request environment overrides executor values`() {
            val result = ProcessExecutor(baseEnvironment = mapOf("SANDBOX_TEST_VALUE" to "base"))
                .execute(ExecutionRequest(command("environment"), environment = mapOf("SANDBOX_TEST_VALUE" to "request"),
                    timeout = 20.seconds))
            assertEquals("request", assertIs<ExecutionResult.Completed>(result).stdout)
        }
    }
}
