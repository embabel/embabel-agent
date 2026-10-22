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
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.ModelProvider.Companion.CHEAPEST_ROLE
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.model.ChatModel

/**
 * [ModelPlatform]: one registration per provider platform, building every kind it lists.
 */
class ModelPlatformTest {

    private companion object {
        const val OPENAI = "openai"
        const val ANTHROPIC = "anthropic"
        const val CHAT_MODEL = "gpt-4.1-nano"
        const val EMBEDDING_MODEL = "text-embedding-3-small"
        const val DEFAULT_LLM = "gpt-4.1-mini"
        const val DOCUMENTS_ROLE = "documents"

        /** The provider of the deployment's own model; no role names a model for it, so startup checks nothing. */
        const val DEPLOYMENT_PROVIDER = "deployment"
    }

    private fun llm(name: String, provider: String): LlmService<*> =
        SpringAiLlmService(name, provider, mockk<ChatModel>(), DefaultOptionsConverter)

    /** A capability no shipped kind has, for a kind the test declares itself. */
    private interface ScoringModel : ModelCapability

    private class FakeEmbeddingService(
        override val name: String,
        override val provider: String,
    ) : EmbeddingService {
        override val pricingModel: PricingModel? = null
        override val dimensions = 8
        override fun embed(text: String) = FloatArray(dimensions)
        override fun embed(texts: List<String>) = texts.map { FloatArray(dimensions) }
        override fun infoString(verbose: Boolean?, indent: Int) = name
    }

