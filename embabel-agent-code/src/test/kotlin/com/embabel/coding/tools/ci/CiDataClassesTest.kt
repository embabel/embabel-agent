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
package com.embabel.coding.tools.ci

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class CiDataClassesTest {

    @Test
    fun `should create BuildOptions with required field`() {
        // Arrange & Act
        val options = BuildOptions(buildCommand = "mvn test")

        // Assert
        assertEquals("mvn test", options.buildCommand)
        assertFalse(options.streamOutput)
        assertFalse(options.interactive)
    }

    @Test
    fun `should create BuildOptions with all fields`() {
        // Arrange & Act
        val options = BuildOptions(
            buildCommand = "gradle build",
            streamOutput = true,
            interactive = true
        )

        // Assert
        assertEquals("gradle build", options.buildCommand)
        assertTrue(options.streamOutput)
        assertTrue(options.interactive)
    }

    @Test
    fun `should create BuildOptions with streamOutput only`() {
        // Arrange & Act
        val options = BuildOptions(
            buildCommand = "npm test",
            streamOutput = true
        )

        // Assert
        assertEquals("npm test", options.buildCommand)
        assertTrue(options.streamOutput)
        assertFalse(options.interactive)
    }

    @Test
    fun `should create BuildStatus with success and output`() {
        // Arrange & Act
        val status = BuildStatus(
            success = true,
            relevantOutput = "Build completed successfully"
        )

        // Assert
        assertTrue(status.success)
        assertEquals("Build completed successfully", status.relevantOutput)
    }

    @Test
    fun `should create BuildStatus with failure`() {
        // Arrange & Act
        val status = BuildStatus(
            success = false,
            relevantOutput = "Compilation error on line 42"
        )

        // Assert
        assertFalse(status.success)
        assertEquals("Compilation error on line 42", status.relevantOutput)
    }

    @Test
    fun `should create BuildResult with status`() {
        // Arrange
        val status = BuildStatus(true, "Success output")
        val timestamp = Instant.now()
        val duration = Duration.ofSeconds(30)

        // Act
        val result = BuildResult(
            status = status,
            rawOutput = "Full build output...",
            timestamp = timestamp,
            runningTime = duration
        )

        // Assert
        assertEquals(status, result.status)
        assertEquals("Full build output...", result.rawOutput)
        assertEquals(timestamp, result.timestamp)
        assertEquals(duration, result.runningTime)
    }

    @Test
    fun `should create BuildResult without status`() {
        // Arrange
        val duration = Duration.ofMinutes(2)

        // Act
        val result = BuildResult(
            status = null,
            rawOutput = "Unknown build output",
            runningTime = duration
        )

        // Assert
        assertNull(result.status)
        assertEquals("Unknown build output", result.rawOutput)
        assertEquals(duration, result.runningTime)
        assertNotNull(result.timestamp)
    }

    @Test
    fun `should return relevant output when status exists`() {
        // Arrange
        val status = BuildStatus(true, "Relevant output here")
        val result = BuildResult(
            status = status,
            rawOutput = "Full output with lots of details",
            runningTime = Duration.ofSeconds(10)
        )

        // Act
        val relevantOutput = result.relevantOutput()

        // Assert
        assertEquals("Relevant output here", relevantOutput)
    }

    @Test
    fun `should return raw output when status is null`() {
        // Arrange
        val result = BuildResult(
            status = null,
            rawOutput = "Raw output fallback",
            runningTime = Duration.ofSeconds(5)
        )

        // Act
        val relevantOutput = result.relevantOutput()

        // Assert
        assertEquals("Raw output fallback", relevantOutput)
    }

    @Test
    fun `should generate contribution with success status`() {
        // Arrange
        val status = BuildStatus(true, "Tests passed")
        val result = BuildResult(
            status = status,
            rawOutput = "Full output",
            runningTime = Duration.ofSeconds(20)
        )

        // Act
        val contribution = result.contribution()

        // Assert
        assertTrue(contribution.contains("Build result: success=true"))
        assertTrue(contribution.contains("Relevant output:"))
        assertTrue(contribution.contains("Tests passed"))
    }

    @Test
    fun `should generate contribution with failure status`() {
        // Arrange
        val status = BuildStatus(false, "Compilation failed")
        val result = BuildResult(
            status = status,
            rawOutput = "Error details",
            runningTime = Duration.ofSeconds(15)
        )

        // Act
        val contribution = result.contribution()

        // Assert
        assertTrue(contribution.contains("Build result: success=false"))
        assertTrue(contribution.contains("Compilation failed"))
    }

    @Test
    fun `should generate contribution with unknown status`() {
        // Arrange
        val result = BuildResult(
            status = null,
            rawOutput = "Unknown output",
            runningTime = Duration.ofSeconds(10)
        )

        // Act
        val contribution = result.contribution()

        // Assert
        assertTrue(contribution.contains("Build result: success=unknown"))
        assertTrue(contribution.contains("Unknown output"))
    }

    @Test
    fun `should implement Timestamped interface`() {
        // Arrange
        val result = BuildResult(
            status = null,
            rawOutput = "output",
            runningTime = Duration.ofSeconds(1)
        )

        // Assert
        assertTrue(result is com.embabel.common.core.types.Timestamped)
        assertNotNull(result.timestamp)
    }

    @Test
    fun `should implement Timed interface`() {
        // Arrange
        val duration = Duration.ofMinutes(1)
        val result = BuildResult(
            status = null,
            rawOutput = "output",
            runningTime = duration
        )

        // Assert
        assertTrue(result is com.embabel.common.core.types.Timed)
        assertEquals(duration, result.runningTime)
    }

    @Test
    fun `should implement PromptContributor interface`() {
        // Arrange
        val result = BuildResult(
            status = null,
            rawOutput = "output",
            runningTime = Duration.ofSeconds(5)
        )

        // Assert
        assertTrue(result is com.embabel.common.ai.prompt.PromptContributor)
        assertNotNull(result.contribution())
    }
}
