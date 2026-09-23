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

import com.embabel.agent.api.models.OllamaModels
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.local.LocalModelDiscoveryProperties
import com.embabel.common.ai.model.local.LocalModelKind
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.SpringAiEmbeddingService
import io.micrometer.observation.ObservationRegistry
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.ollama.OllamaEmbeddingModel
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for multi-ollama instance registration algorithm and logic.
 * Uses MockK to mock RestClient responses, testing the complete
 * configuration processing, mode detection, bean registration patterns, and naming strategies.
 */
class OllamaModelsConfigTest {

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
    private val modelResponseClass =
        Class.forName("com.embabel.agent.config.models.ollama.OllamaModelsConfig\$ModelResponse")
    private val modelDetailsClass =
        Class.forName("com.embabel.agent.config.models.ollama.OllamaModelsConfig\$ModelDetails")

    // Create test data using reflection to match the actual internal classes
    private val testModels by lazy {
        val modelDetailsConstructor =
            modelDetailsClass.getDeclaredConstructor(String::class.java, Long::class.java, String::class.java)
        val modelResponseConstructor = modelResponseClass.getDeclaredConstructor(List::class.java)

        val modelDetailsList = listOf(
            modelDetailsConstructor.newInstance("embeddinggemma:latest", 9876L, "2024-01-01T00:00:00Z"),
            modelDetailsConstructor.newInstance("deepseek-r1:latest", 54321L, "2024-01-01T00:00:00Z"),
            modelDetailsConstructor.newInstance("qwen3:latest", 11111L, "2024-01-01T00:00:00Z"),
            modelDetailsConstructor.newInstance("gemma3:latest", 12345L, "2024-01-01T00:00:00Z")
        )

        modelResponseConstructor.newInstance(modelDetailsList)
    }

    @BeforeEach
    fun setup() {
        clearAllMocks()

        // Mock basic dependencies
        every { mockBeanFactory.registerSingleton(any(), any()) } just Runs
        every { mockProperties.allWellKnownEmbeddingServiceNames() } returns setOf("embeddinggemma:latest")
        every { mockObservationRegistry.getIfUnique(any()) } returns ObservationRegistry.NOOP

        // Mock RestClient.Builder provider chain
        every { mockRestClientBuilder.observationRegistry(any()) } returns mockRestClientBuilder
        every { mockRestClientBuilder.clone() } returns mockClonedBuilder
        // The bounded discovery client is built off the clone, so it must chain back to the same
        // stubbed RestClient - otherwise model discovery silently finds nothing.
        every { mockClonedBuilder.requestFactory(any()) } returns mockClonedBuilder
        every { mockClonedBuilder.build() } returns mockRestClient
        every { mockRestClientBuilder.build() } returns mockRestClient
        every { mockRestClientBuilderProvider.getIfAvailable(any<java.util.function.Supplier<RestClient.Builder>>()) } returns mockRestClientBuilder

        // Setup standard RestClient call chain
        every { mockRestClient.get() } returns mockRequestHeadersUriSpec
        every { mockRequestHeadersUriSpec.uri(any<String>()) } returns mockRequestHeadersSpec
        every { mockRequestHeadersSpec.accept(MediaType.APPLICATION_JSON) } returns mockRequestHeadersSpec
        every { mockRequestHeadersSpec.retrieve() } returns mockResponseSpec

        // Spring 7's `org.springframework.web.client.body` reified Kotlin extension now
        // invokes `.hint(KType, T)` on the ResponseSpec before `.body(...)` so converters
        // can pick up the Kotlin reified type. Stub it to return the same mock so the
        // chain proceeds to `.body(...)`.
        every { mockResponseSpec.hint(any(), any()) } returns mockResponseSpec

        // Mock the body method using any() matcher to avoid type issues
        every { mockResponseSpec.body(any<ParameterizedTypeReference<Any>>()) } returns testModels
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }


