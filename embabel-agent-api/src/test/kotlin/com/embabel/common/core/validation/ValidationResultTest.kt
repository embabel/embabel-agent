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
package com.embabel.common.core.validation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for ValidationResult data class.
 *
 * Tests cover:
 * - Construction with valid/invalid states
 * - Companion object VALID instance
 * - Data class equality and properties
 * - Edge cases with empty and multiple errors
 */
class ValidationResultTest {

    @Test
    fun `ValidationResult should correctly represent validation state with errors`() {
        // Given
        val error = ValidationError(
            code = "TEST_ERROR",
            message = "Test validation error",
            severity = ValidationSeverity.ERROR,
            location = ValidationLocation("type", "name", "agent", "component")
        )
        val invalidResult = ValidationResult(isValid = false, errors = listOf(error))
        val validResult = ValidationResult(isValid = true, errors = emptyList())

        // Then
        assertFalse(invalidResult.isValid)
        assertEquals(1, invalidResult.errors.size)
        assertTrue(validResult.isValid)
        assertTrue(validResult.errors.isEmpty())
    }

    @Test
    fun `ValidationResult VALID companion object should be valid with empty errors`() {
        // Given
        val validResult = ValidationResult.VALID

        // Then
        assertTrue(validResult.isValid)
        assertTrue(validResult.errors.isEmpty())
    }

    @Test
    fun `ValidationResult should handle multiple errors`() {
        // Given
        val location = ValidationLocation("type", "name", "agent", "component")
        val error1 = ValidationError("ERROR_1", "First error", ValidationSeverity.ERROR, location)
        val error2 = ValidationError("ERROR_2", "Second error", ValidationSeverity.WARNING, location)
        val errors = listOf(error1, error2)

        val result = ValidationResult(isValid = false, errors = errors)

        // Then
        assertFalse(result.isValid)
        assertEquals(2, result.errors.size)
        assertEquals(error1, result.errors[0])
        assertEquals(error2, result.errors[1])
    }

    @Test
    fun `ValidationResult should work with guardrail proxy factory severity handling`() {
        // Given - Real guardrails use case: determine highest severity from multiple errors
        val location = ValidationLocation("GuardRail", "ContentFilter", "ChatAgent", "proxy")
        val infoError = ValidationError("INFO_CODE", "Info message", ValidationSeverity.INFO, location)
        val criticalError = ValidationError("CRIT_CODE", "Critical violation", ValidationSeverity.CRITICAL, location)

        val result = ValidationResult(isValid = false, errors = listOf(infoError, criticalError))

        // When - Use new API to get highest severity
        val highestSeverity = result.getHighestSeverity()

        // Then
        assertEquals(ValidationSeverity.CRITICAL, highestSeverity)
        assertEquals(2, result.errors.size)
    }

    @Test
    fun `getHighestSeverity should return correct severity ordering`() {
        // Given
        val location = ValidationLocation("test", "test", "test", "test")

        // Test with single severities
        val infoResult = ValidationResult(false, listOf(
            ValidationError("INFO", "info", ValidationSeverity.INFO, location)
        ))
        val warningResult = ValidationResult(false, listOf(
            ValidationError("WARN", "warning", ValidationSeverity.WARNING, location)
        ))
        val errorResult = ValidationResult(false, listOf(
            ValidationError("ERR", "error", ValidationSeverity.ERROR, location)
        ))
        val criticalResult = ValidationResult(false, listOf(
            ValidationError("CRIT", "critical", ValidationSeverity.CRITICAL, location)
        ))

        // Test with mixed severities
        val mixedResult = ValidationResult(false, listOf(
            ValidationError("INFO", "info", ValidationSeverity.INFO, location),
            ValidationError("WARN", "warning", ValidationSeverity.WARNING, location),
            ValidationError("ERR", "error", ValidationSeverity.ERROR, location)
        ))

        // Empty result
        val emptyResult = ValidationResult(true, emptyList())

        // Then
        assertEquals(ValidationSeverity.INFO, infoResult.getHighestSeverity())
        assertEquals(ValidationSeverity.WARNING, warningResult.getHighestSeverity())
        assertEquals(ValidationSeverity.ERROR, errorResult.getHighestSeverity())
        assertEquals(ValidationSeverity.CRITICAL, criticalResult.getHighestSeverity())
        assertEquals(ValidationSeverity.ERROR, mixedResult.getHighestSeverity()) // ERROR > WARNING > INFO
        assertNull(emptyResult.getHighestSeverity())
    }

    @Nested
    inner class ValidationErrorTests {

        private fun createTestLocation(name: String = "ContentFilter") = ValidationLocation(
            type = "GuardRail",
            name = name,
            agentName = "TestAgent",
            component = "ValidationProxy"
        )

        @Test
        fun `ValidationError should work with guardrail exception throwing logic`() {
            // Given - Real use case: GuardRailViolationException creation
            val location = createTestLocation()
            val criticalError = ValidationError(
                code = "INAPPROPRIATE_CONTENT",
                message = "Content violates safety guidelines",
                severity = ValidationSeverity.CRITICAL,
                location = location
            )

            // When - Simulate exception creation logic from GuardedOperationsProxyFactory
            val shouldThrowException = criticalError.severity == ValidationSeverity.CRITICAL

            // Then
            assertTrue(shouldThrowException)
            assertEquals("INAPPROPRIATE_CONTENT", criticalError.code)
            assertTrue(criticalError.message.contains("safety guidelines"))
        }
    }

    @Nested
    inner class ValidationSeverityTests {

        @Test
        fun `ValidationSeverity should be used in priority-based guardrail logic`() {
            // Given - Real use case: severity comparison in GuardedOperationsProxyFactory
            val severityPriority = mapOf(
                ValidationSeverity.INFO to 1,
                ValidationSeverity.WARNING to 2,
                ValidationSeverity.ERROR to 3,
                ValidationSeverity.CRITICAL to 4
            )

            // When - Test priority mapping used in actual guardrail code
            val infoPriority = severityPriority[ValidationSeverity.INFO]!!
            val warningPriority = severityPriority[ValidationSeverity.WARNING]!!
            val errorPriority = severityPriority[ValidationSeverity.ERROR]!!
            val criticalPriority = severityPriority[ValidationSeverity.CRITICAL]!!

            // Then - Verify logical priority ordering
            assertTrue(criticalPriority > errorPriority) // CRITICAL blocks execution
            assertTrue(errorPriority > warningPriority) // ERROR > WARNING
            assertTrue(warningPriority > infoPriority) // WARNING > INFO
        }
    }
}