    /**
     * A deployment with one registered model of its own provider, whose chat role
     * [CHEAPEST_ROLE] and embedding role [DOCUMENTS_ROLE] name a model for each of [OPENAI] and
     * [ANTHROPIC].
     */
    private fun provider(platforms: List<ModelPlatform>) = ConfigurableModelProvider(
        llms = listOf(llm(DEFAULT_LLM, DEPLOYMENT_PROVIDER)),
        embeddingServices = emptyList(),
        properties = ConfigurableModelProviderProperties(
            defaultLlm = DEFAULT_LLM,
            roles = mapOf(
                CHEAPEST_ROLE to mapOf(
                    OPENAI to LlmOptions.withModel(CHAT_MODEL),
                    ANTHROPIC to LlmOptions.withModel("claude-haiku-4-5"),
                ),
            ),
            embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(OPENAI to EMBEDDING_MODEL)),
        ),
        modelPlatforms = platforms,
    )

    /** Runs [block] as a call made with a key for [provider]. */
    private fun <T> withKeyFor(provider: String, block: () -> T): T =
        ModelSelectionContextHolder.with(ModelSelectionContext(credential = ProviderCredential(provider, "sk-test")), block)

    @Nested
    inner class Builder {

        private val credential = ProviderCredential(OPENAI, "sk-test")

        @Test
        fun `one platform builds each kind it was given`() {
            val chat = llm(CHAT_MODEL, OPENAI)
            val platform = ModelPlatform.builder()
                .builds(LlmService.KIND) { _, _ -> chat }
                .builds(EmbeddingService.KIND) { credential, model -> FakeEmbeddingService(model, credential.provider) }
                .build()

            // The platform lists both kinds.
            assertEquals(setOf(LlmService.KIND, EmbeddingService.KIND), platform.kinds)
            // Chat goes to the chat function.
            assertSame(chat, platform.model(credential, LlmService.KIND, CHAT_MODEL))
            // Embedding goes to the embedding function, with the model name passed through.
            assertEquals(EMBEDDING_MODEL, platform.model(credential, EmbeddingService.KIND, EMBEDDING_MODEL)?.name)
        }

        @Test
        fun `a kind the platform was not given builds nothing`() {
            val platform = platformBuilding(LlmService.KIND) { _, _ -> llm(CHAT_MODEL, OPENAI) }

            // Embedding was never given to the builder, so there is no model for it.
            assertNull(platform.model(credential, EmbeddingService.KIND, EMBEDDING_MODEL))
        }

        @Test
        fun `a kind given twice is rejected`() {
            val builder = ModelPlatform.builder().builds(LlmService.KIND) { _, _ -> null }

            // Two functions for one kind would make the answer depend on list order.
            assertThrows<IllegalArgumentException> { builder.builds(LlmService.KIND) { _, _ -> null } }
        }

        @Test
        fun `two kinds with the same name and capability are different kinds`() {
            val first = ModelKind<ScoringModel, ModelName>("scoring", ScoringModel::class.java)
            val second = ModelKind<ScoringModel, ModelName>("scoring", ScoringModel::class.java)

            // A kind equals only itself, so a set holds both.
            assertNotEquals(first, second)
            assertEquals(first, first)
            assertEquals(2, setOf(first, second).size)
        }

        @Test
        fun `builds returns a new builder and leaves the original unchanged`() {
            val chatOnly = ModelPlatform.builder().builds(LlmService.KIND) { _, _ -> null }
            chatOnly.builds(EmbeddingService.KIND) { _, _ -> null }

            // The second call returned a new builder, so the first still builds chat only.
            assertEquals(setOf(LlmService.KIND), chatOnly.build().kinds)
        }
    }

    @Nested
    inner class Selection {

        @Test
        fun `one platform serves a key's chat and embedding roles`() {
            val chat = llm(CHAT_MODEL, OPENAI)
            val platform = ModelPlatform.builder()
                .builds(LlmService.KIND) { _, _ -> chat }
                .builds(EmbeddingService.KIND) { credential, model -> FakeEmbeddingService(model, credential.provider) }
                .build()
            val mp = provider(listOf(platform))

            val resolvedChat = withKeyFor(OPENAI) { mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE)) }
            val resolvedEmbedding = withKeyFor(OPENAI) {
                mp.getEmbeddingService(ByRoleModelSelectionCriteria(DOCUMENTS_ROLE))
            }

            // The chat role was built by the platform's chat function.
            assertSame(chat, resolvedChat)
            // The embedding role was built by the same platform's embedding function.
            assertEquals(EMBEDDING_MODEL, resolvedEmbedding.name)
            assertEquals(OPENAI, resolvedEmbedding.provider)
        }

        @Test
        fun `a platform is asked only for the kinds it lists`() {
            val askedKinds = mutableListOf<ModelKind<*, *>>()
            // Hand-written rather than built, so that a request for a kind it does not list is seen.
            val chatOnly = object : ModelPlatform {
                override val kinds: Set<ModelKind<*, *>> = setOf(LlmService.KIND)
                override fun <C : ModelCapability> model(
                    credential: ProviderCredential,
                    kind: ModelKind<C, *>,
                    model: String,
                ): C? {
                    askedKinds += kind
                    return null
                }
            }
            val embedding = platformBuilding(EmbeddingService.KIND) { credential, model ->
                FakeEmbeddingService(model, credential.provider)
            }
            val mp = provider(listOf(chatOnly, embedding))

            val resolved = withKeyFor(OPENAI) { mp.getEmbeddingService(ByRoleModelSelectionCriteria(DOCUMENTS_ROLE)) }

            // The embedding platform answered.
            assertEquals(EMBEDDING_MODEL, resolved.name)
            // The chat-only platform was first in the list but was never asked for an embedding model.
            assertEquals(emptyList<ModelKind<*, *>>(), askedKinds)
        }

        @Test
        fun `a platform that returns null for a provider lets the next platform answer`() {
            val anthropicChat = llm("claude-haiku-4-5", ANTHROPIC)
            val askedProviders = mutableListOf<String>()
            val openAiOnly = platformBuilding(LlmService.KIND) { credential, model ->
                askedProviders += credential.provider
                // Builds only for its own provider.
                if (credential.provider == OPENAI) llm(model, OPENAI) else null
            }
            val anthropicOnly = platformBuilding(LlmService.KIND) { credential, _ ->
                if (credential.provider == ANTHROPIC) anthropicChat else null
            }
            val mp = provider(listOf(openAiOnly, anthropicOnly))

            val resolved = withKeyFor(ANTHROPIC) { mp.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE)) }

            // The first platform was asked, in bean order, and returned null.
            assertEquals(listOf(ANTHROPIC), askedProviders)
            // The second platform built the model.
            assertSame(anthropicChat, resolved)
        }
    }
}
