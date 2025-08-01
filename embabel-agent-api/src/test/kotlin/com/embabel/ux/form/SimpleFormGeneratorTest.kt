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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

data class AllTypesClass(

    @property:FormField("string-id")
    @Text(label = "custom label")
    val stringField: String,

    @property:FormField("int-id")
    val intField: Int,

    @property:FormField("long-id")
    val longField: Long,

    @property:FormField("double-id")
    val doubleField: Double,

    @property:FormField("float-id")
    val floatField: Float,

    @property:FormField("boolean-id")
    val booleanField: Boolean,

    @property:FormField("date-id")
    val dateField: LocalDate,

    @property:FormField("time-id")
    val timeField: LocalTime,

    @property:FormField("list-id")
    val listField: List<String>
)


class SimpleFormGeneratorTest {

    private val formGenerator = SimpleFormGenerator

    @Nested
    @DisplayName("Basic Form Generation Tests")
    inner class BasicFormGenerationTests {

        @Test
        @DisplayName("Single field data class")
        fun singleField() {
            data class Starry(
                @Text(label = "Enter your star sign")
                val sign: String,
            )

            // When
            val form = formGenerator.generateForm<Starry>("Test Form")

            // Then
            assertEquals("Test Form", form.title)
            assertEquals(2, form.controls.size)
            assertTrue(form.controls[0] is TextField, "Expected TextField, got ${form.controls}")
            assertEquals("Enter your star sign", (form.controls[0] as TextField).label)
        }

        @Test
        @DisplayName("Should generate form with correct title")
        fun shouldGenerateFormWithCorrectTitle() {
            // Given
            data class SimpleClass(
                @FormField("test-id")
                val testField: String
            )

            // When
            val form = formGenerator.generateForm<SimpleClass>("Test Form")

            // Then
            assertEquals("Test Form", form.title)
        }

        @Test
        @DisplayName("Should include a submit button")
        fun shouldIncludeSubmitButton() {
            // Given
            data class SimpleClass(
                @FormField("test-id")
                val testField: String
            )

            // When
            val form = formGenerator.generateForm<SimpleClass>("Test Form")

            // Then
            val lastControl = form.controls.last()
            assertTrue(lastControl is Button)
            assertEquals("Submit", (lastControl as Button).label)
        }

        @Test
        @DisplayName("Should skip properties with NotFormField annotation")
        fun shouldSkipPropertiesWithoutAnnotation() {
            // Given
            data class MixedClass(
                @property:FormField("annotated-id")
                val annotatedField: String,

                // Negative annotation, should be skipped
                @property:NoFormField
                val unannotatedField: String
            )

            // When
            val form = formGenerator.generateForm<MixedClass>("Mixed Form")

            // Then
            // Should have annotated field + submit button = 2 controls
            assertEquals(2, form.controls.size)

            val firstControl = form.controls.first()
            assertTrue(firstControl is TextField)
            assertEquals("annotated-id", (firstControl as TextField).id)
            assertEquals("Annotated Field", firstControl.label)
        }
    }

    @Nested
    @DisplayName("Control Type Tests")
    inner class ControlTypeTests {


        @Test
        @DisplayName("Should create correct control types for different property types")
        fun shouldCreateCorrectControlTypes() {
            // When
            val form = formGenerator.generateForm<AllTypesClass>("All Types Form")

            // Then - excluding the submit button
            val controls = form.controls.dropLast(1)
            assertEquals(9, controls.size)

            // Check each control type
            assertTrue(controls[0] is TextField, "First control should be text field, it is ${controls[0]}")
            assertEquals("string-id", (controls[0] as TextField).id)
            assertEquals("custom label", (controls[0] as TextField).label)

            assertTrue(controls[1] is TextField)
            assertEquals("int-id", (controls[1] as TextField).id)
            assertNotNull((controls[1] as TextField).validationPattern)

            assertTrue(controls[2] is TextField)
            assertEquals("long-id", (controls[2] as TextField).id)
            assertNotNull((controls[2] as TextField).validationPattern)

            assertTrue(controls[3] is TextField)
            assertEquals("double-id", (controls[3] as TextField).id)
            assertNotNull((controls[3] as TextField).validationPattern)

            assertTrue(controls[4] is TextField)
            assertEquals("float-id", (controls[4] as TextField).id)
            assertNotNull((controls[4] as TextField).validationPattern)

            assertTrue(controls[5] is Checkbox)
            assertEquals("boolean-id", (controls[5] as Checkbox).id)

            assertTrue(controls[6] is DatePicker)
            assertEquals("date-id", (controls[6] as DatePicker).id)

            assertTrue(controls[7] is TimePicker)
            assertEquals("time-id", (controls[7] as TimePicker).id)

            assertTrue(controls[8] is TextArea)
            assertEquals("list-id", (controls[8] as TextArea).id)
        }
    }

