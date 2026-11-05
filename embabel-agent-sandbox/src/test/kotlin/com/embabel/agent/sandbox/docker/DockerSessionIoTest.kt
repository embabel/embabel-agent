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
package com.embabel.agent.sandbox.docker

import com.embabel.agent.sandbox.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DockerSessionIoTest {
    private val config = SandboxConfig(enabled = true, image = "alpine:latest", network = false, propagateEnv = emptyList())
    @BeforeEach fun requireDocker() {
        assumeTrue(DockerExecutor.isDockerAvailable() && DockerExecutor.imageExists(config.image))
    }

    @Test
    fun `large stdout and stderr are drained with bounded retained output`() {
        DockerSandboxSession("bounded-output", config, ttl = 1.hours).use { session ->
            val result = assertIs<ExecutionResult.Completed>(session.execute(ExecutionRequest(
                listOf("sh", "-s"),
                stdin = "head -c 1200000 /dev/zero | tr '\\000' x; head -c 1200000 /dev/zero | tr '\\000' y >&2",
                timeout = 15.seconds,
            )))
            assertEquals(0, result.exitCode)
            assertContains(result.stdout, "[stdout truncated")
            assertContains(result.stderr, "[stderr truncated")
            assertTrue(result.stdout.length < 1_050_000)
            assertTrue(result.stderr.length < 1_050_000)
        }
    }

    @Test
    fun `captureOutput false still drains both streams and runs callbacks`() {
        DockerSandboxSession("uncaptured-output", config, ttl = 1.hours).use { session ->
            val seen = mutableListOf<String>()
            val result = assertIs<ExecutionResult.Completed>(session.execute(ExecutionRequest(
                listOf("sh", "-s"), stdin = "printf 'ready\\n'; head -c 262144 /dev/zero >&2",
                timeout = 10.seconds, captureOutput = false, stdoutCallback = { seen.add(it) },
            )))
            assertEquals(0, result.exitCode)
            assertEquals("", result.stdout)
            assertEquals("", result.stderr)
            assertEquals(listOf("ready"), seen)
        }
    }

    @Test
    fun `slow callback under line pressure does not grow an unbounded queue`() {
        DockerSandboxSession("callback-pressure", config, ttl = 1.hours).use { session ->
            val started = System.nanoTime()
            val result = session.execute(ExecutionRequest(listOf("sh", "-s"),
                stdin = "i=0; while [ \"\$i\" -lt 1000 ]; do echo \"\$i\"; i=\$((i+1)); done",
                timeout = 500.milliseconds, stdoutCallback = { Thread.sleep(20000) }))
            assertIs<ExecutionResult.TimedOut>(result)
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started) < java.time.Duration.ofSeconds(4))
        }
    }

    @Test
    fun `brief output burst waits for callback capacity`() {
        DockerSandboxSession("callback-burst", config, ttl = 1.hours).use { session ->
            val seen = mutableListOf<String>()
            val result = assertIs<ExecutionResult.Completed>(session.execute(ExecutionRequest(
                listOf("sh", "-s"),
                stdin = "i=0; while [ \"\$i\" -lt 200 ]; do echo \"\$i\"; i=\$((i+1)); done",
                timeout = 10.seconds,
                stdoutCallback = { Thread.sleep(2); seen.add(it) },
            )))
            assertEquals(0, result.exitCode)
            assertEquals((0 until 200).map(Int::toString), seen)
        }
    }

    @Test
    fun `large input and output are pumped concurrently`() {
        DockerSandboxSession("large-io", config, ttl = 1.hours).use { session ->
            val script = "head -c 262144 /dev/zero | tr '\\000' x; echo; " +
                "head -c 262144 /dev/zero | tr '\\000' y >&2; echo >&2\n" +
                "# padding\n".repeat(100000) + "echo done\n"
            val workers = Executors.newSingleThreadExecutor()
            try {
                val result = assertIs<ExecutionResult.Completed>(workers.submit<ExecutionResult> {
                    session.execute(ExecutionRequest(listOf("sh", "-s"), stdin = script, timeout = 15.seconds))
                }.get(20, TimeUnit.SECONDS))
                assertEquals(0, result.exitCode)
                assertTrue(result.stdout.endsWith("done"))
                assertTrue(result.stdout.length > 262144)
                assertTrue(result.stderr.length >= 262144)
            } finally { workers.shutdownNow() }
        }
    }

    @Test
    fun `command that never reads large stdin still times out`() {
        DockerSandboxSession("blocked-input", config, ttl = 1.hours).use { session ->
            val workers = Executors.newSingleThreadExecutor()
            try {
                val result = workers.submit<ExecutionResult> {
                    session.execute(ExecutionRequest(listOf("sleep", "20"), stdin = "x".repeat(4 * 1024 * 1024),
                        timeout = 500.milliseconds))
                }.get(5, TimeUnit.SECONDS)
                assertIs<ExecutionResult.TimedOut>(result)
            } finally { workers.shutdownNow() }
        }
    }

    @Test
    fun `callback can close session without deadlocking`() {
        DockerSandboxSession("callback-close", config, ttl = 1.hours).use { session ->
            val workers = Executors.newSingleThreadExecutor()
            try {
                val result = workers.submit<ExecutionResult> {
                    session.execute(ExecutionRequest(listOf("sh"), stdin = "echo ready; sleep 1; echo done", timeout = 10.seconds,
                        stdoutCallback = { if (it == "ready") session.close() }))
                }.get(15, TimeUnit.SECONDS)
                assertEquals(0, assertIs<ExecutionResult.Completed>(result).exitCode)
                assertEquals(SandboxSession.SessionState.CLOSED, session.state)
            } finally { workers.shutdownNow() }
        }
    }

    @Test
    fun `idle eviction skips executing sessions`() {
        DockerSandboxSessionManager().use { manager ->
            val session = manager.create("busy", config, ttl = 100.milliseconds)
            val ready = CountDownLatch(1)
            val workers = Executors.newSingleThreadExecutor()
            try {
                val result = workers.submit<ExecutionResult> {
                    session.execute(ExecutionRequest(listOf("sh"), stdin = "echo ready; sleep 1", timeout = 10.seconds,
                        stdoutCallback = { ready.countDown() }))
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                Thread.sleep(150)
                manager.evictExpired()
                assertEquals(SandboxSession.SessionState.ACTIVE, session.state)
                assertEquals(0, assertIs<ExecutionResult.Completed>(result.get(15, TimeUnit.SECONDS)).exitCode)
            } finally { workers.shutdownNow() }
        }
    }

    @Test
    fun `slow callback cannot extend execution beyond its deadline`() {
        DockerSandboxSession("slow-callback", config, ttl = 1.hours).use { session ->
            val workers = Executors.newSingleThreadExecutor()
            try {
                val result = workers.submit<ExecutionResult> {
                    session.execute(ExecutionRequest(listOf("sh"), stdin = "echo ready", timeout = 500.milliseconds,
                        stdoutCallback = { Thread.sleep(20000) }))
                }.get(5, TimeUnit.SECONDS)
                assertIs<ExecutionResult.TimedOut>(result)
            } finally { workers.shutdownNow() }
        }
    }
}
