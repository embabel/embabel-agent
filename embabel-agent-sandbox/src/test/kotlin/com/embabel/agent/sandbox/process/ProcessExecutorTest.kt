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

import com.embabel.agent.sandbox.ExecutionArtifact
import com.embabel.agent.sandbox.ExecutionRequest
import com.embabel.agent.sandbox.ExecutionResult
import com.embabel.agent.sandbox.SandboxConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProcessExecutorTest {

    @Test
    fun `executor is always available`() {
        val executor = ProcessExecutor()
        assertNull(executor.checkAvailability())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Requires Unix commands (echo)")
    fun `executes simple echo command`() {
        val executor = ProcessExecutor()

        val result = executor.execute(
            ExecutionRequest(
                command = listOf("echo", "hello world"),
                timeout = 10.seconds,
            )
        )

        assertIs<ExecutionResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("hello world"))
        assertTrue(result.success)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Requires Unix commands (sh)")
    fun `captures exit code on failure`() {
        val executor = ProcessExecutor()

        val result = executor.execute(
            ExecutionRequest(
                command = listOf("sh", "-c", "exit 42"),
                timeout = 10.seconds,
            )
        )

        assertIs<ExecutionResult.Completed>(result)
        assertEquals(42, result.exitCode)
        assertTrue(!result.success)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Requires Unix commands (sleep)")
    fun `respects timeout`() {
        val executor = ProcessExecutor()

        val result = executor.execute(
            ExecutionRequest(
                command = listOf("sleep", "10"),
                timeout = 1.seconds,
            )
        )

        assertIs<ExecutionResult.TimedOut>(result)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Requires Unix commands (sh)")
    fun `passes environment variables`() {
        val executor = ProcessExecutor()

        val result = executor.execute(
            ExecutionRequest(
                command = listOf("sh", "-c", "echo \$TEST_VAR"),
                environment = mapOf("TEST_VAR" to "test_value"),
                timeout = 10.seconds,
            )
        )

        assertIs<ExecutionResult.Completed>(result)
        assertTrue(result.stdout.contains("test_value"))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Requires Unix commands (sh)")
    fun `provides INPUT_DIR and OUTPUT_DIR environment variables`() {
        val executor = ProcessExecutor()

        val result = executor.execute(
            ExecutionRequest(
                command = listOf("sh", "-c", "echo INPUT=\$INPUT_DIR OUTPUT=\$OUTPUT_DIR"),
                timeout = 10.seconds,
            )
        )

        assertIs<ExecutionResult.Completed>(result)
        assertTrue(result.stdout.contains("INPUT="))
        assertTrue(result.stdout.contains("OUTPUT="))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Requires Unix commands (cat)")
    fun `handles stdin`() {
        val executor = ProcessExecutor()

        val result = executor.execute(
            ExecutionRequest(
                command = listOf("cat"),
                stdin = "hello from stdin",
                timeout = 10.seconds,
            )
        )

        assertIs<ExecutionResult.Completed>(result)
        assertTrue(result.stdout.contains("hello from stdin"))
    }

    @Test
    fun `returns Failed for non-existent command`() {
        val executor = ProcessExecutor()

        val result = executor.execute(
            ExecutionRequest(
                command = listOf("nonexistent_command_xyz_123"),
                timeout = 10.seconds,
            )
        )

        assertIs<ExecutionResult.Failed>(result)
    }
}
