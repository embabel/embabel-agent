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
package com.embabel.agent.config.models.byok

import com.embabel.agent.spi.PlaceholderEmbeddingService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.ByRoleModelSelectionCriteria
import com.embabel.common.ai.model.ConfigurableModelProvider
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.DefaultModelSelectionCriteria
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.NoSuitableModelException
import com.embabel.common.ai.model.PricingModel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The embedding placeholder exists so a BYOK application that uses RAG or memory starts with no
 * keys, the same as one that only chats. These tests pin the two halves of that: the model provider
 * resolves, and everything that would need a real model fails with something an operator can act on
 * — `dimensions` above all.
 */
class SetupRequiredEmbeddingTest {

    @Test
    fun `placeholder is named by the well-known constants and carries the marker`() {
        val service = SetupRequiredEmbedding.embeddingService()

        assertThat(service.name).isEqualTo(SetupRequiredEmbedding.NAME)
        assertThat(service.provider).isEqualTo(SetupRequiredEmbedding.PROVIDER)
        /*
         * The marker is the whole point: a consumer that provisions a vector index reads it to
         * decide whether to skip. Losing it would not fail here — it would let that consumer build
         * an index from a dimension nobody can vouch for.
         */
        assertThat(service).isInstanceOf(PlaceholderEmbeddingService::class.java)
        // Identifies itself in logs and model listings — an operator reading "why is retrieval
        // empty" should see which service answered, not a bare class@hashcode.
        assertThat(service.toString()).contains(SetupRequiredEmbedding.NAME)
    }

    /**
     * The rule this file exists to defend. There is no defensible dimension for a placeholder: any
     * number would become the shape of a vector index, writes to it would succeed, and a real model
     * configured later would silently disagree with everything already stored. Failing is the only
     * answer that surfaces as an error rather than as bad results.
     */
    @Test
    fun `dimensions fails rather than answering`() {
        assertThatThrownBy { SetupRequiredEmbedding.embeddingService().dimensions }
            .isInstanceOf(NoEmbeddingServiceConfiguredException::class.java)
    }

    @Test
    fun `embedding fails with an actionable error`() {
        val service = SetupRequiredEmbedding.embeddingService()

        assertThatThrownBy { service.embed("anything") }
            .isInstanceOf(NoEmbeddingServiceConfiguredException::class.java)
            .hasMessageContaining("No embedding service is configured")
        assertThatThrownBy { service.embed(listOf("anything")) }
            .isInstanceOf(NoEmbeddingServiceConfiguredException::class.java)
    }

    @Test
    fun `model provider with only the placeholder resolves the default embedding service`() {
        val modelProvider = ConfigurableModelProvider(
            llms = listOf(SetupRequiredLlm.llmService()),
            embeddingServices = listOf(SetupRequiredEmbedding.embeddingService()),
            properties = ConfigurableModelProviderProperties(
                defaultLlm = SetupRequiredLlm.NAME,
                defaultEmbeddingModel = SetupRequiredEmbedding.NAME,
            ),
        )

        assertThat(modelProvider.getEmbeddingService(DefaultModelSelectionCriteria).name)
            .isEqualTo(SetupRequiredEmbedding.NAME)
    }

    /**
     * The case that motivated the issue: a BYOK deployment names an embedding model it has no key
     * for yet, so nothing registered it. Before the fallback that threw, and the throw happened
     * while consumer beans were being created — taking the context down before any BYOK code could
     * run. Now it starts, and fails at the point of use instead.
     */
    @Test
    fun `an unregistered default embedding model falls back to the placeholder`() {
        val modelProvider = ConfigurableModelProvider(
            llms = listOf(SetupRequiredLlm.llmService()),
            embeddingServices = listOf(SetupRequiredEmbedding.embeddingService()),
            properties = ConfigurableModelProviderProperties(
                defaultLlm = SetupRequiredLlm.NAME,
                defaultEmbeddingModel = "text-embedding-3-small",
            ),
        )

        val resolved = modelProvider.getEmbeddingService(DefaultModelSelectionCriteria)

        assertThat(resolved).isInstanceOf(PlaceholderEmbeddingService::class.java)
        assertThatThrownBy { resolved.dimensions }
            .isInstanceOf(NoEmbeddingServiceConfiguredException::class.java)
    }

    /**
     * A deployment that HAS a key must never be degraded to the placeholder — the fallback is for
     * the deployment awaiting one, not a way to paper over a model that failed to register.
     */
    @Test
    fun `a registered embedding model is preferred over the placeholder`() {
        val real = FakeEmbeddingService("text-embedding-3-small", dimensions = 1536)
        val modelProvider = ConfigurableModelProvider(
            llms = listOf(SetupRequiredLlm.llmService()),
            embeddingServices = listOf(SetupRequiredEmbedding.embeddingService(), real),
            properties = ConfigurableModelProviderProperties(
                defaultLlm = SetupRequiredLlm.NAME,
                defaultEmbeddingModel = "text-embedding-3-small",
            ),
        )

        val resolved = modelProvider.getEmbeddingService(DefaultModelSelectionCriteria)

        assertThat(resolved).isNotInstanceOf(PlaceholderEmbeddingService::class.java)
        assertThat(resolved.dimensions).isEqualTo(1536)
    }

