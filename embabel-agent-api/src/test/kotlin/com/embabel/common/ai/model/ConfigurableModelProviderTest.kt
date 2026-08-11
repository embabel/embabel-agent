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
package com.embabel.common.ai.model

import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.PlaceholderEmbeddingService
import com.embabel.agent.spi.PlaceholderLlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.ModelProvider.Companion.BEST_ROLE
import com.embabel.common.ai.model.ModelProvider.Companion.CHEAPEST_ROLE
import tools.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import kotlin.test.assertContains

class ConfigurableModelProviderTest {

    private companion object {
        /**
         * Opaque identifiers, not live models: every service here wraps a mockk ChatModel, so
         * nothing reaches a provider and a retired catalogue id cannot break these tests. Named
         * anyway — the ids appear across a dozen assertions, and a reader should not have to
         * decide whether BEST_MODEL and DEFAULT_MODEL differ meaningfully in each one.
         */
        const val DEFAULT_MODEL = "gpt-4.1-mini"
        const val BEST_MODEL = "gpt-4.1"
        const val CHEAPEST_MODEL = "gpt-4.1-nano"
    }

    /**
     * Stands in for `SetupRequiredLlm`, which lives in the BYOK autoconfigure module this one
     * cannot depend on. [SpringAiLlmService] is a data class and so final; the real placeholder
     * carries the marker by delegation in the same way.
     */
    private val placeholderModel: LlmService<*> = object :
        LlmService<SpringAiLlmService> by SpringAiLlmService(
            "setup-required", "none", mockk<ChatModel>(),
        ),
        PlaceholderLlmService {}

    /**
     * Stands in for `SetupRequiredEmbedding`, for the same reason [placeholderModel] does: it lives
     * in the BYOK module this one cannot depend on. Refuses a dimension exactly as the real one
     * does, so a test that provisions from it fails here rather than in a deployment.
     */
    private fun placeholderEmbedding(): EmbeddingService = object : EmbeddingService,
        PlaceholderEmbeddingService {
        override val name = "setup-required-embedding"
        override val provider = "none"
        override val pricingModel: PricingModel? = null
        override val awaitingProviderKey = true
        override fun embed(text: String): FloatArray = error("no embedding service configured")
        override fun embed(texts: List<String>): List<FloatArray> = error("no embedding service configured")
        override val dimensions: Int get() = error("no embedding service configured")
    }

    private fun realEmbedding(name: String, dimensions: Int = 1536): EmbeddingService =
        object : EmbeddingService {
            override val name = name
            override val provider = "openai"
            override val pricingModel: PricingModel? = null
            override fun embed(text: String): FloatArray = FloatArray(dimensions)
            override fun embed(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
            override val dimensions = dimensions
        }

    private fun providerWith(
        llms: List<LlmService<*>>,
        properties: ConfigurableModelProviderProperties,
        embeddingServices: List<EmbeddingService> = emptyList(),
    ) = ConfigurableModelProvider(llms, embeddingServices, properties)