    @Test
    fun `should handle default mode configuration`() {
        // Given - single Ollama instance configuration (legacy/backward compatible mode)
        // baseUrl provided, no multi-node configuration
        val config = createConfig("http://localhost:11434", null)

        // When - real method execution triggers HTTP discovery and bean registration
        config.ollamaModelsInitializer()

        // Then - Verify that actual calls occurred during config.registerModels()
        // Verify HTTP discovery happened
        verify { mockRestClient.get() }
        verify { mockResponseSpec.body(any<ParameterizedTypeReference<Any>>()) }

        // Verify all models were registered with simple naming (no node prefixes)
        // Embedding model gets "ollamaEmbeddingModel-" prefix, LLMs get "ollamaModel-" prefix
        // Model names normalized: "gemma3:latest" becomes "gemma3-latest"
        verify {
            mockBeanFactory.registerSingleton("ollamaEmbeddingModel-embeddinggemma-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-deepseek-r1-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-qwen3-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-gemma3-latest", any())
        }
        verify(exactly = 4) { mockBeanFactory.registerSingleton(any(), any()) }
    }

    @Test
    fun `should distinguish between LLM and embedding models`() {
        // Given - same setup as default mode to focus on model classification logic
        val config = createConfig("http://localhost:11434", null)

        // When - real method execution processes the mocked models
        config.ollamaModelsInitializer()

        // Then - Verify that actual calls occurred during config.registerModels()
        // Verify model classification works correctly based on embedding service names
        // "embeddinggemma:latest" is in allWellKnownEmbeddingServiceNames() so gets embedding prefix
        // Other models get LLM prefix
        verify {
            // LLM models get "ollamaModel-" prefix
            mockBeanFactory.registerSingleton("ollamaModel-gemma3-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-deepseek-r1-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-qwen3-latest", any())

            // Embedding model gets "ollamaEmbeddingModel-" prefix
            mockBeanFactory.registerSingleton("ollamaEmbeddingModel-embeddinggemma-latest", any())
        }
    }

    @Test
    fun `should handle multi-node configuration when some nodes fail`() {
        // Given - multi-node configuration where one node succeeds and one fails
        // No baseUrl (empty), only nodeProperties provided
        // Mock setup: 1st HTTP call (main node) returns testModels, 2nd HTTP call (gpu-server) returns null
        // This simulates gpu-server being down/unreachable/returning no models
        every { mockResponseSpec.body(any<ParameterizedTypeReference<Any>>()) } returns testModels andThen null
        val nodeProperties = OllamaNodeProperties().apply {
            nodes = listOf(
                OllamaNodeConfig().apply {
                    name = "main"
                    baseUrl = "http://localhost:11434"
                },
                OllamaNodeConfig().apply {
                    name = "gpu-server"
                    baseUrl = "http://localhost:11435"
                }
            )
        }
        val config = createConfig("", nodeProperties)

        // When - real method execution processes both nodes sequentially
        config.ollamaModelsInitializer()

        // Then - Verify that actual calls occurred during config.registerModels()
        // Both nodes should be attempted (2 HTTP calls to different URLs)
        verify(exactly = 2) { mockResponseSpec.body(any<ParameterizedTypeReference<Any>>()) }

        // Only main node should have beans registered because gpu-server returned null (no models)
        // When loadModelsFromUrl() returns null/empty, the models.forEach{} loop is skipped
        // so no registerSingleton() calls happen for gpu-server
        // Bean names include node prefix: "ollamaModel-{nodeName}-{modelName}"
        verify {
            mockBeanFactory.registerSingleton("ollamaEmbeddingModel-main-embeddinggemma-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-main-deepseek-r1-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-main-qwen3-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-main-gemma3-latest", any())
        }
        verify(exactly = 4) { mockBeanFactory.registerSingleton(any(), any()) }
    }

