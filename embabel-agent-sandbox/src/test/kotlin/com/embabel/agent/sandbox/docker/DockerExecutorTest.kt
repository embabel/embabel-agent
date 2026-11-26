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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class DockerExecutorTest {
    private val image = "alpine:latest"
    private fun executor() = DockerExecutor(image, networkEnabled = false)

    @BeforeEach
    fun requireDocker() {
        assumeTrue(DockerExecutor.isDockerAvailable() && DockerExecutor.imageExists(image))
    }

    @Nested
    inner class InputAndOutput {
        @Test
        fun `stdin reaches the container`() {
            val result = executor().execute(ExecutionRequest(listOf("cat"), stdin = "hello from stdin", timeout = 20.seconds))
            assertEquals("hello from stdin", assertIs<ExecutionResult.Completed>(result).stdout)
            assertEquals(0, result.exitCode)
        }

        @Test
        fun `piped stdin and streaming callbacks work with capture enabled and disabled`() {
            for (capture in listOf(true, false)) {
                val lines = mutableListOf<String>()
                val result = executor().execute(ExecutionRequest(listOf("cat"), stdin = "first\nlast\n",
                    timeout = 20.seconds, captureOutput = capture, stdoutCallback = { lines.add(it) }))
                assertIs<ExecutionResult.Completed>(result)
                assertEquals(0, result.exitCode)
                assertEquals(if (capture) "first\nlast" else "", result.stdout)
                assertEquals("", result.stderr)
                assertEquals(listOf("first", "last"), lines)
            }
        }

        @Test
        fun `empty stdin reaches EOF while streaming callbacks are enabled`() {
            val lines = mutableListOf<String>()
            val result = executor().execute(ExecutionRequest(listOf("cat"), stdin = "",
                timeout = 20.seconds, stdoutCallback = { lines.add(it) }))
            assertIs<ExecutionResult.Completed>(result)
            assertEquals(0, result.exitCode)
            assertEquals("", result.stdout)
            assertEquals("", result.stderr)
            assertTrue(lines.isEmpty())
        }

        @Test
        fun `streaming with stdin preserves separate stderr`() {
            val lines = mutableListOf<String>()
            val result = executor().execute(ExecutionRequest(listOf("sh", "-s"),
                stdin = "echo output; echo diagnostic >&2", timeout = 20.seconds,
                stdoutCallback = { lines.add(it) }))
            assertIs<ExecutionResult.Completed>(result)
            assertEquals(0, result.exitCode)
            assertEquals("output", result.stdout)
            assertEquals("diagnostic", result.stderr.trim())
            assertEquals(listOf("output"), lines)
        }

        @Test
        fun `drains large output while providing large input`() {
            val script = "head -c 2097152 /dev/zero; wc -c >&2\n" + "#".repeat(2 * 1024 * 1024)
            // The shell writes before consuming the rest of the script.
            val result = executor().execute(ExecutionRequest(listOf("sh", "-s"), stdin = script, timeout = 20.seconds))
            assertIs<ExecutionResult.Completed>(result)
            assertEquals(0, result.exitCode)
            assertContains(result.stdout, "[stdout truncated")
        }

        @Test
        fun `bounds both output streams`() {
            val result = executor().execute(ExecutionRequest(listOf("sh", "-s"), timeout = 20.seconds,
                stdin = "head -c 1200000 /dev/zero; head -c 1200000 /dev/zero >&2"))
            assertIs<ExecutionResult.Completed>(result)
            assertEquals(0, result.exitCode)
            assertContains(result.stdout, "[stdout truncated")
            assertContains(result.stderr, "[stderr truncated")
            assertTrue(result.stdout.length < 1_050_000)
            assertTrue(result.stderr.length < 1_050_000)
        }

        @Test
        fun `disabled capture still delivers streaming callbacks`() {
            val lines = mutableListOf<String>()
            val result = executor().execute(ExecutionRequest(listOf("printf", "first\\nlast"), timeout = 20.seconds,
                captureOutput = false, stdoutCallback = { lines.add(it) }))
            assertIs<ExecutionResult.Completed>(result)
            assertEquals(0, result.exitCode)
            assertEquals("", result.stdout)
            assertEquals("", result.stderr)
            assertEquals(listOf("first", "last"), lines)
        }

        @Test
        fun `disabled capture drains high volume output`() {
            val result = executor().execute(ExecutionRequest(listOf("sh", "-s"), timeout = 20.seconds,
                stdin = "head -c 1200000 /dev/zero; head -c 1200000 /dev/zero >&2", captureOutput = false))
            assertIs<ExecutionResult.Completed>(result)
            assertEquals(0, result.exitCode)
            assertEquals("", result.stdout)
            assertEquals("", result.stderr)
        }
    }

    @Nested
    inner class Configuration {
        private fun config() = SandboxConfig(enabled = true, image = image, network = false,
            propagateEnv = listOf("PATH"), mapEnv = mapOf("SANDBOX_TEST_VALUE" to "PATH"),
            runtimeEnv = mapOf("SANDBOX_TEST_VALUE" to "runtime"),
            extraHosts = mapOf("sandbox.test" to "127.0.0.2"))

        @Test
        fun `runtime values override mapped host values`() {
            val result = config().createExecutor()!!.execute(
                ExecutionRequest(listOf("/bin/printenv", "SANDBOX_TEST_VALUE"), timeout = 20.seconds))
            assertEquals("runtime", assertIs<ExecutionResult.Completed>(result).stdout)
        }

        @Test
        fun `request values override runtime values`() {
            val result = config().createExecutor()!!.execute(ExecutionRequest(listOf("/bin/printenv", "SANDBOX_TEST_VALUE"),
                environment = mapOf("SANDBOX_TEST_VALUE" to "request"), timeout = 20.seconds))
            assertEquals("request", assertIs<ExecutionResult.Completed>(result).stdout)
        }

        @Test
        fun `configured extra hosts reach the container`() {
            val result = config().createExecutor()!!.execute(ExecutionRequest(listOf("/bin/cat", "/etc/hosts"), timeout = 20.seconds))
            assertContains(assertIs<ExecutionResult.Completed>(result).stdout, "127.0.0.2\tsandbox.test")
        }
    }
}
