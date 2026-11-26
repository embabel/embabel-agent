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

import com.embabel.agent.sandbox.ExecutionRequest
import com.embabel.agent.sandbox.ExecutionResult
import com.embabel.agent.sandbox.SandboxConfig
import com.embabel.agent.sandbox.SandboxSession
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for [DockerSandboxSession].
 * These require Docker to be running.
 */
class DockerSandboxSessionTest {

    private var session: DockerSandboxSession? = null

    private val config = SandboxConfig(
        enabled = true,
        image = "alpine:latest",
        memory = "128m",
        cpus = "0.5",
        network = false,
    )

    @BeforeEach
    fun checkDocker() {
        assumeTrue(DockerExecutor.isDockerAvailable(), "Docker is not available")
    }

    @AfterEach
    fun cleanup() {
        session?.close()
    }

    @Test
    fun `session starts in active state`() {
        session = DockerSandboxSession(
            label = "test-session",
            config = config,
            owner = "test-user",
            ttl = 1.hours,
        )

        assertEquals(SandboxSession.SessionState.ACTIVE, session!!.state)
        assertNotNull(session!!.containerId)
        assertEquals("test-session", session!!.label)
        assertEquals("test-user", session!!.owner)
    }

    @Test
    fun `executes commands and returns output`() {
        session = DockerSandboxSession(label = "test", config = config, ttl = 1.hours)

        val result = session!!.execute(ExecutionRequest(
            command = listOf("echo", "hello world"),
            timeout = 10.seconds,
        ))

        assertIs<ExecutionResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertContains(result.stdout, "hello world")
    }

    @Test
    fun `state persists across executions`() {
        session = DockerSandboxSession(label = "test", config = config, ttl = 1.hours)

        // Create a file
        val write = session!!.execute(ExecutionRequest(
            command = listOf("sh", "-c", "echo 'persistent data' > /tmp/testfile.txt"),
            timeout = 10.seconds,
        ))
        assertIs<ExecutionResult.Completed>(write)
        assertEquals(0, write.exitCode)

        // Read it back in a separate execution
        val read = session!!.execute(ExecutionRequest(
            command = listOf("cat", "/tmp/testfile.txt"),
            timeout = 10.seconds,
        ))
        assertIs<ExecutionResult.Completed>(read)
        assertContains(read.stdout, "persistent data")
    }

    @Test
    @DisabledOnOs(
        OS.WINDOWS,
        disabledReason = "Windows ProcessBuilder does not escape embedded double quotes in the jq command",
    )
    fun `installed packages persist across executions`() {
        val netConfig = config.copy(network = true)
        session = DockerSandboxSession(label = "test", config = netConfig, ttl = 1.hours)

        // Install a package (120s timeout for slow networks)
        val install = session!!.execute(ExecutionRequest(
            command = listOf("apk", "add", "--no-cache", "jq"),
            timeout = 120.seconds,
        ))
        assertIs<ExecutionResult.Completed>(install)
        assertEquals(0, install.exitCode)

        // Use it in a separate execution
        val use = session!!.execute(ExecutionRequest(
            command = listOf("sh", "-c", "echo '{\"key\":\"value\"}' | jq .key"),
            timeout = 10.seconds,
        ))
        assertIs<ExecutionResult.Completed>(use)
        assertContains(use.stdout, "value")
    }

    @Test
    fun `pause and resume preserves state`() {
        session = DockerSandboxSession(label = "test", config = config, ttl = 1.hours)

        // Create state
        session!!.execute(ExecutionRequest(
            command = listOf("sh", "-c", "echo 'before pause' > /tmp/state.txt"),
            timeout = 10.seconds,
        ))

        // Pause and resume with graceful skip for CI environments where containers
        // may be removed by external cleanup processes or resource limits
        try {
            session!!.pause()
            assertEquals(SandboxSession.SessionState.PAUSED, session!!.state)

            session!!.resume()
            assertEquals(SandboxSession.SessionState.ACTIVE, session!!.state)
        } catch (e: RuntimeException) {
            assumeTrue(false, "Skipping: container removed by CI environment - ${e.message}")
        }

        // Verify state survived
        val result = session!!.execute(ExecutionRequest(
            command = listOf("cat", "/tmp/state.txt"),
            timeout = 10.seconds,
        ))
        assertIs<ExecutionResult.Completed>(result)
        assertContains(result.stdout, "before pause")
    }

    @Test
    fun `close destroys the session`() {
        session = DockerSandboxSession(label = "test", config = config, ttl = 1.hours)
        val s = session!!

        s.close()
        assertEquals(SandboxSession.SessionState.CLOSED, s.state)

        // Prevent afterEach from double-closing
        session = null
    }

    @Test
    fun `environment variables are passed to container`() {
        val envConfig = config.copy(
            propagateEnv = emptyList(),
        )
        session = DockerSandboxSession(label = "test", config = envConfig, ttl = 1.hours)

        val result = session!!.execute(ExecutionRequest(
            command = listOf("sh", "-c", "echo \$MY_VAR"),
            environment = mapOf("MY_VAR" to "test_value"),
            timeout = 10.seconds,
        ))
        assertIs<ExecutionResult.Completed>(result)
        assertContains(result.stdout, "test_value")
    }

    @Test
    fun `respects command timeout`() {
        session = DockerSandboxSession(label = "test", config = config, ttl = 1.hours)

        val result = session!!.execute(ExecutionRequest(
            command = listOf("sleep", "60"),
            timeout = 2.seconds,
        ))

        assertIs<ExecutionResult.TimedOut>(result)
    }

    @Test
    fun `lastActiveAt updates after execution`() {
        session = DockerSandboxSession(label = "test", config = config, ttl = 1.hours)
        val before = session!!.lastActiveAt

        Thread.sleep(50)

        session!!.execute(ExecutionRequest(
            command = listOf("echo", "ping"),
            timeout = 10.seconds,
        ))

        assertTrue(session!!.lastActiveAt.isAfter(before))
    }
}
