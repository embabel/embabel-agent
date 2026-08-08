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
package com.embabel.common.byok

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The probe-and-stamp algorithm, independent of any provider. It lives here rather than in a
 * provider module because nothing about it is provider-specific: the caller supplies the
 * service, this supplies the validation.
 */
class EmbeddingValidationTest {

    private class FakeEmbeddingService(
        override val name: String,
        override val provider: String,
        private val configuredDimensions: Int?,
        override val pricingModel: PricingModel? = null,
        private val respond: (String) -> FloatArray,
    ) : EmbeddingService {
        override fun embed(text: String): FloatArray = respond(text)
        override fun embed(texts: List<String>): List<FloatArray> = texts.map(respond)
        override val dimensions: Int
            get() = configuredDimensions ?: error("dimensions not configured")
    }

    /**
     * Stands in for a provider's builder, recording the width it was asked to stamp on each call.
     */
    private class RecordingBuilder(
        private val respond: (String) -> FloatArray,
    ) : (Int?) -> EmbeddingService {

        val widthsRequested = mutableListOf<Int?>()

        override fun invoke(configuredDimensions: Int?): EmbeddingService {
            widthsRequested += configuredDimensions
            return FakeEmbeddingService(
                name = "text-embedding-3-small",
                provider = "acme",
                configuredDimensions = configuredDimensions,
                respond = respond,
            )
        }
    }

    @Test
    fun `probes once, then stamps the width the model actually returned`() {
        var probes = 0
        val build = RecordingBuilder { probes++; FloatArray(3072) }

        val service = validatedEmbeddingService("text-embedding-3-large", "acme", build)

        assertEquals(1, probes, "should probe exactly once")
        assertEquals(3072, service.dimensions)
        assertEquals(
            listOf(null, 3072),
            build.widthsRequested,
            "probe with no width, then stamp the one observed",
        )
    }

    @Test
    fun `a provider error is translated, and names the model`() {
        val build = RecordingBuilder { throw RuntimeException("404 model not found") }

        val e = assertThrows<InvalidApiKeyException> {
            validatedEmbeddingService("text-embedding-3-smal", "acme", build)
        }

        // The model is caller-supplied and mandatory here, so a typo in it is at least as likely
        // as a bad key and must be visible in the message.
        assertTrue(e.message!!.contains("text-embedding-3-smal"), e.message)
        assertTrue(e.message!!.contains("acme"), e.message)
    }

    @Test
    fun `an empty vector is a validation failure, not a zero-width service`() {
        val build = RecordingBuilder { FloatArray(0) }

        assertThrows<InvalidApiKeyException> {
            validatedEmbeddingService("text-embedding-3-small", "acme", build)
        }
    }
}