    @Test
    fun `should create unique bean names across multiple nodes with same models`() {
        // Given - multi-node configuration where BOTH nodes have identical models (uniqueness test)
        // No baseUrl (empty), only nodeProperties provided - this triggers multi-node-only mode
        // Mock setup: 1st HTTP call (main) returns testModels, 2nd HTTP call (gpu-server) returns SAME testModels
        // This tests the critical scenario: what happens when nodes have identical models?
        every { mockResponseSpec.body(any<ParameterizedTypeReference<Any>>()) } returns testModels andThen testModels
        val nodeProperties = OllamaNodeProperties().apply {
            nodes = listOf(
                OllamaNodeConfig().apply {
                    name = "main"
                    baseUrl = "http://localhost:11434"
                },
                OllamaNodeConfig().apply {
                    name = "gpu-server"
                    baseUrl = "http://localhost:11435"
                }
            )
        }
        val config = createConfig("", nodeProperties)

        // When - real method execution processes both nodes with identical model responses
        config.ollamaModelsInitializer()

        // Then - Verify that actual calls occurred during config.registerModels()
        // Both nodes should be attempted (2 HTTP calls to different URLs)
        verify(exactly = 2) { mockResponseSpec.body(any<ParameterizedTypeReference<Any>>()) }

        // CRITICAL: Both nodes should have beans registered with UNIQUE names using node prefixes
        // This tests uniqueness by having IDENTICAL models from both nodes but DIFFERENT bean names
        // Same model "gemma3:latest" from both nodes becomes 2 DIFFERENT beans:
        // - "ollamaModel-main-gemma3-latest" (from main node)
        // - "ollamaModel-gpu-server-gemma3-latest" (from gpu-server node)
        // WITHOUT node prefixes, Spring would fail with "Bean name 'ollamaModel-gemma3-latest' already exists" error
        verify {
            // main node beans (with "main" prefix)
            mockBeanFactory.registerSingleton("ollamaEmbeddingModel-main-embeddinggemma-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-main-deepseek-r1-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-main-qwen3-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-main-gemma3-latest", any())

            // gpu-server node beans (with "gpu-server" prefix) - SAME models, DIFFERENT names
            mockBeanFactory.registerSingleton("ollamaEmbeddingModel-gpu-server-embeddinggemma-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-gpu-server-deepseek-r1-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-gpu-server-qwen3-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-gpu-server-gemma3-latest", any())
        }
        verify(exactly = 8) { mockBeanFactory.registerSingleton(any(), any()) }
    }

    @Test
    fun `should handle hybrid mode configuration`() {
        // Given - hybrid mode: both baseUrl AND nodeProperties provided
        // This maintains backward compatibility while adding multi-node support
        // Same URL for both default and node (common production setup)
        val nodeProperties = OllamaNodeProperties().apply {
            nodes = listOf(
                OllamaNodeConfig().apply {
                    name = "main"
                    baseUrl = "http://localhost:11434"
                }
            )
        }
        val config = createConfig("http://localhost:11434", nodeProperties)

        // When - real method execution triggers both default and multi-node registration
        config.ollamaModelsInitializer()

        // Then - Verify that actual calls occurred during config.registerModels()
        // Should register models for BOTH default and node (8 total beans)
        // This allows existing code to work (default beans) while enabling new node-aware code
        verify {
            // Default registration (backward compatible)
            mockBeanFactory.registerSingleton("ollamaEmbeddingModel-embeddinggemma-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-deepseek-r1-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-qwen3-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-gemma3-latest", any())

            // Node registration (node-aware)
            mockBeanFactory.registerSingleton("ollamaEmbeddingModel-main-embeddinggemma-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-main-deepseek-r1-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-main-qwen3-latest", any())
            mockBeanFactory.registerSingleton("ollamaModel-main-gemma3-latest", any())
        }
        verify(exactly = 8) { mockBeanFactory.registerSingleton(any(), any()) }
    }

    @Test
    fun `should handle no configuration gracefully`() {
        // Given - no baseUrl and no nodeProperties (invalid configuration)
        val config = createConfig("", null)

        // When - real method execution detects invalid configuration
        config.ollamaModelsInitializer()

        // Then - Verify that actual calls occurred during config.registerModels()
        // Should skip all registration when no valid configuration provided
        verify(exactly = 0) { mockBeanFactory.registerSingleton(any(), any()) }
    }

    @Test
    fun `should handle empty model discovery`() {
        // Given - valid configuration but Ollama server returns no models
        // Create empty response using reflection to match actual internal class
        val emptyModels = modelResponseClass.getDeclaredConstructor(List::class.java).newInstance(emptyList<Any>())
        every { mockResponseSpec.body(any<ParameterizedTypeReference<Any>>()) } returns emptyModels
        val config = createConfig("http://localhost:11434", null)

        // When - real method execution processes empty model list
        config.ollamaModelsInitializer()

        // Then - Verify that actual calls occurred during config.registerModels()
        // Should complete HTTP discovery but register no beans when no models found
        verify(exactly = 0) { mockBeanFactory.registerSingleton(any(), any()) }
    }