    @Nested
    @DisplayName("Label Formatting Tests")
    inner class LabelFormattingTests {

        @Test
        @DisplayName("Should format camelCase property names to proper labels")
        fun shouldFormatCamelCasePropertyNames() {
            // Given
            data class FormattingClass(
                @FormField("simple-id")
                val simpleField: String,

                @FormField("camel-case-id")
                val camelCaseField: String,

                @FormField("multiple-words-id")
                val multipleWordsInThisField: String
            )

            // When
            val form = formGenerator.generateForm<FormattingClass>("Formatting Form")

            // Then - excluding the submit button
            val controls = form.controls.dropLast(1)
            assertEquals(3, controls.size)

            // Check label formatting
            assertEquals("Simple Field", (controls[0] as TextField).label)
            assertEquals("Camel Case Field", (controls[1] as TextField).label)
            assertEquals("Multiple Words In This Field", (controls[2] as TextField).label)
        }
    }

    @Nested
    @DisplayName("Nullability Tests")
    inner class NullabilityTests {

        @Test
        @DisplayName("Should mark non-nullable fields as required")
        fun shouldMarkNonNullableFieldsAsRequired() {
            // Given
            data class NullabilityClass(
                @FormField("required-id")
                val requiredField: String,

                @FormField("optional-id")
                val optionalField: String?
            )

            // When
            val form = formGenerator.generateForm<NullabilityClass>("Nullability Form")

            // Then - excluding the submit button
            val controls = form.controls.dropLast(1)
            assertEquals(2, controls.size)

            // Check required state
            assertTrue((controls[0] as TextField).required, "First control should be required: ${controls[0]}")
            assertFalse((controls[1] as TextField).required)
        }
    }

    @Nested
    @DisplayName("Complex Class Tests")
    inner class ComplexClassTests {

        @Test
        @DisplayName("Should handle complex classes with inheritance")
        @Disabled("not yet working")
        fun shouldHandleInheritance() {
            // Given
            open class BaseClass(
                @FormField("base-id")
                open val baseField: String
            )

            data class DerivedClass(
                override val baseField: String,
                @FormField("derived-id")
                val derivedField: Int
            ) : BaseClass(baseField)

            // When
            val form = formGenerator.generateForm<DerivedClass>("Inheritance Form")

            // Then - excluding the submit button
            val controls = form.controls.dropLast(1)
            assertEquals(2, controls.size)

            // Check fields from both base and derived classes
            val controlIds = controls.map {
                when (it) {
                    is TextField -> it.id
                    else -> ""
                }
            }

            assertTrue(controlIds.contains("base-id"))
            assertTrue(controlIds.contains("derived-id"))
        }

        @Test
        @DisplayName("Should handle nested data classes")
        fun shouldHandleNestedDataClasses() {
            // Given
            data class NestedClass(
                @property:FormField("nested-id")
                val nestedField: String
            )

            data class OuterClass(
                @property:FormField("outer-id")
                val outerField: String,

                // No annotation on the nested object itself
                val nested: NestedClass
            )

            // When
            val form = formGenerator.generateForm<OuterClass>("Nested Form")

            // Then - excluding the submit button
            val controls = form.controls.dropLast(1)
            assertEquals(2, controls.size)

            // Should only include the annotated field from the outer class
            assertEquals("outer-id", (controls[0] as TextField).id)
        }
    }

    @Nested
    @DisplayName("Real-World Example Tests")
    inner class RealWorldExampleTests {

        @Test
        @DisplayName("Should generate a complete user registration form")
        fun shouldGenerateUserRegistrationForm() {
            // Given
            data class UserRegistration(
                @property:FormField("name-field")
                val fullName: String,

                @property:FormField("email-field")
                val emailAddress: String,

                @property:FormField("password-field")
                val password: String,

                @property:FormField("age-field")
                val age: Int,

                @property:FormField("terms-field")
                val acceptTerms: Boolean,

                @property:FormField("birth-date-field")
                val dateOfBirth: LocalDate,

                @property:FormField("bio-field")
                val biography: String? = null
            )

            // When
            val form = formGenerator.generateForm<UserRegistration>("User Registration")

            // Then - excluding the submit button
            val controls = form.controls.dropLast(1)
            assertEquals(7, controls.size)

            // Check specific fields
            val nameField = controls.find { it is TextField && it.id == "name-field" } as TextField?
            assertNotNull(nameField)
            assertEquals("Full Name", nameField?.label)
            assertTrue(nameField?.required ?: false)

            val emailField = controls.find { it is TextField && it.id == "email-field" } as TextField?
            assertNotNull(emailField)
            assertEquals("Email Address", emailField?.label)

            val termsField = controls.find { it is Checkbox && it.id == "terms-field" } as Checkbox?
            assertNotNull(termsField)
            assertEquals("Accept Terms", termsField?.label)

            val bioField = controls.find { it is TextField && it.id == "bio-field" } as TextField?
            assertNotNull(bioField)
            assertEquals("Biography", bioField?.label)
            assertFalse(bioField?.required ?: true)
        }
    }
}
