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
package com.embabel.ux.form

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class DefaultFormProcessorTest {

    private lateinit var formProcessor: DefaultFormProcessor

    @BeforeEach
    fun setUp() {
        formProcessor = DefaultFormProcessor()
    }

    @Test
    fun `test process submission with text field`() {
        // Given
        val textField = TextField(
            label = "Name",
            required = true,
            id = "name-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(textField)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("name-field" to "John Doe")
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(result.valid)
        assertTrue(result.validationErrors.isEmpty())
        assertEquals(1, result.values.size)
        assertTrue(result.values["name-field"] is ControlValue.TextValue)
        assertEquals("John Doe", (result.values["name-field"] as ControlValue.TextValue).value)
    }

    @Test
    fun `test process submission with missing required field`() {
        // Given
        val textField = TextField(
            label = "Name",
            required = true,
            id = "name-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(textField)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("name-field" to "")
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        assertFalse(result.valid)
        assertEquals(1, result.validationErrors.size)
        assertTrue(result.validationErrors.containsKey("name-field"))
        assertTrue(
            result.validationErrors["name-field"]!!.contains("is required"),
            "Validation error message should relate to field being required': ${result.validationErrors["name-field"]}",
        )
    }

    @Test
    fun `test process submission with invalid pattern for text field`() {
        // Given
        val emailField = TextField(
            label = "Email",
            validationPattern = "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}\$",
            validationMessage = "Invalid email format",
            id = "email-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(emailField)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("email-field" to "invalid-email")
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertFalse(result.valid)
        assertEquals(1, result.validationErrors.size)
        assertTrue(result.validationErrors.containsKey("email-field"))
        assertEquals("Invalid email format", result.validationErrors["email-field"])
    }

    @Test
    fun `test process submission with text area`() {
        // Given
        val textArea = TextArea(
            label = "Description",
            rows = 5,
            id = "description-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(textArea)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("description-field" to "This is a long description\nWith multiple lines\nFor testing purposes")
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(result.valid)
        assertEquals(1, result.values.size)
        assertTrue(result.values["description-field"] is ControlValue.TextValue)
        assertEquals(
            "This is a long description\nWith multiple lines\nFor testing purposes",
            (result.values["description-field"] as ControlValue.TextValue).value
        )
    }

    @Test
    fun `test process submission with checkbox`() {
        // Given
        val checkbox = Checkbox(
            label = "Accept Terms",
            required = true,
            id = "terms-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(checkbox)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("terms-field" to true)
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(result.valid)
        assertEquals(1, result.values.size)
        assertTrue(result.values["terms-field"] is ControlValue.BooleanValue)
        assertTrue((result.values["terms-field"] as ControlValue.BooleanValue).value)
    }

    @Test
    fun `test process submission with unchecked required checkbox`() {
        // Given
        val checkbox = Checkbox(
            label = "Accept Terms",
            required = true,
            id = "terms-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(checkbox)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("terms-field" to false)
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertFalse(result.valid)
        assertEquals(1, result.validationErrors.size)
        assertTrue(result.validationErrors.containsKey("terms-field"))
    }

    @Test
    fun `test process submission with radio group`() {
        // Given
        val radioGroup = RadioGroup(
            label = "Gender",
            options = listOf(
                RadioOption("Male", "male"),
                RadioOption("Female", "female"),
                RadioOption("Other", "other")
            ),
            id = "gender-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(radioGroup)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("gender-field" to "female")
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(result.valid)
        assertEquals(1, result.values.size)
        assertTrue(result.values["gender-field"] is ControlValue.OptionValue)
        assertEquals("female", (result.values["gender-field"] as ControlValue.OptionValue).value)
    }

    @Test
    fun `test process submission with dropdown`() {
        // Given
        val dropdown = Dropdown(
            label = "Country",
            options = listOf(
                DropdownOption("United States", "us"),
                DropdownOption("Canada", "ca"),
                DropdownOption("United Kingdom", "uk")
            ),
            id = "country-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(dropdown)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("country-field" to "ca")
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(result.valid)
        assertEquals(1, result.values.size)
        assertTrue(result.values["country-field"] is ControlValue.OptionValue)
        assertEquals("ca", (result.values["country-field"] as ControlValue.OptionValue).value)
    }

    @Test
    fun `test process submission with date picker`() {
        // Given
        val datePicker = DatePicker(
            label = "Birth Date",
            id = "birth-date-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(datePicker)
        )

        val birthDate = "2000-01-15"
        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("birth-date-field" to birthDate)
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(result.valid)
        assertEquals(1, result.values.size)
        assertTrue(result.values["birth-date-field"] is ControlValue.DateValue)
        assertEquals(
            LocalDate.parse(birthDate),
            (result.values["birth-date-field"] as ControlValue.DateValue).value
        )
    }

    @Test
    fun `test process submission with time picker`() {
        // Given
        val timePicker = TimePicker(
            label = "Meeting Time",
            is24Hour = true,
            id = "meeting-time-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(timePicker)
        )

        val meetingTime = "14:30"
        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("meeting-time-field" to meetingTime)
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(result.valid)
        assertEquals(1, result.values.size)
        assertTrue(result.values["meeting-time-field"] is ControlValue.TimeValue)
        assertEquals(
            LocalTime.parse(meetingTime),
            (result.values["meeting-time-field"] as ControlValue.TimeValue).value
        )
    }

    @Test
    fun `test process submission with slider`() {
        // Given
        val slider = Slider(
            label = "Rating",
            min = 1.0,
            max = 5.0,
            step = 0.5,
            id = "rating-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(slider)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("rating-field" to 4.5)
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(result.valid)
        assertEquals(1, result.values.size)
        assertTrue(result.values["rating-field"] is ControlValue.NumberValue)
        assertEquals(4.5, (result.values["rating-field"] as ControlValue.NumberValue).value)
    }

    @Nested
    inner class InvalidSubmissions {

        @Test
        fun `test process submission with invalid dropdown option`() {
            // Given
            val dropdown = Dropdown(
                label = "Country",
                options = listOf(
                    DropdownOption("United States", "us"),
                    DropdownOption("Canada", "ca"),
                    DropdownOption("United Kingdom", "uk")
                ),
                id = "country-field"
            )

            val form = Form(
                title = "Test Form",
                controls = listOf(dropdown)
            )

            val submission = FormSubmission(
                formId = form.id,
                values = mapOf("country-field" to "fr") // Not in options
            )

            val result = formProcessor.processSubmission(form, submission)

            assertFalse(result.valid, "Submission should be invalid: ${result.infoString(true)}")
            assertEquals(1, result.validationErrors.size, "Should have 1 validation error: ${result.infoString(true)}")
            assertTrue(result.validationErrors.containsKey("country-field"))
        }

        @Test
        fun `test process submission with slider outside range`() {
            // Given
            val slider = Slider(
                label = "Rating",
                min = 1.0,
                max = 5.0,
                step = 0.5,
                id = "rating-field"
            )

            val form = Form(
                title = "Test Form",
                controls = listOf(slider)
            )

            val submission = FormSubmission(
                formId = form.id,
                values = mapOf("rating-field" to 7.5) // Outside max
            )

            // When
            val result = formProcessor.processSubmission(form, submission)

            // Then
            assertFalse(result.valid, "Result should be invalid: ${result.infoString(true)}")
            assertEquals(1, result.validationErrors.size)
            assertTrue(result.validationErrors.containsKey("rating-field"))
        }

        @Test
        fun `test process submission with too many files`() {
            // Given
            val fileUpload = FileUpload(
                label = "Documents",
                maxFiles = 2,
                id = "documents-field"
            )

            val form = Form(
                title = "Test Form",
                controls = listOf(fileUpload)
            )

            val fileIds = listOf("file-1", "file-2", "file-3") // 3 files but max is 2
            val fileNames = listOf("document1.pdf", "document2.pdf", "document3.pdf")
            val fileData = mapOf(
                "fileIds" to fileIds,
                "fileNames" to fileNames
            )

            val submission = FormSubmission(
                formId = form.id,
                values = mapOf("documents-field" to fileData)
            )

            // When
            val result = formProcessor.processSubmission(form, submission)

            // Then
            assertFalse(result.valid)
            assertTrue(result.validationErrors.containsKey("documents-field"))
        }

        @Test
        @Disabled("decide whether validation should be required here")
        fun `test process submission with date picker and min date validation`() {
            // Given
            val datePicker = DatePicker(
                label = "Future Appointment",
                minDate = "2025-01-01",
                id = "appointment-field"
            )

            val form = Form(
                title = "Test Form",
                controls = listOf(datePicker)
            )

            val pastDate = "2023-01-15"
            val submission = FormSubmission(
                formId = form.id,
                values = mapOf("appointment-field" to pastDate)
            )

            // When
            val result = formProcessor.processSubmission(form, submission)

            // Then
            assertFalse(result.valid, "Submission should be invalid due to past date")
            assertEquals(1, result.validationErrors.size)
            assertTrue(result.validationErrors.containsKey("appointment-field"))
        }
    }

    @Test
    fun `test process submission with toggle`() {
        // Given
        val toggle = Toggle(
            label = "Notifications",
            id = "notifications-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(toggle)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("notifications-field" to true)
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(result.valid)
        assertEquals(1, result.values.size)
        assertTrue(result.values["notifications-field"] is ControlValue.BooleanValue)
        assertTrue((result.values["notifications-field"] as ControlValue.BooleanValue).value)
    }

    @Test
    @Disabled("not yet working")
    fun `test process submission with file upload`() {
        // Given
        val fileUpload = FileUpload(
            label = "Documents",
            maxFiles = 2,
            id = "documents-field"
        )

        val form = Form(
            title = "Test Form",
            controls = listOf(fileUpload)
        )

        val fileIds = listOf("file-1", "file-2")
        val fileNames = listOf("document1.pdf", "document2.pdf")
        val fileData = mapOf(
            "fileIds" to fileIds,
            "fileNames" to fileNames
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf("documents-field" to fileData)
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(result.valid)
        assertEquals(1, result.values.size)
        assertTrue(result.values["documents-field"] is ControlValue.FileValue)
        assertEquals(fileIds, (result.values["documents-field"] as ControlValue.FileValue).fileIds)
        assertEquals(fileNames, (result.values["documents-field"] as ControlValue.FileValue).fileNames)
    }

    @Test
    fun `test process submission with multiple controls`() {
        // Given
        val nameField = TextField(label = "Name", required = true, id = "name-field")
        val emailField = TextField(
            label = "Email",
            validationPattern = "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}\$",
            id = "email-field"
        )
        val countryDropdown = Dropdown(
            label = "Country",
            options = listOf(
                DropdownOption("United States", "us"),
                DropdownOption("Canada", "ca")
            ),
            id = "country-field"
        )
        val termsCheckbox = Checkbox(label = "Accept Terms", required = true, id = "terms-field")

        val form = Form(
            title = "Registration Form",
            controls = listOf(nameField, emailField, countryDropdown, termsCheckbox)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf(
                "name-field" to "John Doe",
                "email-field" to "john.doe@example.com",
                "country-field" to "us",
                "terms-field" to true
            )
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(result.valid)
        assertTrue(result.validationErrors.isEmpty())
        assertEquals(4, result.values.size)
    }

    @Test
    fun `test process submission with partially valid controls`() {
        // Given
        val nameField = TextField(label = "Name", required = true, id = "name-field")
        val emailField = TextField(
            label = "Email",
            validationPattern = "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}\$",
            validationMessage = "Invalid email format",
            id = "email-field"
        )
        val countryDropdown = Dropdown(
            label = "Country",
            options = listOf(
                DropdownOption("United States", "us"),
                DropdownOption("Canada", "ca")
            ),
            id = "country-field"
        )
        val termsCheckbox = Checkbox(label = "Accept Terms", required = true, id = "terms-field")

        val form = Form(
            title = "Registration Form",
            controls = listOf(nameField, emailField, countryDropdown, termsCheckbox)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf(
                "name-field" to "John Doe",
                "email-field" to "invalid-email", // Invalid email
                "country-field" to "us",
                "terms-field" to false // Required but false
            )
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertFalse(result.valid)
        assertEquals(2, result.validationErrors.size)
        assertTrue(result.validationErrors.containsKey("email-field"))
        assertEquals("Invalid email format", result.validationErrors["email-field"])
        assertTrue(result.validationErrors.containsKey("terms-field"))
    }

    @Test
    fun `test process submission with missing control`() {
        // Given
        val nameField = TextField(label = "Name", required = true, id = "name-field")
        val emailField = TextField(label = "Email", id = "email-field", required = false)

        val form = Form(
            title = "Simple Form",
            controls = listOf(nameField, emailField)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf(
                "name-field" to "John Doe"
                // email-field is missing
            )
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(
            result.valid,
            "Form should be valid because email is not required: ${result.infoString(true)}",
        ) // Valid because email is not required
        assertEquals(2, result.values.size)
        assertTrue(result.values["email-field"] is ControlValue.EmptyValue)
    }

    @Test
    fun `test process submission with button control`() {
        // Given
        val nameField = TextField(label = "Name", id = "name-field")
        val submitButton = Button(label = "Submit", id = "submit-button")

        val form = Form(
            title = "Button Test Form",
            controls = listOf(nameField, submitButton)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf(
                "name-field" to "John Doe",
                "submit-button" to true
            )
        )

        // When
        val result = formProcessor.processSubmission(form, submission)

        // Then
        assertTrue(result.valid)
        assertEquals(
            2,
            result.values.size,
            "Should have 2 result values: " + result.infoString(true)
        )
        assertTrue(result.values.containsKey("submit-button"))
    }

    @Test
    fun `test process submission with disabled control`() {
        // Given
        val nameField = TextField(label = "Name", id = "name-field")
        val emailField = TextField(
            label = "Email",
            disabled = true,
            id = "email-field"
        )

        val form = Form(
            title = "Disabled Control Form",
            controls = listOf(nameField, emailField)
        )

        val submission = FormSubmission(
            formId = form.id,
            values = mapOf(
                "name-field" to "John Doe",
                "email-field" to "john.doe@example.com" // This should be ignored since the field is disabled
            )
        )

        val result = formProcessor.processSubmission(form, submission)

        assertTrue(result.valid)
        assertEquals(1, result.values.size)
        assertNull(
            result.values["email-field"],
            "Disabled field should not be returned, has ${result.infoString(true)}",
        )
    }
}