    @Test
    fun `should normalize model names correctly via private method`() {
        // Given - test the core name normalization logic in isolation
        val config = createConfig("http://localhost:11434", null)

        // Create a mock Model using reflection to access the private class
        val modelClass = Class.forName("com.embabel.agent.config.models.ollama.OllamaModelsConfig\$Model")
        val modelConstructor =
            modelClass.getDeclaredConstructor(String::class.java, String::class.java, Long::class.java)
        val testModel = modelConstructor.newInstance("gemma3-latest", "gemma3:latest", 12345L)

        // When - invoke actual private normalizeModelNameForBean method via reflection
        val normalizeMethod = config.javaClass.getDeclaredMethod("normalizeModelNameForBean", modelClass)
        normalizeMethod.isAccessible = true
        val normalizedName = normalizeMethod.invoke(config, testModel) as String

        // Then - verify that colons are replaced with dashes and lowercased
        assertEquals("gemma3-latest", normalizedName)
    }

    @Test
    fun `should add node prefix to model names via private method with multi-node config`() {
        // Given - multi-node configuration to test the method in realistic context
        val nodeProperties = OllamaNodeProperties().apply {
            nodes = listOf(
                OllamaNodeConfig().apply {
                    name = "main"
                    baseUrl = "http://localhost:11434"
                },
                OllamaNodeConfig().apply {
                    name = "gpu-server"
                    baseUrl = "http://localhost:11435"
                }
            )
        }
        val config = createConfig("", nodeProperties)

        // When - invoke actual private createUniqueModelName method via reflection
        val uniqueMethod =
            config.javaClass.getDeclaredMethod("createUniqueModelName", String::class.java, String::class.java)
        uniqueMethod.isAccessible = true

        // Then - verify node prefix behavior in multi-node context
        // No node = no prefix (for default mode fallback)
        assertEquals("gemma3:latest", uniqueMethod.invoke(config, "gemma3:latest", null) as String)
        // With actual node names from configuration = add node prefix
        assertEquals("main-gemma3:latest", uniqueMethod.invoke(config, "gemma3:latest", "main") as String)
        assertEquals("gpu-server-llama3:latest", uniqueMethod.invoke(config, "llama3:latest", "gpu-server") as String)
    }

    @Test
    fun `should handle network errors gracefully`() {
        // Given - valid configuration but network error occurs during HTTP discovery
        every { mockResponseSpec.body(any<ParameterizedTypeReference<Any>>()) } throws RuntimeException("Connection refused")
        val config = createConfig("http://localhost:11434", null)
        // When - real method execution encounters network error
        config.ollamaModelsInitializer()

        // Then - Verify that actual calls occurred during config.registerModels()
        // Should handle network errors gracefully and register no beans when HTTP fails
        verify(exactly = 0) { mockBeanFactory.registerSingleton(any(), any()) }
    }

    @Test
    fun `should work with primary namespace baseUrl`() {
        // Given - primary namespace is set (simulates embabel.agent.platform.models.ollama.base-url)
        val config = createConfig("http://primary:11434", null)

        // When
        config.ollamaModelsInitializer()

        // Then - should use the provided baseUrl
        verify { mockRestClient.get() }
        verify { mockRequestHeadersUriSpec.uri("http://primary:11434/api/tags") }
    }

    @Test
    fun `should work with legacy namespace baseUrl as fallback`() {
        // Given - legacy namespace is set (simulates spring.ai.ollama.base-url fallback)
        // Note: The SpEL fallback ${new:${legacy:}} is resolved by Spring at injection time.
        // This test verifies the config works correctly with any baseUrl value,
        // which covers both primary and fallback scenarios.
        val config = createConfig("http://legacy:11434", null)

        // When
        config.ollamaModelsInitializer()

        // Then - should use the provided baseUrl (legacy in this case)
        verify { mockRestClient.get() }
        verify { mockRequestHeadersUriSpec.uri("http://legacy:11434/api/tags") }
    }