    @Nested
    inner class SetupRequiredMode {

        private val real: LlmService<*> =
            SpringAiLlmService(DEFAULT_MODEL, "openai", mockk<ChatModel>())

        @Test
        fun `a deployment awaiting a key boots with roles nothing can satisfy`() {
            val mp = providerWith(
                llms = listOf(placeholderModel),
                properties = ConfigurableModelProviderProperties(
                    llms = mapOf(BEST_ROLE to BEST_MODEL, CHEAPEST_ROLE to CHEAPEST_MODEL),
                    defaultLlm = "setup-required",
                ),
            )
            assertEquals("setup-required", mp.getLlm(DefaultModelSelectionCriteria).name)
        }

        @Test
        fun `default-llm naming an unregistered model falls back to the placeholder`() {
            // The realistic pure-BYOK application.yml: default-llm still names the model the
            // deployment wants once a key arrives, and the placeholder stands in until then.
            val mp = providerWith(
                llms = listOf(placeholderModel),
                properties = ConfigurableModelProviderProperties(defaultLlm = BEST_MODEL),
            )
            assertEquals("setup-required", mp.getLlm(DefaultModelSelectionCriteria).name)
        }

        @Test
        fun `a deployment holding a key still dies on a name nothing registers`() {
            /*
             * The other half of the gate. Making this a warning too - which an earlier revision
             * did - fixes BYOK by turning every keyed deployment's typo into a late failure at
             * whichever call first wants that role.
             */
            val e = assertThrows<IllegalStateException> {
                providerWith(
                    llms = listOf(real, placeholderModel),
                    properties = ConfigurableModelProviderProperties(
                        llms = mapOf(BEST_ROLE to BEST_MODEL),
                        defaultLlm = DEFAULT_MODEL,
                    ),
                )
            }
            assertContains(e.message!!, BEST_MODEL)
        }

        @Test
        fun `an unresolvable embedding role is tolerated while awaiting a key`() {
            providerWith(
                llms = listOf(placeholderModel),
                properties = ConfigurableModelProviderProperties(
                    embeddingServices = mapOf("default" to "text-embedding-3-small"),
                    defaultLlm = "setup-required",
                ),
            )
        }

        @Test
        fun `an unresolvable embedding role is still fatal for a deployment holding a key`() {
            // Same gate, no fallback: there is no embedding placeholder and there should not be
            // one, so this decides only whether the deployment starts.
            val e = assertThrows<IllegalStateException> {
                providerWith(
                    llms = listOf(real),
                    properties = ConfigurableModelProviderProperties(
                        embeddingServices = mapOf("default" to "text-embedding-3-small"),
                        defaultLlm = DEFAULT_MODEL,
                    ),
                )
            }
            assertContains(e.message!!, "text-embedding-3-small")
        }
    }

