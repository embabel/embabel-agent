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
import com.embabel.common.byok.BLANK_API_KEY_MESSAGE
import com.embabel.common.byok.ByokFactory
import com.embabel.common.byok.InvalidApiKeyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenAiCompatibleModelFactoryByokEmbeddingTest {

    private companion object {
        /**
         * Named rather than repeated: these are OpenAI catalogue ids, and a model being retired
         * should be one edit here rather than a hunt through every assertion.
         */
        const val SMALL_MODEL = "text-embedding-3-small"
        const val LARGE_MODEL = "text-embedding-3-large"
        const val SMALL_MODEL_DIMENSIONS = 1536
        const val LARGE_MODEL_DIMENSIONS = 3072
    }

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
        apiKey: String = "key",
        private val respond: (String) -> FloatArray,
    ) : OpenAiCompatibleModelFactory(baseUrl = null, apiKey = apiKey) {

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
            OpenAiCompatibleModelFactory.openAiEmbedding("key", SMALL_MODEL)
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
        val service = FakeFactory { FloatArray(SMALL_MODEL_DIMENSIONS) }
            .buildValidatedEmbeddingService(
                model = SMALL_MODEL,
                provider = OpenAiModels.PROVIDER,
            )
        assertEquals(OpenAiModels.PROVIDER, service.provider)
        assertEquals(SMALL_MODEL, service.name)
    }

    @Test
    fun `valid key returns a service and probes exactly once`() {
        var probes = 0
        val factory = FakeFactory { probes++; FloatArray(SMALL_MODEL_DIMENSIONS) { 0.1f } }

        val service = factory.buildValidatedEmbeddingService(
            model = SMALL_MODEL,
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
                model = SMALL_MODEL,
                provider = OpenAiModels.PROVIDER,
            )
        }
        assertTrue(e.message!!.contains("401 Unauthorized"), e.message)
    }

    @Test
    fun `a validation failure names the model, since a typo in it looks the same as a bad key`() {
        // The embedding model is caller-supplied and mandatory — never defaulted, never detected —
        // so "invalid API key" alone would send someone to re-check a key that was fine.
        val factory = FakeFactory { throw RuntimeException("404 model not found") }

        val e = assertThrows<InvalidApiKeyException> {
            factory.buildValidatedEmbeddingService(
                model = "text-embedding-3-smal",
                provider = OpenAiModels.PROVIDER,
            )
        }

        assertTrue(e.message!!.contains("text-embedding-3-smal"), e.message)
        assertTrue(e.message!!.contains(OpenAiModels.PROVIDER), e.message)
    }

    @Test
    fun `empty probe vector is rejected`() {
        val factory = FakeFactory { FloatArray(0) }

        assertThrows<InvalidApiKeyException> {
            factory.buildValidatedEmbeddingService(
                model = SMALL_MODEL,
                provider = OpenAiModels.PROVIDER,
            )
        }
    }

    @Test
    fun `returned service carries the probed dimension so no live dimensions call is needed`() {
        // The width comes from the model and only from the model: the caller has no way to declare
        // one here, so a store can compare it against its index rather than trusting config.
        val factory = FakeFactory { FloatArray(LARGE_MODEL_DIMENSIONS) }

        val service = factory.buildValidatedEmbeddingService(
            model = LARGE_MODEL,
            provider = OpenAiModels.PROVIDER,
        )

        assertEquals(LARGE_MODEL_DIMENSIONS, service.dimensions)
    }

    @Test
    fun `a blank key is rejected without probing`() {
        // Compose passes OPENAI_API_KEY=${OPENAI_API_KEY:-}, so set-but-empty is routine. That
        // empty string passes a null check and would otherwise reach the provider, coming back as
        // an opaque authentication error a long way from its cause.
        listOf("", "   ", "\t").forEach { blank ->
            val factory = FakeFactory(apiKey = blank) { FloatArray(SMALL_MODEL_DIMENSIONS) }

            val e = assertThrows<InvalidApiKeyException> {
                factory.buildValidatedEmbeddingService(
                    model = SMALL_MODEL,
                    provider = OpenAiModels.PROVIDER,
                )
            }

            assertEquals(BLANK_API_KEY_MESSAGE, e.message)
            assertEquals(0, factory.builds.size, "a blank key must not build or probe anything")
        }
    }

    @Test
    fun `the BYOK embedding entry points reject a blank key too`() {
        listOf(
            OpenAiCompatibleModelFactory.openAiEmbedding("   ", SMALL_MODEL),
            OpenAiCompatibleModelFactory.byokEmbedding(
                baseUrl = "https://api.example.com",
                apiKey = "",
                model = "acme-embed-small",
                provider = "Acme",
            ),
        ).forEach { spec ->
            val e = assertThrows<InvalidApiKeyException> { spec.buildValidated() }
            assertEquals(BLANK_API_KEY_MESSAGE, e.message)
        }
    }

    @Test
    fun `pricing model is carried through to the returned service`() {
        val factory = FakeFactory { FloatArray(SMALL_MODEL_DIMENSIONS) }

        val service = factory.buildValidatedEmbeddingService(
            model = SMALL_MODEL,
            provider = OpenAiModels.PROVIDER,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
        )

        assertSame(PricingModel.ALL_YOU_CAN_EAT, service.pricingModel)
    }
}
