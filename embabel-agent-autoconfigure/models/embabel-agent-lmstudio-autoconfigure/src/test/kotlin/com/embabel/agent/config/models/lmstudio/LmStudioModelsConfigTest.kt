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

import com.embabel.agent.api.models.LmStudioModels
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.local.LocalModelDiscoveryProperties
import com.embabel.common.ai.model.local.LocalModelKind
import io.micrometer.observation.ObservationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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
            modelProviderProperties = ConfigurableModelProviderProperties(),
            localModelDiscoveryProperties = LocalModelDiscoveryProperties(),
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

    /**
     * The per-call surface: what LM Studio is serving NOW. Unlike Docker and Ollama the server
     * reports a type per model, so nothing here consults configuration to tell the two apart.
     */
    @Nested
    inner class AskedPerCall {

        private fun servingBoth(): LmStudioModelsConfig {
            every { mockResponseSpec.body(String::class.java) } returns """
                {
                  "models": [
                    { "key": "qwen3", "type": "llm" },
                    { "key": "nomic-embed-text", "type": "embedding" }
                  ]
                }
            """.trimIndent()
            return createConfig()
        }

        @Test
        fun `the catalog splits models by the type the server itself reports`() {
            val catalog = servingBoth().lmStudioLocalModelCatalog()

            assertEquals(setOf("qwen3"), catalog.servedNames(LocalModelKind.CHAT))
            assertEquals(setOf("nomic-embed-text"), catalog.servedNames(LocalModelKind.EMBEDDING))
        }

        @Test
        fun `a model loaded after boot resolves to a service by name`() {
            val catalog = servingBoth().lmStudioLocalModelCatalog()

            val llm = catalog.llmNamed("qwen3")
            assertNotNull(llm)
            assertEquals("qwen3", llm.name)

            val embedding = catalog.embeddingNamed("nomic-embed-text")
            assertNotNull(embedding)
            assertEquals("nomic-embed-text", embedding.name)
        }

        /**
         * Kind is decisive for a caller who named a model: it has given no other signal for which
         * of the two lists it meant, and answering an embedding request with a chat model is worse
         * than declining.
         */
        @Test
        fun `a chat model does not answer an embedding request by name, or the reverse`() {
            val catalog = servingBoth().lmStudioLocalModelCatalog()

            assertNull(catalog.embeddingNamed("qwen3"))
            assertNull(catalog.llmNamed("nomic-embed-text"))
        }

        @Test
        fun `the three beans are one catalog, so the server is asked once`() {
            val config = servingBoth()

            assertSame(config.lmStudioLocalModelCatalog(), config.lmStudioLocalModelCatalog())
            assertSame(config.lmStudioLocalModelRoleResolver(), config.lmStudioLocalModelRoleResolver())
            assertSame(
                config.lmStudioLocalModelEmbeddingRoleResolver(),
                config.lmStudioLocalModelEmbeddingRoleResolver(),
            )

            config.lmStudioLocalModelCatalog().servedNames(LocalModelKind.CHAT)
            config.lmStudioLocalModelCatalog().servedNames(LocalModelKind.EMBEDDING)

            verify(exactly = 1) { mockResponseSpec.body(String::class.java) }
        }

        @Test
        fun `the catalog declares late arrival only while discovery is enabled`() {
            assertEquals(
                LmStudioModels.PROVIDER,
                servingBoth().lmStudioLocalModelCatalog().lateArrivingProvider,
            )
            assertNull(
                createConfig(LocalModelDiscoveryProperties(enabled = false))
                    .lmStudioLocalModelCatalog().lateArrivingProvider,
            )
        }
    }

    private fun createConfig(
        discovery: LocalModelDiscoveryProperties = LocalModelDiscoveryProperties(),
    ) = LmStudioModelsConfig(
        lmStudioProperties = mockLmStudioProperties,
        configurableBeanFactory = mockBeanFactory,
        modelProviderProperties = ConfigurableModelProviderProperties(),
        localModelDiscoveryProperties = discovery,
        observationRegistry = mockObservationRegistry,
        restClientBuilder = mockRestClientBuilderProvider,
        webClientBuilder = mockWebClientBuilderProvider,
    )

    @Test
    fun `should handle empty response`() {
        // Given
        every { mockResponseSpec.body(String::class.java) } returns null

        val config = LmStudioModelsConfig(
            lmStudioProperties = mockLmStudioProperties,
            configurableBeanFactory = mockBeanFactory,
            modelProviderProperties = ConfigurableModelProviderProperties(),
            localModelDiscoveryProperties = LocalModelDiscoveryProperties(),
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
            modelProviderProperties = ConfigurableModelProviderProperties(),
            localModelDiscoveryProperties = LocalModelDiscoveryProperties(),
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
            modelProviderProperties = ConfigurableModelProviderProperties(),
            localModelDiscoveryProperties = LocalModelDiscoveryProperties(),
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


    // -----------------------------------------------------------------------
    // The chat base URL. Discovery appends `/v1/models` to the configured value
    // while the OpenAI SDK appends `/chat/completions` to it, so one of the two
    // has to normalise — and getting this wrong is silent: models list fine and
    // every completion 404s as "`choices` is not set".
    // -----------------------------------------------------------------------

    @Test
    fun `chat base url gains the v1 the SDK expects`() {
        assertEquals("${LmStudioProperties.DEFAULT_BASE_URL}/v1", LmStudioModelsConfig.chatBaseUrl(LmStudioProperties.DEFAULT_BASE_URL))
    }

    @Test
    fun `a trailing slash does not produce a double slash`() {
        assertEquals("${LmStudioProperties.DEFAULT_BASE_URL}/v1", LmStudioModelsConfig.chatBaseUrl("${LmStudioProperties.DEFAULT_BASE_URL}/"))
    }

    @Test
    fun `an operator who already wrote v1 is not given two`() {
        assertEquals("http://host.docker.internal:${LmStudioProperties.DEFAULT_PORT}/v1", LmStudioModelsConfig.chatBaseUrl("http://host.docker.internal:${LmStudioProperties.DEFAULT_PORT}/v1"))
    }
}
