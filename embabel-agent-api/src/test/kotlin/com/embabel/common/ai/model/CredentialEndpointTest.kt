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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.time.LocalDate

/**
 * The point of these types, asserted rather than stated: a deployment can add a BYOK provider
 * without naming anything under `com.embabel.agent.spi`, which the API evolution guide asks
 * application code not to depend on. [CredentialLlmServiceFactory], the other way to do it,
 * returns an `LlmService` and so cannot make that promise.
 */
class CredentialEndpointTest {

    private companion object {
        const val SPI_PACKAGE = "com.embabel.agent.spi"
    }

    @Nested
    inner class NoSpiInTheSignature {

        @Test
        fun `implementing a resolver names no SPI type`() {
            val resolve = CredentialEndpointResolver::class.java.methods.single { it.name == "resolve" }

            assertThat(typesIn(resolve)).noneMatch { it.startsWith(SPI_PACKAGE) }
        }

        @Test
        fun `nor does building any endpoint the resolver can return`() {
            val cases = CredentialEndpoint::class.sealedSubclasses

            assertThat(cases).isNotEmpty()
            cases.forEach { case ->
                val named = case.java.constructors.flatMap { it.parameterTypes.map { p -> p.name } } +
                    case.java.methods.flatMap { typesIn(it) }

                assertThat(named).describedAs(case.simpleName).noneMatch { it.startsWith(SPI_PACKAGE) }
            }
        }

        @Test
        fun `the factory it replaces cannot say the same`() {
            // Not a complaint about that type - it builds the service itself, so it has to name one.
            // Pinned so that if it ever stops naming an SPI type, this pair of tiers gets revisited.
            val create = CredentialLlmServiceFactory::class.java.methods.single { it.name == "createLlmService" }

            assertThat(typesIn(create)).anyMatch { it.startsWith(SPI_PACKAGE) }
        }

        private fun typesIn(method: Method): List<String> =
            method.parameterTypes.map { it.name } + method.returnType.name
    }

    @Nested
    inner class Defaults {

        @Test
        fun `a BYOK endpoint is free to the deployment unless it says otherwise`() {
            // The user's own key is billed, so the deployment's cost accounting must not count it.
            assertThat(CredentialEndpoint.OpenAiCompatible(baseUrl = null, provider = "OurGateway").pricingModel)
                .isEqualTo(PricingModel.ALL_YOU_CAN_EAT)
            assertThat(CredentialEndpoint.Anthropic(provider = "OurGateway").pricingModel)
                .isEqualTo(PricingModel.ALL_YOU_CAN_EAT)
        }

        @Test
        fun `a cutoff date is stated rather than guessed`() {
            // It reaches the LLM as a prompt contribution, and the model name comes from
            // configuration - so defaulting to anything but "unknown" would be a guess.
            assertThat(CredentialEndpoint.OpenAiCompatible(baseUrl = null, provider = "OurGateway").knowledgeCutoffDate)
                .isNull()
            assertThat(
                CredentialEndpoint.Anthropic(provider = "OurGateway", knowledgeCutoffDate = LocalDate.of(2026, 1, 1))
                    .knowledgeCutoffDate,
            ).isEqualTo(LocalDate.of(2026, 1, 1))
        }
    }
}
