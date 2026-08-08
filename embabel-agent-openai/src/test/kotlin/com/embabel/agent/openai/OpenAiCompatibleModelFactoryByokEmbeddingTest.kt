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
package com.embabel.agent.openai

import com.embabel.agent.api.models.OpenAiModels
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.byok.ByokFactory
import com.embabel.common.byok.InvalidApiKeyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenAiCompatibleModelFactoryByokEmbeddingTest {

    /**
     * Stands in for a live embedding endpoint so the validation logic can be exercised
     * without a network call.
     */
    private class FakeEmbeddingService(
        override val name: String,
        override val provider: String,
        val configuredDimensions: Int?,
        override val pricingModel: PricingModel? = null,
        private val respond: (String) -> FloatArray,
    ) : EmbeddingService {
        override fun embed(text: String): FloatArray = respond(text)

        override fun embed(texts: List<String>): List<FloatArray> = texts.map(respond)

        override val dimensions: Int
            get() = configuredDimensions ?: error("dimensions not configured")
    }

    /**
     * Replaces the live embedding service with a fake, recording what the factory asked for.
     */
    private class FakeFactory(
        private val respond: (String) -> FloatArray,
    ) : OpenAiCompatibleModelFactory(baseUrl = null, apiKey = "key") {

        val builds = mutableListOf<FakeEmbeddingService>()

        override fun openAiCompatibleEmbeddingService(
            model: String,
            provider: String,
            configuredDimensions: Int?,
            pricingModel: PricingModel?,
        ): EmbeddingService = FakeEmbeddingService(
            name = model,
            provider = provider,
            configuredDimensions = configuredDimensions,
            pricingModel = pricingModel,
            respond = respond,
        ).also { builds += it }
    }

    @Test
    fun `openAiEmbedding returns a ByokFactory of EmbeddingService`() {
        val spec: ByokFactory<EmbeddingService> =
            OpenAiCompatibleModelFactory.openAiEmbedding("key", "text-embedding-3-small")
        assertInstanceOf(ByokFactory::class.java, spec)
    }

    @Test
    fun `byokEmbedding with explicit params returns a ByokFactory`() {
        assertInstanceOf(
            ByokFactory::class.java,
            OpenAiCompatibleModelFactory.byokEmbedding(
                baseUrl = "https://api.example.com",
                apiKey = "key",
                model = "acme-embed-small",
                provider = "Acme",
            ),
        )
    }

    @Test
    fun `openAiEmbedding uses the OpenAI provider`() {
        val service = FakeFactory { FloatArray(1536) }
            .buildValidatedEmbeddingService(
                model = "text-embedding-3-small",
                provider = OpenAiModels.PROVIDER,
            )
        assertEquals(OpenAiModels.PROVIDER, service.provider)
        assertEquals("text-embedding-3-small", service.name)
    }

    @Test
    fun `valid key returns a service and probes exactly once`() {
        var probes = 0
        val factory = FakeFactory { probes++; FloatArray(1536) { 0.1f } }

        val service = factory.buildValidatedEmbeddingService(
            model = "text-embedding-3-small",
            provider = OpenAiModels.PROVIDER,
        )

        assertNotNull(service)
        assertEquals(1, probes, "buildValidated should probe once")
    }

    @Test
    fun `probe failure is translated to InvalidApiKeyException`() {
        val factory = FakeFactory { throw RuntimeException("401 Unauthorized") }

        val e = assertThrows<InvalidApiKeyException> {
            factory.buildValidatedEmbeddingService(
                model = "text-embedding-3-small",
                provider = OpenAiModels.PROVIDER,
            )
        }
        assertEquals("401 Unauthorized", e.message)
    }

    @Test
    fun `empty probe vector is rejected`() {
        val factory = FakeFactory { FloatArray(0) }

        assertThrows<InvalidApiKeyException> {
            factory.buildValidatedEmbeddingService(
                model = "text-embedding-3-small",
                provider = OpenAiModels.PROVIDER,
            )
        }
    }

    @Test
    fun `returned service carries the probed dimension so no live dimensions call is needed`() {
        val factory = FakeFactory { FloatArray(3072) }

        val service = factory.buildValidatedEmbeddingService(
            model = "text-embedding-3-large",
            provider = OpenAiModels.PROVIDER,
        )

        assertEquals(3072, service.dimensions)
    }

    @Test
    fun `dimension comes from the model, never from the caller`() {
        // The caller has no way to declare a width here: whatever the model returns is the
        // width, so a store can compare it against its index rather than trusting config.
        val factory = FakeFactory { FloatArray(3072) }

        val service = factory.buildValidatedEmbeddingService(
            model = "text-embedding-3-large",
            provider = OpenAiModels.PROVIDER,
        )

        assertEquals(3072, service.dimensions)
    }

    @Test
    fun `pricing model is carried through to the returned service`() {
        val factory = FakeFactory { FloatArray(1536) }

        val service = factory.buildValidatedEmbeddingService(
            model = "text-embedding-3-small",
            provider = OpenAiModels.PROVIDER,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
        )

        assertSame(PricingModel.ALL_YOU_CAN_EAT, service.pricingModel)
    }
}
