/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.spi.validation

import jakarta.validation.ConstraintViolation
import jakarta.validation.Valid
import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.constraints.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultValidationPromptGeneratorTest {

    private lateinit var generator: DefaultValidationPromptGenerator
    private lateinit var validator: Validator

    @BeforeEach
    fun setUp() {
        generator = DefaultValidationPromptGenerator()
        validator = Validation.buildDefaultValidatorFactory().validator
    }

    // Test data classes with various validation annotations
    data class SimpleUser(
        @field:NotBlank(message = "Name cannot be blank")
        val name: String,

        @field:Email(message = "Must be a valid email address")
        val email: String
    )

    data class ComplexUser(
        @field:NotNull(message = "ID is required")
        @field:Min(value = 1, message = "ID must be positive")
        val id: Long?,

        @field:NotBlank(message = "Username cannot be blank")
        @field:Size(min = 3, max = 20, message = "Username must be between 3 and 20 characters")
        @field:Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username can only contain letters, numbers, and underscores")
        val username: String,

        @field:Email(message = "Must be a valid email address")
        val email: String,

        @field:Min(value = 0, message = "Age cannot be negative")
        @field:Max(value = 150, message = "Age cannot exceed 150")
        val age: Int,

        @field:Past(message = "Birth date must be in the past")
        val birthDate: LocalDate,

        @field:DecimalMin(value = "0.0", message = "Balance cannot be negative")
        @field:DecimalMax(value = "1000000.0", message = "Balance cannot exceed 1,000,000")
        val balance: Double
    )

    data class ProductInfo(
        @field:NotBlank(message = "Product name is required")
        @field:Size(min = 2, max = 100, message = "Product name must be between 2 and 100 characters")
        val name: String,

        @field:NotNull(message = "Price is required")
        @field:DecimalMin(value = "0.01", message = "Price must be at least 0.01")
        val price: Double?,

        @field:Size(max = 500, message = "Description cannot exceed 500 characters")
        val description: String?
    )

    class EmptyClass

    @Nested
    inner class GenerateRequirementsPrompt {

        @Test
        fun `should generate requirements for simple validation constraints`() {
            val result = generator.generateRequirementsPrompt(validator, SimpleUser::class.java)

            assertTrue(result.startsWith("Validation Requirements:"))
            assertTrue(result.contains("Field 'name': NotBlank constraint"))
            assertTrue(result.contains("Name cannot be blank"))
            assertTrue(result.contains("Field 'email': Email constraint"))
            assertTrue(result.contains("Must be a valid email address"))
        }

        @Test
        fun `should generate requirements for complex validation constraints`() {
            val result = generator.generateRequirementsPrompt(validator, ComplexUser::class.java)

            assertTrue(result.startsWith("Validation Requirements:"))

            // Check for all field constraints
            assertTrue(result.contains("Field 'id': NotNull constraint"))
            assertTrue(result.contains("Field 'id': Min constraint"))
            assertTrue(result.contains("ID is required"))
            assertTrue(result.contains("ID must be positive"))

            assertTrue(result.contains("Field 'username': NotBlank constraint"))
            assertTrue(result.contains("Field 'username': Size constraint"))
            assertTrue(result.contains("Field 'username': Pattern constraint"))
            assertTrue(result.contains("Username cannot be blank"))
            assertTrue(result.contains("Username must be between 3 and 20 characters"))
            assertTrue(result.contains("Username can only contain letters, numbers, and underscores"))

            assertTrue(result.contains("Field 'email': Email constraint"))
            assertTrue(result.contains("Must be a valid email address"))

            assertTrue(result.contains("Field 'age': Min constraint"))
            assertTrue(result.contains("Field 'age': Max constraint"))
            assertTrue(result.contains("Age cannot be negative"))
            assertTrue(result.contains("Age cannot exceed 150"))

            assertTrue(result.contains("Field 'birthDate': Past constraint"))
            assertTrue(result.contains("Birth date must be in the past"))

            assertTrue(result.contains("Field 'balance': DecimalMin constraint"))
            assertTrue(result.contains("Field 'balance': DecimalMax constraint"))
            assertTrue(result.contains("Balance cannot be negative"))
            assertTrue(result.contains("Balance cannot exceed 1,000,000"))
        }

        @Test
        fun `should handle nullable fields with constraints`() {
            val result = generator.generateRequirementsPrompt(validator, ProductInfo::class.java)

            assertTrue(result.startsWith("Validation Requirements:"))
            assertTrue(result.contains("Field 'name': NotBlank constraint"))
            assertTrue(result.contains("Field 'name': Size constraint"))
            assertTrue(result.contains("Field 'price': NotNull constraint"))
            assertTrue(result.contains("Field 'price': DecimalMin constraint"))
            assertTrue(result.contains("Field 'description': Size constraint"))
        }

        @Test
        fun `should return no constraints message for class without validation annotations`() {
            val result = generator.generateRequirementsPrompt(validator, EmptyClass::class.java)

            assertEquals("No validation constraints defined.", result)
        }

        @Test
        fun `should handle class with only one constraint`() {
            data class SingleConstraintClass(
                @field:NotNull(message = "Value is required")
                val value: String?
            )

            val result = generator.generateRequirementsPrompt(validator, SingleConstraintClass::class.java)

            assertTrue(result.startsWith("Validation Requirements:"))
            assertTrue(result.contains("Field 'value': NotNull constraint"))
            assertTrue(result.contains("Value is required"))
        }
    }

    @Nested
    inner class GenerateViolationsReport {

        @Test
        fun `should generate violations report for simple constraint violations`() {
            val user = SimpleUser("", "invalid-email")
            val violations = validator.validate(user)

            val result = generator.generateViolationsReport(violations)

            assertTrue(result.startsWith("Validation Violations:"))
            assertTrue(violations.isNotEmpty())

            // Check that violations are properly formatted
            violations.forEach { violation ->
                val propertyPath = violation.propertyPath.toString()
                val invalidValue = violation.invalidValue
                val message = violation.message
                assertTrue(result.contains("Field '$propertyPath' with value '$invalidValue': $message"))
            }
        }

        @Test
        fun `should generate violations report for complex constraint violations`() {
            val user = ComplexUser(
                id = null,
                username = "a", // too short
                email = "invalid-email",
                age = -5, // negative
                birthDate = LocalDate.now().plusDays(1), // future date
                balance = -100.0 // negative balance
            )
            val violations = validator.validate(user)

            val result = generator.generateViolationsReport(violations)

            assertTrue(result.startsWith("Validation Violations:"))
            assertTrue(violations.isNotEmpty())

            // Verify all violations are included
            violations.forEach { violation ->
                val propertyPath = violation.propertyPath.toString()
                val invalidValue = violation.invalidValue
                val message = violation.message
                assertTrue(result.contains("Field '$propertyPath' with value '$invalidValue': $message"))
            }
        }

        @Test
        fun `should handle multiple violations on same field`() {
            val user = ComplexUser(
                id = 1L,
                username = "a!", // too short and invalid pattern
                email = "valid@example.com",
                age = 25,
                birthDate = LocalDate.of(1990, 1, 1),
                balance = 100.0
            )
            val violations = validator.validate(user)

            val result = generator.generateViolationsReport(violations)

            if (violations.isNotEmpty()) {
                assertTrue(result.startsWith("Validation Violations:"))
                // Should contain violations for username (both size and pattern)
                assertTrue(result.contains("username"))
            }
        }

        @Test
        fun `should return no violations message for valid object`() {
            val user = SimpleUser("John Doe", "john@example.com")
            val violations = validator.validate(user)

            val result = generator.generateViolationsReport(violations)

            assertEquals("No validation violations.", result)
        }

        @Test
        fun `should handle empty violations set`() {
            val emptyViolations = emptySet<ConstraintViolation<Any>>()

            val result = generator.generateViolationsReport(emptyViolations)

            assertEquals("No validation violations.", result)
        }

        @Test
        fun `should handle null values in violations correctly`() {
            val product = ProductInfo("A", null, "Valid description")
            val violations = validator.validate(product)

            val result = generator.generateViolationsReport(violations)

            if (violations.isNotEmpty()) {
                assertTrue(result.startsWith("Validation Violations:"))
                // Should handle null price value
                violations.forEach { violation ->
                    if (violation.propertyPath.toString() == "price") {
                        assertTrue(result.contains("with value 'null'"))
                    }
                }
            }
        }

        @Test
        fun `should format violations with complex property paths`() {
            data class NestedClass(
                @field:NotBlank(message = "Nested value cannot be blank")
                val nestedValue: String
            )

            data class ContainerClass(
                @field:Valid
                val nested: NestedClass
            )

            val container = ContainerClass(NestedClass(""))
            val violations = validator.validate(container)

            val result = generator.generateViolationsReport(violations)

            if (violations.isNotEmpty()) {
                assertTrue(result.startsWith("Validation Violations:"))
                // Should handle nested property paths
                assertTrue(result.contains("nested.nestedValue"))
            }
        }
    }

    // Validation groups for testing
    interface CreateGroup
    interface UpdateGroup

    @Nested
    inner class EdgeCases {

        @Test
        fun `should handle class with inherited constraints`() {
            open class BaseUser(
                @field:NotBlank(message = "Base name is required")
                open val name: String
            )

            data class ExtendedUser(
                override val name: String,
                @field:Email(message = "Email is required")
                val email: String
            ) : BaseUser(name)

            val result = generator.generateRequirementsPrompt(validator, ExtendedUser::class.java)

            assertTrue(result.contains("Field 'name': NotBlank constraint"))
            assertTrue(result.contains("Field 'email': Email constraint"))
        }

        @Test
        fun `should handle custom validation messages with special characters`() {
            data class SpecialMessageClass(
                @field:NotBlank(message = "Name cannot be blank! Must contain: letters, numbers & symbols.")
                val name: String
            )

            val result = generator.generateRequirementsPrompt(validator, SpecialMessageClass::class.java)

            assertTrue(result.contains("Name cannot be blank! Must contain: letters, numbers & symbols."))
        }

        @Test
        fun `should handle validation groups (basic test)`() {
            data class GroupedValidationClass(
                @field:Null(groups = [CreateGroup::class], message = "ID must be null for creation")
                @field:NotNull(groups = [UpdateGroup::class], message = "ID is required for update")
                val id: Long?,

                @field:NotBlank(message = "Name is always required")
                val name: String
            )

            val result = generator.generateRequirementsPrompt(validator, GroupedValidationClass::class.java)

            // Default validation should show constraints that apply to Default group
            assertTrue(result.contains("Field 'name': NotBlank constraint"))
        }
    }
}
