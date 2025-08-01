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
package com.embabel.ux.form

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class TypeTestUser(
    // Now form field on this one
    val stringValue: String,

    @property:FormField("int-id")
    val intValue: Int,

    @property:FormField("long-id")
    val longValue: Long,

    @property:FormField("double-id")
    val doubleValue: Double,

    @property:FormField("float-id")
    val floatValue: Float,

    @property:FormField("boolean-id")
    val booleanValue: Boolean,

    @property:FormField("date-id")
    val dateValue: LocalDate,

    @property:FormField("time-id")
    val timeValue: LocalTime
)

data class OptionalFieldsUser(
    @property:FormField("name-id")
    val name: String,

    @property:FormField("age-id")
    val age: Int?,

    @property:FormField("email-id")
    val email: String?
)

class FormBinderTest {

    // Test data classes
    data class SimpleUser(
        @property:FormField("name-id")
        val name: String,

        @property:FormField("age-id")
        val age: Int
    )

    data class UserWithOptional(
        @property:FormField("name-id")
        val name: String,

        @property:FormField("age-id")
        val age: Int,

        @property:FormField("bio-id")
        val bio: String? = null
    )

    data class ComplexUser(
        @property:FormField("name-id")
        val name: String,

        @property:FormField("email-id")
        val email: String,

        @property:FormField("age-id")
        val age: Int,

        @property:FormField("birth-date-id")
        val birthDate: LocalDate,

        @property:FormField("wake-time-id")
        val wakeTime: LocalTime,

        @property:FormField("active-id")
        val isActive: Boolean,

        @property:FormField("country-id")
        val country: String,

        @property:FormField("hobbies-id")
        val hobbies: List<String>
    )

    data class UnmappedFieldUser(
        @property:FormField("name-id")
        val name: String,

        @property:NoFormField
        val unmappedField: String // No annotation
    )

    // Setup helper functions
    private fun createSimpleSubmission(valid: Boolean = true): FormSubmissionResult {
        return FormSubmissionResult(
            submission = FormSubmission(
                formId = "test-form",
                values = mapOf(
                    "name-id" to "John Doe",
                    "age-id" to 30
                )
            ),
            values = mapOf(
                "name-id" to ControlValue.TextValue("John Doe"),
                "age-id" to ControlValue.NumberValue(30.0)
            ),
            valid = valid,
            validationErrors = if (valid) emptyMap() else mapOf("age-id" to "Age must be positive")
        )
    }

    private fun createComplexSubmission(): FormSubmissionResult {
        // Raw submission with primitive values as would come from a form
        val rawSubmission = FormSubmission(
            formId = "complex-form",
            values = mapOf(
                "name-id" to "Jane Smith",
                "email-id" to "jane@example.com",
                "age-id" to 28,
                "birth-date-id" to "1995-03-15", // Note: Date as string from form
                "wake-time-id" to "07:30",       // Note: Time as string from form
                "active-id" to true,
                "country-id" to "Canada",
                "hobbies-id" to listOf("Reading", "Hiking")
            )
        )

        // The processed submission with typed values
        return FormSubmissionResult(
            submission = rawSubmission,
            values = mapOf(
                "name-id" to ControlValue.TextValue("Jane Smith"),
                "email-id" to ControlValue.TextValue("jane@example.com"),
                "age-id" to ControlValue.NumberValue(28.0),
                "birth-date-id" to ControlValue.DateValue(LocalDate.of(1995, 3, 15)),
                "wake-time-id" to ControlValue.TimeValue(LocalTime.of(7, 30)),
                "active-id" to ControlValue.BooleanValue(true),
                "country-id" to ControlValue.OptionValue("Canada"),
                "hobbies-id" to ControlValue.MultiOptionValue(listOf("Reading", "Hiking"))
            ),
            valid = true
        )
    }