    /**
     * Custom EmbeddingService that does NOT extend AiModel.
     * Verifies that the framework works with non-Spring AI embedding implementations.
     */
    private class CustomEmbeddingService(
        override val name: String,
        override val provider: String,
    ) : EmbeddingService {
        override val dimensions: Int = 384
        override val pricingModel: PricingModel? = null
        override fun embed(text: String): FloatArray = FloatArray(dimensions)
        override fun embed(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
    }

    private val embeddingPricing: PricingModel = PerTokenPricingModel(
        usdPer1mInputTokens = 0.02,
        usdPer1mOutputTokens = 0.0,
    )

    private val mp: ModelProvider = ConfigurableModelProvider(
        llms = listOf(
            SpringAiLlmService("gpt40", "OpenAI", mockk<ChatModel>(), DefaultOptionsConverter),
            SpringAiLlmService(DEFAULT_MODEL, "OpenAI", mockk<ChatModel>(), DefaultOptionsConverter),
            SpringAiLlmService("embedding", "OpenAI", mockk<ChatModel>(), DefaultOptionsConverter)
        ),
        embeddingServices = listOf(
            SpringAiEmbeddingService(
                "text-embedding-3-small",
                "OpenAI",
                mockk<EmbeddingModel>(),
                pricingModel = embeddingPricing,
            )
        ),
        properties = ConfigurableModelProviderProperties(
            llms = mapOf(
                BEST_ROLE to "gpt40",
                CHEAPEST_ROLE to "gpt40"
            ),
            embeddingServices = mapOf(
                CHEAPEST_ROLE to "text-embedding-3-small"
            )
        ),
    )

    @Nested
    inner class ListTests {

        @Test
        fun llmRoles() {
            val roles = mp.listRoles(SpringAiLlmService::class.java)
            assertFalse(roles.isEmpty())
            assertContains(roles, BEST_ROLE)
        }

        @Test
        fun embeddingRoles() {
            val roles = mp.listRoles(EmbeddingService::class.java)
            assertFalse(roles.isEmpty())
            assertContains(roles, CHEAPEST_ROLE)
        }

        @Test
        fun llmNames() {
            val names = mp.listModelNames(SpringAiLlmService::class.java)
            assertFalse(names.isEmpty())
            assertContains(names, "gpt40")
        }

        @Test
        fun embeddingNames() {
            val roles = mp.listModelNames(EmbeddingService::class.java)
            assertFalse(roles.isEmpty())
            assertContains(roles, "text-embedding-3-small")
        }

        @Test
        fun `models are of correct type`() {
            val models = mp.listModels()
            assertFalse(models.isEmpty())
            assertContains(models.map { it.name }, "gpt40")
            assertContains(models.map { it.name }, "text-embedding-3-small")
            assertEquals(
                0,
                models.filterIsInstance<SpringAiLlmService>().size,
                "Should not have SpringAiLlm class, but safe metadata class",
            )
            assertEquals(
                0,
                models.filterIsInstance<EmbeddingService>().size,
                "Should not have EmbeddingService class, but safe metadata class",
            )
        }

        @Test
        fun `models are serializable`() {
            val models = mp.listModels()
            jacksonObjectMapper().writeValueAsString(models)
        }

        @Test
        fun `embedding metadata preserves pricingModel`() {
            val embedding = mp.listModels()
                .filterIsInstance<EmbeddingServiceMetadata>()
                .single { it.name == "text-embedding-3-small" }
            assertEquals(embeddingPricing, embedding.pricingModel)
        }

    }

    @Nested
    inner class Llms {

        @Test
        fun `no such role`() {
            assertThrows<NoSuitableModelException> { mp.getLlm(ByRoleModelSelectionCriteria("what in God's holy name are you blathering about?")) }
        }

        @Test
        fun `valid role`() {
            val llm = mp.getLlm(ByRoleModelSelectionCriteria(BEST_ROLE))
            assertNotNull(llm)
        }

        @Test
        fun `no such name`() {
            assertThrows<NoSuitableModelException> { mp.getLlm(ByNameModelSelectionCriteria("what in God's holy name are you blathering about?")) }
        }

        @Test
        fun `valid name`() {
            val llm = mp.getLlm(ByNameModelSelectionCriteria(DEFAULT_MODEL))
            assertNotNull(llm)
        }
    }

    @Nested
    inner class Embeddings {

        @Test
        fun `no such role`() {
            assertThrows<NoSuitableModelException> { mp.getEmbeddingService(ByRoleModelSelectionCriteria("what in God's holy name are you blathering about?")) }
        }

        @Test
        fun `valid role`() {
            val ember = mp.getEmbeddingService(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
            assertNotNull(ember)
        }
    }

    @Nested
    inner class CustomEmbeddingServiceTests {

        private val customMp = ConfigurableModelProvider(
            llms = listOf(
                SpringAiLlmService(DEFAULT_MODEL, "OpenAI", mockk<ChatModel>(), DefaultOptionsConverter),
            ),
            embeddingServices = listOf(
                CustomEmbeddingService("my-custom-embeddings", "CustomProvider"),
            ),
            properties = ConfigurableModelProviderProperties(
                embeddingServices = mapOf(
                    CHEAPEST_ROLE to "my-custom-embeddings"
                ),
            ),
        )

        @Test
        fun `custom embedding service works with listRoles`() {
            val roles = customMp.listRoles(EmbeddingService::class.java)
            assertContains(roles, CHEAPEST_ROLE)
        }

        @Test
        fun `custom embedding service works with listModelNames`() {
            val names = customMp.listModelNames(EmbeddingService::class.java)
            assertContains(names, "my-custom-embeddings")
        }

        @Test
        fun `custom embedding service returned by getEmbeddingService`() {
            val service = customMp.getEmbeddingService(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
            assertEquals("my-custom-embeddings", service.name)
            assertEquals("CustomProvider", service.provider)
            assertEquals(384, service.dimensions)
        }
    }

    /**
     * The embedding half of setup-required mode. Its LLM counterpart lives in [SetupRequiredMode];
     * these belong here rather than in the BYOK module because it is this class that decides what a
     * deployment awaiting a key is allowed to do.
     */
    @Nested
    inner class EmbeddingSetupRequiredMode {

        @Test
        fun `default-embedding-model naming an unregistered model falls back to the placeholder`() {
            // The realistic pure-BYOK application.yml: it still names the model the deployment wants
            // once a key arrives, and the placeholder stands in until then.
            val mp = providerWith(
                llms = listOf(placeholderModel),
                embeddingServices = listOf(placeholderEmbedding()),
                properties = ConfigurableModelProviderProperties(
                    defaultLlm = "setup-required",
                    defaultEmbeddingModel = "text-embedding-3-small",
                ),
            )

            assertTrue(mp.getEmbeddingService(DefaultModelSelectionCriteria).awaitingProviderKey)
        }

        @Test
        fun `a registered embedding model is never degraded to the placeholder`() {
            val mp = providerWith(
                llms = listOf(placeholderModel),
                embeddingServices = listOf(placeholderEmbedding(), realEmbedding("text-embedding-3-small")),
                properties = ConfigurableModelProviderProperties(
                    defaultLlm = "setup-required",
                    defaultEmbeddingModel = "text-embedding-3-small",
                ),
            )

            val resolved = mp.getEmbeddingService(DefaultModelSelectionCriteria)
            assertFalse(resolved.awaitingProviderKey)
            assertEquals(1536, resolved.dimensions)
        }

        @Test
        fun `with no placeholder an unresolvable default embedding model still fails`() {
            // Opt-in: without a placeholder the deployment is misconfigured, not waiting.
            val mp = providerWith(
                llms = listOf(placeholderModel),
                embeddingServices = listOf(realEmbedding("text-embedding-3-large", dimensions = 3072)),
                properties = ConfigurableModelProviderProperties(
                    defaultLlm = "setup-required",
                    defaultEmbeddingModel = "text-embedding-3-small",
                ),
            )

            val e = assertThrows<IllegalArgumentException> {
                mp.getEmbeddingService(DefaultModelSelectionCriteria)
            }
            assertContains(e.message!!, "text-embedding-3-small")
        }

        /**
         * The mixed deployment — a server-side chat key with embedding keys arriving at runtime —
         * which the LLM-derived gate used to kill during context refresh.
         */
        @Test
        fun `a real LLM with a placeholder embedding tolerates an unregistered embedding role`() {
            val mp = providerWith(
                llms = listOf(SpringAiLlmService(DEFAULT_MODEL, "openai", mockk<ChatModel>())),
                embeddingServices = listOf(placeholderEmbedding()),
                properties = ConfigurableModelProviderProperties(
                    embeddingServices = mapOf(BEST_ROLE to "text-embedding-3-small"),
                    defaultLlm = DEFAULT_MODEL,
                    defaultEmbeddingModel = "setup-required-embedding",
                ),
            )

            assertThrows<NoSuitableModelException> {
                mp.getEmbeddingService(ByRoleModelSelectionCriteria(BEST_ROLE))
            }
        }

        /**
         * And the complement, which is what keeps the gate from becoming permissive: with a real
         * embedding model registered, an unresolvable embedding role is a typo and still fatal.
         */
        @Test
        fun `a keyed deployment still dies on an embedding role nothing registers`() {
            assertThrows<IllegalStateException> {
                providerWith(
                    llms = listOf(SpringAiLlmService(DEFAULT_MODEL, "openai", mockk<ChatModel>())),
                    embeddingServices = listOf(realEmbedding("text-embedding-3-small")),
                    properties = ConfigurableModelProviderProperties(
                        embeddingServices = mapOf(BEST_ROLE to "nonexistent-embedding"),
                        defaultLlm = DEFAULT_MODEL,
                        defaultEmbeddingModel = "text-embedding-3-small",
                    ),
                )
            }
        }
    }
}
