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
package com.embabel.agent.config.models.ollama

import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.LlmOptions
import io.micrometer.observation.ObservationRegistry
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.util.ClassUtils
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Regression test for https://github.com/embabel/embabel-agent/issues/1735.
 *
 * Spring AI 2.0 no longer merges the [org.springframework.ai.ollama.OllamaChatModel]'s
 * configured default options into a prompt that already carries per-request options, and
 * `OllamaChatOptions` coerces a null model to `OllamaModel.MISTRAL.id()`. Because Embabel
 * always supplies per-request options built by [OllamaOptionsConverter] — which sets no
 * model — every request to a registered Ollama LLM would silently target `mistral`
 * instead of the model the service was registered for.
 */
class OllamaConfiguredModelBindingTest {

    private val registeredBeans = mutableMapOf<String, Any>()
    private val mockBeanFactory = mockk<ConfigurableBeanFactory>(relaxed = true)
    private val mockProperties = mockk<ConfigurableModelProviderProperties>()
    private val mockObservationRegistry = mockk<ObjectProvider<ObservationRegistry>>()
    private val mockRestClient = mockk<RestClient>()
    private val mockRestClientBuilder = mockk<RestClient.Builder>()
    private val mockClonedBuilder = mockk<RestClient.Builder>(relaxed = true)
    private val mockRestClientBuilderProvider = mockk<ObjectProvider<RestClient.Builder>>()
    private val mockRequestHeadersUriSpec = mockk<RestClient.RequestHeadersUriSpec<*>>()
    private val mockRequestHeadersSpec = mockk<RestClient.RequestHeadersSpec<*>>()
    private val mockResponseSpec = mockk<RestClient.ResponseSpec>()

    // Use reflection to access the actual internal ModelResponse class from OllamaModelsConfig
    private val modelResponseClass = ClassUtils.forName(
        "com.embabel.agent.config.models.ollama.OllamaModelsConfig\$ModelResponse",
        OllamaModelsConfig::class.java.classLoader,
    )
    private val modelDetailsClass = ClassUtils.forName(
        "com.embabel.agent.config.models.ollama.OllamaModelsConfig\$ModelDetails",
        OllamaModelsConfig::class.java.classLoader,
    )

    private val testModels by lazy {
        val modelDetailsConstructor =
            modelDetailsClass.getDeclaredConstructor(String::class.java, Long::class.java, String::class.java)
        val modelResponseConstructor = modelResponseClass.getDeclaredConstructor(List::class.java)

        modelResponseConstructor.newInstance(
            listOf(modelDetailsConstructor.newInstance("gemma3:latest", 12345L, "2024-01-01T00:00:00Z"))
        )
    }

    @BeforeEach
    fun setup() {
        clearAllMocks()
        registeredBeans.clear()

        every { mockBeanFactory.registerSingleton(any(), any()) } answers {
            registeredBeans[firstArg()] = secondArg()
        }
        every { mockProperties.allWellKnownEmbeddingServiceNames() } returns emptySet()
        every { mockObservationRegistry.getIfUnique(any()) } returns ObservationRegistry.NOOP

        every { mockRestClientBuilder.observationRegistry(any()) } returns mockRestClientBuilder
        every { mockRestClientBuilder.clone() } returns mockClonedBuilder
        every { mockRestClientBuilder.build() } returns mockRestClient
        every { mockRestClientBuilderProvider.getIfAvailable(any<java.util.function.Supplier<RestClient.Builder>>()) } returns mockRestClientBuilder

        every { mockRestClient.get() } returns mockRequestHeadersUriSpec
        every { mockRequestHeadersUriSpec.uri(any<String>()) } returns mockRequestHeadersSpec
        every { mockRequestHeadersSpec.accept(MediaType.APPLICATION_JSON) } returns mockRequestHeadersSpec
        every { mockRequestHeadersSpec.retrieve() } returns mockResponseSpec
        every { mockResponseSpec.hint(any(), any()) } returns mockResponseSpec
        every { mockResponseSpec.body(any<ParameterizedTypeReference<Any>>()) } returns testModels
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `registered Ollama LLM uses the configured model instead of the mistral default`() {
        // Given - a single discovered model "gemma3:latest" registered via default mode
        val config = createConfig("http://localhost:11434")
        config.ollamaModelsInitializer()
        val llm = assertIs<SpringAiLlmService>(
            registeredBeans["ollamaModel-gemma3-latest"],
            "expected an ollamaModel-gemma3-latest bean; registered: ${registeredBeans.keys}",
        )

        // When - per-request options are built exactly as ChatClientLlmOperations does
        // (post-#1818: SpringAiLlmService.convertOptions stamps the registered model
        // name onto the converter output)
        val chatOptions = assertIs<OllamaChatOptions>(llm.convertOptions(LlmOptions()))

        // Then - the request options must target the model the service was registered for,
        // not the OllamaChatOptions "mistral" fallback
        assertEquals("gemma3:latest", chatOptions.model)
    }

    // Helper methods
    private fun createConfig(baseUrl: String) =
        OllamaModelsConfig(
            baseUrl = baseUrl,
            nodeProperties = null,
            configurableBeanFactory = mockBeanFactory,
            properties = mockProperties,
            observationRegistry = mockObservationRegistry,
            restClientBuilder = mockRestClientBuilderProvider,
        )
}