    @Nested
    @DisplayName("Basic Binding Tests")
    inner class BasicBindingTests {

        @Test
        @DisplayName("Should bind a simple form submission to a data class")
        fun bindSimpleFormSubmission() {
            val submission = createSimpleSubmission()
            val user: SimpleUser = submission.bindTo()
            assertEquals("John Doe", user.name)
            assertEquals(30, user.age)
        }

        @Test
        @DisplayName("Should bind a complex form submission with various types")
        fun bindComplexFormSubmission() {
            // Given
            val submission = createComplexSubmission()

            // When
            val user: ComplexUser = submission.bindTo()

            // Then
            assertEquals("Jane Smith", user.name)
            assertEquals("jane@example.com", user.email)
            assertEquals(28, user.age)
            assertEquals(LocalDate.of(1995, 3, 15), user.birthDate)
            assertEquals(LocalTime.of(7, 30), user.wakeTime)
            assertTrue(user.isActive)
            assertEquals("Canada", user.country)
            assertEquals(listOf("Reading", "Hiking"), user.hobbies)
        }

        @Test
        @DisplayName("Should handle optional fields when present")
        fun handleOptionalFieldsWhenPresent() {
            // Given
            val rawSubmission = FormSubmission(
                formId = "test-form",
                values = mapOf(
                    "name-id" to "Alice",
                    "age-id" to 25,
                    "bio-id" to "Software engineer"
                )
            )

            val submission = FormSubmissionResult(
                submission = rawSubmission,
                values = mapOf(
                    "name-id" to ControlValue.TextValue("Alice"),
                    "age-id" to ControlValue.NumberValue(25.0),
                    "bio-id" to ControlValue.TextValue("Software engineer")
                ),
                valid = true
            )

            // When
            val user: UserWithOptional = submission.bindTo()

            // Then
            assertEquals("Alice", user.name)
            assertEquals(25, user.age)
            assertEquals("Software engineer", user.bio)
        }

        @Test
        @DisplayName("Should handle optional fields when missing")
        fun handleOptionalFieldsWhenMissing() {
            // Given
            val rawSubmission = FormSubmission(
                formId = "test-form",
                values = mapOf(
                    "name-id" to "Bob",
                    "age-id" to 40
                    // bio-id is missing
                )
            )

            val submission = FormSubmissionResult(
                submission = rawSubmission,
                values = mapOf(
                    "name-id" to ControlValue.TextValue("Bob"),
                    "age-id" to ControlValue.NumberValue(40.0)
                    // bio-id is missing
                ),
                valid = true
            )

            assertThrows<FormBinder.FormBindingException> {
                submission.bindTo<UserWithOptional>()
            }
        }
    }

