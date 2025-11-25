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
package com.embabel.agent.config.models.lmstudio

import io.micrometer.observation.ObservationRegistry
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

/**
 * Unit tests for LM Studio configuration and bean registration.
 */
class LmStudioModelsConfigTest {

    private val mockBeanFactory = mockk<ConfigurableBeanFactory>(relaxed = true)
    private val mockObservationRegistry = mockk<ObjectProvider<ObservationRegistry>>()
    private val mockRestClient = mockk<RestClient>()
    private val mockRequestHeadersUriSpec = mockk<RestClient.RequestHeadersUriSpec<*>>()
    private val mockRequestHeadersSpec = mockk<RestClient.RequestHeadersSpec<*>>()
    private val mockResponseSpec = mockk<RestClient.ResponseSpec>()

    @BeforeEach
    fun setup() {
        clearAllMocks()

        // Mock basic dependencies
        every { mockBeanFactory.registerSingleton(any(), any()) } just Runs
        every { mockObservationRegistry.getIfUnique(any()) } returns ObservationRegistry.NOOP

        // Mock RestClient.builder() static/chain
        mockkStatic("org.springframework.web.client.RestClient")
        val builder = mockk<RestClient.Builder>()
        every { RestClient.builder() } returns builder
        every { builder.requestFactory(any()) } returns builder
        every { builder.build() } returns mockRestClient
        every { builder.observationRegistry(any()) } returns builder
        every { builder.clone() } returns builder
        every { builder.baseUrl(any<String>()) } returns builder
        every { builder.defaultHeaders(any()) } returns builder
        every { builder.defaultStatusHandler(any()) } returns builder

        // Setup standard RestClient call chain
        every { mockRestClient.get() } returns mockRequestHeadersUriSpec
        every { mockRequestHeadersUriSpec.uri(any<String>()) } returns mockRequestHeadersSpec
        every { mockRequestHeadersSpec.accept(MediaType.APPLICATION_JSON) } returns mockRequestHeadersSpec
        every { mockRequestHeadersSpec.retrieve() } returns mockResponseSpec
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `should register discovered models`() {
        // Given
        val jsonResponse = """
            {
              "data": [
                { "id": "model-1" },
                { "id": "user/model-2" }
              ]
            }
        """.trimIndent()

        every { mockResponseSpec.body(String::class.java) } returns jsonResponse

        val config = LmStudioModelsConfig(
            baseUrl = "http://localhost:1234",
            apiKey = "lm-studio",
            configurableBeanFactory = mockBeanFactory,
            observationRegistry = mockObservationRegistry
        )

        // When
        config.registerModels()

        // Then
        verify {
            mockBeanFactory.registerSingleton("lmStudioModel-model-1", any())
            mockBeanFactory.registerSingleton("lmStudioModel-user-model-2", any())
        }
    }

    @Test
    fun `should handle empty response`() {
        // Given
        every { mockResponseSpec.body(String::class.java) } returns null

        val config = LmStudioModelsConfig(
            baseUrl = "http://localhost:1234",
            apiKey = "lm-studio",
            configurableBeanFactory = mockBeanFactory,
            observationRegistry = mockObservationRegistry
        )

        // When
        config.registerModels()

        // Then
        verify(exactly = 0) { mockBeanFactory.registerSingleton(any(), any()) }
    }

    @Test
    fun `should handle network error`() {
        // Given
        every { mockResponseSpec.body(String::class.java) } throws RuntimeException("Connection refused")

        val config = LmStudioModelsConfig(
            baseUrl = "http://localhost:1234",
            apiKey = "lm-studio",
            configurableBeanFactory = mockBeanFactory,
            observationRegistry = mockObservationRegistry
        )

        // When
        config.registerModels()

        // Then
        verify(exactly = 0) { mockBeanFactory.registerSingleton(any(), any()) }
    }

    @Test
    fun `should normalize model names`() {
        // Given
        val jsonResponse = """
            {
              "data": [
                { "id": "Organization/Model:Name" }
              ]
            }
        """.trimIndent()

        every { mockResponseSpec.body(String::class.java) } returns jsonResponse

        val config = LmStudioModelsConfig(
            baseUrl = "http://localhost:1234",
            apiKey = "lm-studio",
            configurableBeanFactory = mockBeanFactory,
            observationRegistry = mockObservationRegistry
        )

        // When
        config.registerModels()

        // Then
        // "Organization/Model:Name" -> "organization-model-name"
        verify {
            mockBeanFactory.registerSingleton("lmStudioModel-organization-model-name", any())
        }
    }
}
