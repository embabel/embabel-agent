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
package com.embabel.agent.core.hitl

import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.testing.integration.IntegrationTestUtils.dummyAgentProcessRunning
import com.embabel.ux.form.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FormBindingRequestTest {

    private lateinit var processContext: ProcessContext
    private lateinit var form: Form
    private lateinit var formProcessor: DefaultFormProcessor
    private lateinit var formBinder: FormBinder<TestData>

    @BeforeEach
    fun setUp() {
        processContext = mockk(relaxed = true)

        form = Form(
            title = "Test Form",
            controls = listOf(
                TextField(
                    id = "name",
                    label = "Name",
                    required = true
                ),
                TextField(
                    id = "email",
                    label = "Email",
                    required = true
                )
            )
        )

        formProcessor = mockk()
        mockkConstructor(DefaultFormProcessor::class)
        every { anyConstructed<DefaultFormProcessor>().processSubmission(any(), any()) } returns mockk(relaxed = true)

        formBinder = mockk()
        mockkConstructor(FormBinder::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    inner class Constructor {
        @Test
        fun `should create FormBindingRequest with correct properties`() {
            val request = FormBindingRequest(form, TestData::class.java)

            assertEquals(form, request.payload)
            assertEquals(TestData::class.java, request.outputClass)
            assertEquals(false, request.persistent())
        }

        @Test
        fun `should create FormBindingRequest with persistent flag`() {
            val request = FormBindingRequest(form, TestData::class.java, persistent = true)

            assertEquals(true, request.persistent())
        }
    }

    @Nested
    inner class OnResponse {

        @Test
        fun `should process form submission and add bound instance to blackboard`() {
            val agentProcess = dummyAgentProcessRunning(evenMoreEvilWizard())
            val request = FormBindingRequest(form, TestData::class.java)
            val formSubmission = FormSubmission(
                formId = form.id,
                values = mapOf(
                    "name" to "John Doe",
                    "email" to "john@example.com"
                )
            )
            val response = FormResponse(
                awaitableId = request.id,
                formSubmission = formSubmission
            )

            val submissionResult = mockk<FormSubmissionResult>(relaxed = true)
            every { submissionResult.valid } returns true

            every {
                anyConstructed<DefaultFormProcessor>().processSubmission(
                    form,
                    formSubmission
                )
            } returns submissionResult

            val testData = TestData("John Doe", "john@example.com")
            every { anyConstructed<FormBinder<TestData>>().bind(submissionResult) } returns testData

            val result = request.onResponse(response, agentProcess)

            assertEquals(testData, agentProcess.lastResult())
            assertEquals(ResponseImpact.UPDATED, result)
        }

        @Test
        fun `should throw IllegalStateException when form submission is not valid`() {
            val agentProcess = mockk<AgentProcess>()
            val request = FormBindingRequest(form, TestData::class.java)
            val formSubmission = FormSubmission(
                formId = form.id,
                values = mapOf(
                    "name" to "John Doe"
                    // Missing required email field
                )
            )
            val response = FormResponse(
                awaitableId = request.id,
                formSubmission = formSubmission
            )

            val submissionResult = mockk<FormSubmissionResult>()
            every { submissionResult.valid } returns false
            every { submissionResult.validationErrors } returns mapOf("email" to "This field is required")

            every {
                anyConstructed<DefaultFormProcessor>().processSubmission(
                    form,
                    formSubmission
                )
            } returns submissionResult

            val exception = assertThrows<IllegalStateException> {
                request.onResponse(response, agentProcess)
            }

            assertTrue(exception.message!!.contains("Form submission is not valid"))
        }
    }

    @Nested
    inner class FormResponseClass {
        @Test
        fun `should create FormResponse with correct properties`() {
            // Arrange
            val awaitableId = UUID.randomUUID().toString()
            val formSubmission = mockk<FormSubmission>()

            // Act
            val response = FormResponse(
                awaitableId = awaitableId,
                formSubmission = formSubmission,
                persistent = true
            )

            // Assert
            assertEquals(awaitableId, response.awaitableId)
            assertEquals(formSubmission, response.formSubmission)
            assertEquals(true, response.persistent())
            assertNotNull(response.id)
            assertNotNull(response.timestamp)
        }
    }

    @Nested
    inner class `toString method` {
        @Test
        fun `should return infoString with verbose false`() {
            // Arrange
            val request = FormBindingRequest(form, TestData::class.java)

            // Act
            val result = request.toString()

            // Assert
            assertTrue(result.contains(request.id))
            assertTrue(result.contains("payload="))
        }
    }

    // Test data class used for binding
    data class TestData(
        val name: String,
        val email: String
    )
}
