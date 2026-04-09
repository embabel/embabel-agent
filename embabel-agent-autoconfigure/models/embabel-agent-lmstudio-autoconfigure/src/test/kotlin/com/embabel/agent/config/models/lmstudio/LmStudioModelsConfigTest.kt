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
import org.springframework.web.reactive.function.client.WebClient

/**
 * Unit tests for LM Studio configuration and bean registration.
 */
class LmStudioModelsConfigTest {

    private val mockLmStudioProperties = mockk<LmStudioProperties>()
    private val mockBeanFactory = mockk<ConfigurableBeanFactory>(relaxed = true)
    private val mockObservationRegistry = mockk<ObjectProvider<ObservationRegistry>>()
    private val mockRestClientBuilderProvider = mockk<ObjectProvider<RestClient.Builder>>()
    private val mockRestClientBuilder = mockk<RestClient.Builder>()
    private val mockWebClientBuilderProvider = mockk<ObjectProvider<WebClient.Builder>>()
    private val mockWebClientBuilder = mockk<WebClient.Builder>(relaxed = true)
    private val mockRestClient = mockk<RestClient>()
    private val mockRequestHeadersUriSpec = mockk<RestClient.RequestHeadersUriSpec<*>>()
    private val mockRequestHeadersSpec = mockk<RestClient.RequestHeadersSpec<*>>()
    private val mockResponseSpec = mockk<RestClient.ResponseSpec>()

    @BeforeEach
    fun setup() {
        clearAllMocks()

        // Mock basic dependencies
        every { mockLmStudioProperties.baseUrl } returns "http://127.0.0.1:1234"
        every { mockLmStudioProperties.apiKey } returns null
        every { mockLmStudioProperties.retryTemplate(any()) } returns mockk(relaxed = true)
        every { mockBeanFactory.registerSingleton(any(), any()) } just Runs
        every { mockObservationRegistry.getIfUnique(any()) } returns ObservationRegistry.NOOP

        // Mock the ObjectProviders wrapping the builders (used by the constructor / parent class)
        every { mockRestClientBuilderProvider.getIfAvailable(any()) } returns mockRestClientBuilder
        every { mockWebClientBuilderProvider.getIfAvailable(any()) } returns mockWebClientBuilder

        // Mock RestClient.builder() static call (used by loadModelsFromUrl internally)
        mockkStatic("org.springframework.web.client.RestClient")
        every { RestClient.builder() } returns mockRestClientBuilder

        // Mock WebClient.Builder chain (used by parent class)
        every { mockWebClientBuilder.observationRegistry(any()) } returns mockWebClientBuilder
        every { mockWebClientBuilder.clone() } returns mockWebClientBuilder
        every { mockWebClientBuilder.build() } returns mockk(relaxed = true)

        every { mockRestClientBuilder.requestFactory(any()) } returns mockRestClientBuilder
        every { mockRestClientBuilder.build() } returns mockRestClient
        every { mockRestClientBuilder.observationRegistry(any()) } returns mockRestClientBuilder
        every { mockRestClientBuilder.clone() } returns mockRestClientBuilder
        every { mockRestClientBuilder.baseUrl(any<String>()) } returns mockRestClientBuilder
        every { mockRestClientBuilder.defaultHeaders(any()) } returns mockRestClientBuilder
        every { mockRestClientBuilder.defaultStatusHandler(any()) } returns mockRestClientBuilder

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
              "models": [
                { "key": "model-1" ,"type": "llm"},
                { "key": "user/model-2", "type": "embedding"}
              ]
            }
        """.trimIndent()

        every { mockResponseSpec.body(String::class.java) } returns jsonResponse

        val config = LmStudioModelsConfig(
            lmStudioProperties = mockLmStudioProperties,
            configurableBeanFactory = mockBeanFactory,
            observationRegistry = mockObservationRegistry,
            restClientBuilder = mockRestClientBuilderProvider,
            webClientBuilder = mockWebClientBuilderProvider
        )

        // When
        config.lmStudioModelsInitializer()

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
            lmStudioProperties = mockLmStudioProperties,
            configurableBeanFactory = mockBeanFactory,
            observationRegistry = mockObservationRegistry,
            restClientBuilder = mockRestClientBuilderProvider,
            webClientBuilder = mockWebClientBuilderProvider
        )

        // When
        config.lmStudioModelsInitializer()

        // Then
        verify(exactly = 0) { mockBeanFactory.registerSingleton(any(), any()) }
    }

    @Test
    fun `should handle network error`() {
        // Given
        every { mockResponseSpec.body(String::class.java) } throws RuntimeException("Connection refused")

        val config = LmStudioModelsConfig(
            lmStudioProperties = mockLmStudioProperties,
            configurableBeanFactory = mockBeanFactory,
            observationRegistry = mockObservationRegistry,
            restClientBuilder = mockRestClientBuilderProvider,
            webClientBuilder = mockWebClientBuilderProvider
        )

        // When
        config.lmStudioModelsInitializer()

        // Then
        verify(exactly = 0) { mockBeanFactory.registerSingleton(any(), any()) }
    }

    @Test
    fun `should normalize model names`() {
        // Given
        val jsonResponse = """
            {
              "models": [
                { "key": "Organization/Model:Name","type": "llm" }
              ]
            }
        """.trimIndent()

        every { mockResponseSpec.body(String::class.java) } returns jsonResponse

        val config = LmStudioModelsConfig(
            lmStudioProperties = mockLmStudioProperties,
            configurableBeanFactory = mockBeanFactory,
            observationRegistry = mockObservationRegistry,
            restClientBuilder = mockRestClientBuilderProvider,
            webClientBuilder = mockWebClientBuilderProvider
        )

        // When
        config.lmStudioModelsInitializer()

        // Then
        // "Organization/Model:Name" -> "organization-model-name"
        verify {
            mockBeanFactory.registerSingleton("lmStudioModel-organization-model-name", any())
        }
    }

}