    /**
     * The per-call surface: what the server is serving NOW, rather than what it was serving while
     * the platform was being built. Driven through the published catalog, because that is what the
     * platform holds - the source itself is an implementation detail of this configuration.
     */
    @Nested
    inner class AskedPerCall {

        @Test
        fun `the catalog reports what the default endpoint is serving, split by kind`() {
            val catalog = createConfig("http://localhost:11434", null).ollamaLocalModelCatalog()

            assertEquals(
                setOf("deepseek-r1:latest", "qwen3:latest", "gemma3:latest"),
                catalog.servedNames(LocalModelKind.CHAT),
            )
            assertEquals(
                setOf("embeddinggemma:latest"),
                catalog.servedNames(LocalModelKind.EMBEDDING),
                "configuration decides the kind, since /api/tags does not say",
            )
        }

        @Test
        fun `a node's models are reported under the prefixed name registration would give them`() {
            val nodes = OllamaNodeProperties().apply {
                nodes = listOf(OllamaNodeConfig().apply { name = "gpu"; baseUrl = "http://gpu:11434" })
            }
            val catalog = createConfig("", nodes).ollamaLocalModelCatalog()

            assertTrue(
                catalog.servedNames(LocalModelKind.CHAT).contains("gpu-qwen3:latest"),
                "a node-scoped model is asked for under the prefixed name, so it must be listed under it",
            )
        }

        @Test
        fun `a served chat model resolves to a service by name`() {
            val catalog = createConfig("http://localhost:11434", null).ollamaLocalModelCatalog()

            val llm = catalog.llmNamed("qwen3:latest")

            assertNotNull(llm)
            assertEquals("qwen3:latest", llm.name)
            assertEquals(OllamaModels.PROVIDER, llm.provider)
        }

        @Test
        fun `a served embedding model resolves to a service by name`() {
            val catalog = createConfig("http://localhost:11434", null).ollamaLocalModelCatalog()

            val embedding = catalog.embeddingNamed("embeddinggemma:latest")

            assertNotNull(embedding)
            assertEquals("embeddinggemma:latest", embedding.name)
        }

        /**
         * A node-scoped model is ASKED for under its prefixed name and SERVED under its own, so the
         * request has to carry the raw name - getting this wrong sends the node a model name it has
         * never heard of.
         */
        @Test
        fun `a node-scoped chat model is listed under its prefixed name and requested under its raw one`() {
            val catalog = createConfig("", gpuNode()).ollamaLocalModelCatalog()

            val llm = assertIs<SpringAiLlmService>(catalog.llmNamed("gpu-qwen3:latest"))

            assertEquals("gpu-qwen3:latest", llm.name)
            assertEquals("qwen3:latest", llm.convertOptions(LlmOptions()).model)
            verify { mockRequestHeadersUriSpec.uri("http://gpu:11434/api/tags") }
        }

        @Test
        fun `a node-scoped embedding model is listed under its prefixed name and requested under its raw one`() {
            val catalog = createConfig("", gpuNode()).ollamaLocalModelCatalog()

            val embedding = assertIs<SpringAiEmbeddingService>(catalog.embeddingNamed("gpu-embeddinggemma:latest"))

            assertEquals("gpu-embeddinggemma:latest", embedding.name)
            assertEquals("embeddinggemma:latest", requestedEmbeddingModel(embedding))
        }

        /**
         * Several models on the default instance and on two nodes at once: every one of them must
         * resolve under the name it is listed by, and ask its server for the name that server knows.
         */
        @Test
        fun `every model on every endpoint resolves and requests its raw name`() {
            val nodes = OllamaNodeProperties().apply {
                nodes = listOf(
                    OllamaNodeConfig().apply { name = "gpu"; baseUrl = "http://gpu:11434" },
                    OllamaNodeConfig().apply { name = "cpu"; baseUrl = "http://cpu:11434" },
                )
            }
            val catalog = createConfig("http://localhost:11434", nodes).ollamaLocalModelCatalog()
            val chatModels = listOf("deepseek-r1:latest", "qwen3:latest", "gemma3:latest")

            assertEquals(
                chatModels.flatMap { listOf(it, "gpu-$it", "cpu-$it") }.toSet(),
                catalog.servedNames(LocalModelKind.CHAT),
            )
            listOf(null, "gpu", "cpu").forEach { node ->
                chatModels.forEach { raw ->
                    val listed = node?.let { "$it-$raw" } ?: raw
                    val llm = assertIs<SpringAiLlmService>(catalog.llmNamed(listed), listed)
                    assertEquals(listed, llm.name)
                    assertEquals(raw, llm.convertOptions(LlmOptions()).model, "request name for $listed")
                }
                val listedEmbedding = node?.let { "$it-embeddinggemma:latest" } ?: "embeddinggemma:latest"
                val embedding = assertIs<SpringAiEmbeddingService>(catalog.embeddingNamed(listedEmbedding))
                assertEquals("embeddinggemma:latest", requestedEmbeddingModel(embedding), "request name for $listedEmbedding")
            }
        }

        @Test
        fun `a model the server is not serving resolves to nothing`() {
            val catalog = createConfig("http://localhost:11434", null).ollamaLocalModelCatalog()

            assertNull(catalog.llmNamed("never-pulled:latest"))
            assertNull(catalog.embeddingNamed("never-pulled:latest"))
        }

        /**
         * Chat and embedding roles resolve from ONE catalog, so a runner asked for both is asked
         * once. Published as beans, so the platform can find them at all.
         */
        @Test
        fun `the three beans are one catalog, so the server is asked once for chat and embeddings`() {
            val config = createConfig("http://localhost:11434", null)

            // Same instance every time: with proxyBeanMethods = false a bean METHOD building its
            // own catalog would hand the two resolvers separate caches and double the HTTP.
            assertSame(config.ollamaLocalModelCatalog(), config.ollamaLocalModelCatalog())
            assertSame(config.ollamaLocalModelRoleResolver(), config.ollamaLocalModelRoleResolver())
            assertSame(
                config.ollamaLocalModelEmbeddingRoleResolver(),
                config.ollamaLocalModelEmbeddingRoleResolver(),
            )

            config.ollamaLocalModelCatalog().servedNames(LocalModelKind.CHAT)
            config.ollamaLocalModelCatalog().servedNames(LocalModelKind.EMBEDDING)

            verify(exactly = 1) { mockRequestHeadersUriSpec.uri("http://localhost:11434/api/tags") }
        }

        /**
         * The catalog is what declares late arrival, not the resolvers - they exist whether or not
         * discovery is on, so asking them would excuse a provider configured never to be asked.
         */
        @Test
        fun `the catalog declares late arrival only while discovery is enabled`() {
            assertEquals(
                OllamaModels.PROVIDER,
                createConfig("http://localhost:11434", null).ollamaLocalModelCatalog().lateArrivingProvider,
            )
            assertNull(
                createConfig(
                    "http://localhost:11434",
                    null,
                    LocalModelDiscoveryProperties(enabled = false),
                ).ollamaLocalModelCatalog().lateArrivingProvider,
            )
        }
    }

    // Helper methods
    private fun gpuNode() = OllamaNodeProperties().apply {
        nodes = listOf(OllamaNodeConfig().apply { name = "gpu"; baseUrl = "http://gpu:11434" })
    }

    /** Spring AI does not expose an embedding model's default options, and they are what is sent. */
    private fun requestedEmbeddingModel(service: SpringAiEmbeddingService): String? {
        val field = OllamaEmbeddingModel::class.java.getDeclaredField("options").apply { isAccessible = true }
        return (field.get(service.model) as OllamaEmbeddingOptions).model
    }

    private fun createConfig(
        baseUrl: String,
        nodeProperties: OllamaNodeProperties?,
        discovery: LocalModelDiscoveryProperties = LocalModelDiscoveryProperties(),
    ) =
        OllamaModelsConfig(
            baseUrl = baseUrl,
            nodeProperties = nodeProperties,
            configurableBeanFactory = mockBeanFactory,
            properties = mockProperties,
            observationRegistry = mockObservationRegistry,
            localModelDiscoveryProperties = discovery,
            restClientBuilder = mockRestClientBuilderProvider,
        )
}