    /**
     * With no placeholder registered, an unresolvable name is still a typo and still fatal. The
     * fallback is opt-in by carrying the placeholder, exactly as on the LLM side.
     */
    @Test
    fun `without a placeholder an unresolvable embedding model still fails`() {
        val modelProvider = ConfigurableModelProvider(
            llms = listOf(SetupRequiredLlm.llmService()),
            embeddingServices = listOf(FakeEmbeddingService("some-other-model", dimensions = 768)),
            properties = ConfigurableModelProviderProperties(
                defaultLlm = SetupRequiredLlm.NAME,
                defaultEmbeddingModel = "text-embedding-3-small",
            ),
        )

        assertThatThrownBy { modelProvider.getEmbeddingService(DefaultModelSelectionCriteria) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("text-embedding-3-small")
    }

    /**
     * The mixed deployment: a server-side chat key, embedding keys arriving at runtime. Before the
     * embedding gate was split from the LLM one this failed its context refresh on the embedding
     * ROLE — the placeholder rescued `default-embedding-model` while the role check still treated
     * the unregistered name as a typo, which is the startup failure #1909 exists to remove.
     */
    @Test
    fun `a real LLM with byok embeddings still starts with an unregistered embedding role`() {
        val provider = ConfigurableModelProvider(
            llms = listOf(SpringAiLlmService("real-llm", "acme", SetupRequiredChatModel())),
            embeddingServices = listOf(SetupRequiredEmbedding.embeddingService()),
            properties = ConfigurableModelProviderProperties(
                defaultLlm = "real-llm",
                defaultEmbeddingModel = SetupRequiredEmbedding.NAME,
                embeddingServices = mapOf("memory" to "text-embedding-3-small"),
            ),
        )

        // Started. Asking for that role still fails, which is the point — deferred, not softened.
        assertThatThrownBy { provider.getEmbeddingService(ByRoleModelSelectionCriteria("memory")) }
            .isInstanceOf(NoSuitableModelException::class.java)
    }

    /**
     * The complement, so the split gate cannot quietly become permissive: with no embedding
     * placeholder anywhere, an unresolvable embedding role is still a typo and still fatal.
     */
    @Test
    fun `without an embedding placeholder an unregistered embedding role is still fatal`() {
        assertThatThrownBy {
            ConfigurableModelProvider(
                llms = listOf(SpringAiLlmService("real-llm", "acme", SetupRequiredChatModel())),
                embeddingServices = listOf(FakeEmbeddingService("text-embedding-3-large", dimensions = 3072)),
                properties = ConfigurableModelProviderProperties(
                    defaultLlm = "real-llm",
                    defaultEmbeddingModel = "text-embedding-3-large",
                    embeddingServices = mapOf("memory" to "text-embedding-3-small"),
                ),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("text-embedding-3-small")
    }

    /**
     * A role pointed straight at the placeholder. An application that wires roles (memory,
     * retrieval) has to be able to say "not yet" for them too, not only for the default — and role
     * resolution takes a different branch of `getEmbeddingService`, which nothing else here covers.
     */
    @Test
    fun `a role naming the placeholder resolves to it`() {
        val provider = ConfigurableModelProvider(
            llms = listOf(SetupRequiredLlm.llmService()),
            embeddingServices = listOf(SetupRequiredEmbedding.embeddingService()),
            properties = ConfigurableModelProviderProperties(
                defaultLlm = SetupRequiredLlm.NAME,
                defaultEmbeddingModel = SetupRequiredEmbedding.NAME,
                embeddingServices = mapOf("memory" to SetupRequiredEmbedding.NAME),
            ),
        )

        val resolved = provider.getEmbeddingService(ByRoleModelSelectionCriteria("memory"))

        assertThat(resolved).isInstanceOf(PlaceholderEmbeddingService::class.java)
        assertThatThrownBy { resolved.dimensions }
            .isInstanceOf(NoEmbeddingServiceConfiguredException::class.java)
    }

    /**
     * The placeholder is listed like any other registered model. Worth pinning either way, because
     * `listModels` feeds operator-facing surfaces: whichever answer is right, it should be a
     * decision rather than an accident, and a UI showing "setup-required-embedding" as an available
     * model is the visible consequence.
     */
    @Test
    fun `the placeholder appears in listModels`() {
        val provider = ConfigurableModelProvider(
            llms = listOf(SetupRequiredLlm.llmService()),
            embeddingServices = listOf(SetupRequiredEmbedding.embeddingService()),
            properties = ConfigurableModelProviderProperties(
                defaultLlm = SetupRequiredLlm.NAME,
                defaultEmbeddingModel = SetupRequiredEmbedding.NAME,
            ),
        )

        assertThat(provider.listModels().map { it.name }).contains(SetupRequiredEmbedding.NAME)
    }

    /**
     * The property, not the marker, is what consumers ask — because this is what happens to a type
     * test the moment anything decorates the service, which the framework's own event tracking
     * already does. `by` delegation forwards the property to any depth for free.
     */
    @Test
    fun `awaitingProviderKey survives wrapping, where a type test does not`() {
        val wrapped: EmbeddingService = Wrapper(Wrapper(SetupRequiredEmbedding.embeddingService()))

        assertThat(wrapped is PlaceholderEmbeddingService)
            .describedAs("a type test sees only the outermost layer")
            .isFalse()
        assertThat(wrapped.awaitingProviderKey)
            .describedAs("the question consumers actually ask")
            .isTrue()
    }

    @Test
    fun `a wrapped real service is not awaiting a key`() {
        val wrapped: EmbeddingService = Wrapper(FakeEmbeddingService("text-embedding-3-small", dimensions = 1536))

        assertThat(wrapped.awaitingProviderKey).isFalse()
        assertThat(wrapped.dimensions).isEqualTo(1536)
    }

    /** Stands in for any decorator — event tracking, metering, a hot-swappable model holder. */
    private class Wrapper(delegate: EmbeddingService) : EmbeddingService by delegate

    private class FakeEmbeddingService(
        override val name: String,
        override val dimensions: Int,
    ) : EmbeddingService {
        override val provider: String = "acme"
        override val pricingModel: PricingModel? = null
        override fun embed(text: String): FloatArray = FloatArray(dimensions)
        override fun embed(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
    }
}