    @Nested
    @DisplayName("Java Binding Tests")
    inner class JavaBindingTests {

        @Test
        fun `bind record`() {
            val submission = FormSubmissionResult(
                submission = FormSubmission(
                    formId = "test-form",
                    values = mapOf(
                        "name" to "John Doe",
                        "age" to 30
                    )
                ),
                values = mapOf(
                    "name" to ControlValue.TextValue("John Doe"),
                    "age" to ControlValue.NumberValue(30.0)
                ),
                valid = true,
                validationErrors = emptyMap()
            )
            val user: JavaPersonRecord = submission.bindTo()
            assertEquals("John Doe", user.name)
            assertEquals(30, user.age)
        }

        @Test
        fun `bind record with control id`() {
            val submission = createSimpleSubmission()
            val user: JavaPersonRecordWithControlId = submission.bindTo()
            assertEquals("John Doe", user.name)
            assertEquals(30, user.age)
        }

        @Test
        fun `bind immutable class`() {
            val submission = createSimpleSubmission()
            val user: JavaPersonImmutable = submission.bindTo()
            assertEquals("John Doe", user.getName())
            assertEquals(30, user.getAge())
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    inner class ErrorHandlingTests {

        @Test
        @DisplayName("Should throw ValidationException when form is invalid")
        fun throwValidationExceptionForInvalidForm() {
            // Given
            val invalidSubmission = createSimpleSubmission(valid = false)

            // When/Then
            val exception = assertThrows(FormBinder.ValidationException::class.java) {
                invalidSubmission.bindTo<SimpleUser>()
            }

            assertEquals("Form validation failed", exception.message)
            assertEquals("Age must be positive", exception.errors["age-id"])
        }

        @Test
        @DisplayName("Should throw FormBindingException when control ID is missing")
        fun throwExceptionWhenControlIdIsMissing() {
            // Given
            val rawSubmission = FormSubmission(
                formId = "test-form",
                values = mapOf(
                    "name-id" to "Charlie"
                    // age-id is missing
                )
            )

            val incompleteSubmission = FormSubmissionResult(
                submission = rawSubmission,
                values = mapOf(
                    "name-id" to ControlValue.TextValue("Charlie")
                    // age-id is missing
                ),
                valid = true
            )

            // When/Then
            val exception = assertThrows(FormBinder.FormBindingException::class.java) {
                incompleteSubmission.bindTo<SimpleUser>()
            }

            assertTrue(
                exception.message?.contains("No value found for control id 'age-id'") == true,
                "Expected error message about missing control id, got ${exception.message}",
            )
        }

        @Test
        @DisplayName("Should throw FormBindingException for type mismatch")
        fun throwExceptionForTypeMismatch() {
            // Given
            val rawSubmission = FormSubmission(
                formId = "test-form",
                values = mapOf(
                    "name-id" to "Dave",
                    "age-id" to "not-a-number" // String instead of number
                )
            )

            val submissionWithTypeMismatch = FormSubmissionResult(
                submission = rawSubmission,
                values = mapOf(
                    "name-id" to ControlValue.TextValue("Dave"),
                    "age-id" to ControlValue.TextValue("not-a-number") // String instead of number
                ),
                valid = true
            )

            // When/Then
            assertThrows(FormBinder.FormBindingException::class.java) {
                submissionWithTypeMismatch.bindTo<SimpleUser>()
            }
        }

        @Test
        @DisplayName("Should throw FormBindingException for transient required fields")
        fun throwExceptionForUnmappedRequiredFields() {
            // Given
            val submission = createSimpleSubmission()

            // When/Then
            val exception = assertThrows(FormBinder.FormBindingException::class.java) {
                submission.bindTo<UnmappedFieldUser>()
            }

            assertTrue(
                exception.message?.contains("Missing required parameter") ?: false,
                "Expected error message about missing required parameter, got ${exception.message}",
            )
        }
    }

    @Nested
    @DisplayName("Type Conversion Tests")
    inner class TypeConversionTests {


        @Test
        @DisplayName("Should convert different control value types correctly")
        fun convertDifferentValueTypesCorrectly() {
            val rawSubmission = FormSubmission(
                formId = "type-test",
                values = mapOf(
                    "stringValue" to "test string",
                    "int-id" to 42,
                    "long-id" to 9999999999L,
                    "double-id" to 3.14159,
                    "float-id" to 2.71828f,
                    "boolean-id" to true,
                    "date-id" to "2023-05-15",
                    "time-id" to "14:30:45"
                )
            )

            val submission = FormSubmissionResult(
                submission = rawSubmission,
                values = mapOf(
                    "stringValue" to ControlValue.TextValue("test string"),
                    "int-id" to ControlValue.NumberValue(42.0),
                    "long-id" to ControlValue.NumberValue(9999999999.0),
                    "double-id" to ControlValue.NumberValue(3.14159),
                    "float-id" to ControlValue.NumberValue(2.71828),
                    "boolean-id" to ControlValue.BooleanValue(true),
                    "date-id" to ControlValue.DateValue(LocalDate.of(2023, 5, 15)),
                    "time-id" to ControlValue.TimeValue(LocalTime.of(14, 30, 45))
                ),
                valid = true
            )

            // When
            val user: TypeTestUser = submission.bindTo()

            // Then
            assertEquals("test string", user.stringValue)
            assertEquals(42, user.intValue)
            assertEquals(9999999999L, user.longValue)
            assertEquals(3.14159, user.doubleValue)
            assertEquals(2.71828f, user.floatValue)
            assertTrue(user.booleanValue)
            assertEquals(LocalDate.of(2023, 5, 15), user.dateValue)
            assertEquals(LocalTime.of(14, 30, 45), user.timeValue)
        }

        @Test
        @DisplayName("Should convert string to appropriate types")
        fun convertStringToAppropriateTypes() {
            // Given - all values represented as strings in the raw submission
            val rawSubmission = FormSubmission(
                formId = "string-conversion-test",
                values = mapOf(
                    "stringValue" to "test string",
                    "int-id" to "42",
                    "long-id" to "9999999999",
                    "double-id" to "3.14159",
                    "float-id" to "2.71828",
                    "boolean-id" to "true",
                    "date-id" to "2023-05-15",
                    "time-id" to "14:30:45"
                )
            )

            // But in the processed result, they're properly typed
            val submission = FormSubmissionResult(
                submission = rawSubmission,
                values = mapOf(
                    "stringValue" to ControlValue.TextValue("test string"),
                    "int-id" to ControlValue.TextValue("42"),
                    "long-id" to ControlValue.TextValue("9999999999"),
                    "double-id" to ControlValue.TextValue("3.14159"),
                    "float-id" to ControlValue.TextValue("2.71828"),
                    "boolean-id" to ControlValue.TextValue("true"),
                    "date-id" to ControlValue.TextValue("2023-05-15"),
                    "time-id" to ControlValue.TextValue("14:30:45")
                ),
                valid = true
            )

            // When
            val user: TypeTestUser = submission.bindTo()

            // Then
            assertEquals("test string", user.stringValue)
            assertEquals(42, user.intValue)
            assertEquals(9999999999L, user.longValue)
            assertEquals(3.14159, user.doubleValue)
            assertEquals(2.71828f, user.floatValue)
            assertTrue(user.booleanValue)
            assertEquals(LocalDate.of(2023, 5, 15), user.dateValue)
            assertEquals(LocalTime.of(14, 30, 45), user.timeValue)
        }
    }

    @Nested
    @DisplayName("Empty Value Tests")
    inner class EmptyValueTests {

        @Test
        @DisplayName("Should handle empty values for optional fields")
        fun handleEmptyValuesForOptionalFields() {
            // Given
            val rawSubmission = FormSubmission(
                formId = "empty-test",
                values = mapOf(
                    "name-id" to "Eve"
                    // age-id and email-id are missing
                )
            )

            val submission = FormSubmissionResult(
                submission = rawSubmission,
                values = mapOf(
                    "name-id" to ControlValue.TextValue("Eve"),
                    "age-id" to ControlValue.EmptyValue,
                    "email-id" to ControlValue.EmptyValue
                ),
                valid = true
            )

            // When
            val user: OptionalFieldsUser = submission.bindTo()

            // Then
            assertEquals("Eve", user.name)
            assertNull(user.age)
            assertNull(user.email)
        }
    }

    @Nested
    @DisplayName("FormSubmission Timestamp Tests")
    inner class FormSubmissionTimestampTests {

        @Test
        @DisplayName("Should preserve timestamp from form submission")
        fun preserveTimestampFromFormSubmission() {
            // Given
            val fixedTime = Instant.parse("2023-05-15T14:30:45Z")
            val rawSubmission = FormSubmission(
                formId = "timestamp-test",
                values = mapOf("name-id" to "Frank"),
                timestamp = fixedTime
            )

            val submission = FormSubmissionResult(
                submission = rawSubmission,
                values = mapOf("name-id" to ControlValue.TextValue("Frank")),
                valid = true
            )

            // When/Then
            assertEquals(fixedTime, submission.submission.timestamp)
        }
    }
}
