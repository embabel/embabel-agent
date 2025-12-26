/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.common.ai.autoconfig

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class ProviderInitializationTest {

    @Nested
    inner class TotalLlms {

        @Test
        fun `returns zero when no LLMs registered`() {
            val result = createProviderInitialization(llms = emptyList())

            assertThat(result.totalLlms).isZero()
        }

        @Test
        fun `returns correct count when LLMs registered`() {
            val llms = listOf(
                RegisteredModel("gpt4", "gpt-4"),
                RegisteredModel("gpt35", "gpt-3.5-turbo")
            )
            val result = createProviderInitialization(llms = llms)

            assertThat(result.totalLlms).isEqualTo(2)
        }
    }

    @Nested
    inner class TotalEmbeddings {

        @Test
        fun `returns zero when no embeddings registered`() {
            val result = createProviderInitialization(embeddings = emptyList())

            assertThat(result.totalEmbeddings).isZero()
        }

        @Test
        fun `returns correct count when embeddings registered`() {
            val embeddings = listOf(
                RegisteredModel("ada", "text-embedding-ada-002"),
                RegisteredModel("small", "text-embedding-3-small"),
                RegisteredModel("large", "text-embedding-3-large")
            )
            val result = createProviderInitialization(embeddings = embeddings)

            assertThat(result.totalEmbeddings).isEqualTo(3)
        }
    }

    @Nested
    inner class Summary {

        @Test
        fun `formats summary with provider and counts`() {
            val result = createProviderInitialization(
                provider = "OpenAI",
                llms = listOf(RegisteredModel("gpt4", "gpt-4")),
                embeddings = listOf(RegisteredModel("ada", "text-embedding-ada-002"))
            )

            assertThat(result.summary()).isEqualTo("OpenAI: Initialized 1 LLM(s) and 1 embedding(s)")
        }

        @Test
        fun `formats summary with zero counts`() {
            val result = createProviderInitialization(
                provider = "Anthropic",
                llms = emptyList(),
                embeddings = emptyList()
            )

            assertThat(result.summary()).isEqualTo("Anthropic: Initialized 0 LLM(s) and 0 embedding(s)")
        }

        @Test
        fun `formats summary with multiple models`() {
            val result = createProviderInitialization(
                provider = "Azure",
                llms = listOf(
                    RegisteredModel("gpt4", "gpt-4"),
                    RegisteredModel("gpt35", "gpt-3.5-turbo")
                ),
                embeddings = listOf(
                    RegisteredModel("ada", "text-embedding-ada-002"),
                    RegisteredModel("small", "text-embedding-3-small"),
                    RegisteredModel("large", "text-embedding-3-large")
                )
            )

            assertThat(result.summary()).isEqualTo("Azure: Initialized 2 LLM(s) and 3 embedding(s)")
        }
    }

    @Nested
    inner class InitializedAt {

        @Test
        fun `defaults to current time when not specified`() {
            val before = Instant.now()
            val result = createProviderInitialization()
            val after = Instant.now()

            assertThat(result.initializedAt)
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after)
        }

        @Test
        fun `uses provided timestamp when specified`() {
            val timestamp = Instant.parse("2024-01-15T10:30:00Z")
            val result = ProviderInitialization(
                provider = "OpenAI",
                registeredLlms = emptyList(),
                registeredEmbeddings = emptyList(),
                initializedAt = timestamp
            )

            assertThat(result.initializedAt).isEqualTo(timestamp)
        }
    }

    private fun createProviderInitialization(
        provider: String = "TestProvider",
        llms: List<RegisteredModel> = emptyList(),
        embeddings: List<RegisteredModel> = emptyList()
    ) = ProviderInitialization(
        provider = provider,
        registeredLlms = llms,
        registeredEmbeddings = embeddings
    )
}

class RegisteredModelTest {

    @Test
    fun `creates model with bean name and model id`() {
        val model = RegisteredModel(beanName = "gpt4Chat", modelId = "gpt-4")

        assertThat(model.beanName).isEqualTo("gpt4Chat")
        assertThat(model.modelId).isEqualTo("gpt-4")
    }

    @Test
    fun `equals compares by value`() {
        val model1 = RegisteredModel(beanName = "gpt4", modelId = "gpt-4")
        val model2 = RegisteredModel(beanName = "gpt4", modelId = "gpt-4")

        assertThat(model1).isEqualTo(model2)
    }

    @Test
    fun `not equal when bean name differs`() {
        val model1 = RegisteredModel(beanName = "gpt4", modelId = "gpt-4")
        val model2 = RegisteredModel(beanName = "gpt4Chat", modelId = "gpt-4")

        assertThat(model1).isNotEqualTo(model2)
    }

    @Test
    fun `not equal when model id differs`() {
        val model1 = RegisteredModel(beanName = "gpt4", modelId = "gpt-4")
        val model2 = RegisteredModel(beanName = "gpt4", modelId = "gpt-4-turbo")

        assertThat(model1).isNotEqualTo(model2)
    }
}
