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
package com.embabel.agent.test.config

import com.embabel.agent.test.example.simple.horoscope.TestHoroscopeService
import com.embabel.agent.spi.Ranking
import com.embabel.agent.spi.Rankings
import com.embabel.agent.testing.integration.FakeRanker
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.Llm
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import org.junit.jupiter.api.Assertions.fail
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.mockito.Mockito.mock

@TestConfiguration
class FakeConfig {

    @Bean
    @Primary
    fun fakeHoroscopeService() = TestHoroscopeService {
        """
            |On Monday, try to avoid being eaten by wolves            .
        """.trimMargin()
    }

    @Bean
    @Primary
    fun fakeRanker() = object : FakeRanker {

        override fun <T> rank(
            description: String,
            userInput: String,
            rankables: Collection<T>,
        ): Rankings<T> where T : Named, T : Described {
            when (description) {
                "agent" -> {
                    val a = rankables.firstOrNull { it.name.contains("Star") } ?: fail { "No agent with Star found" }
                    return Rankings(
                        rankings = listOf(Ranking(a, 0.9))
                    )
                }

                "goal" -> {
                    val g =
                        rankables.firstOrNull { it.description.contains("horoscope") } ?: fail("No goal with horoscope")
                    return Rankings(
                        rankings = listOf(Ranking(g, 0.9))
                    )
                }

                else -> throw IllegalArgumentException("Unknown description $description")
            }
        }
    }

    /**
     * Mock bean to satisfy the dependency requirement for bedrockModels
     * This prevents the application context from failing to start in tests
     */
    @Bean(name = ["bedrockModels"])
    fun bedrockModels(): Any = Any()

    /**
     * Test LLM bean that matches the default-llm configuration
     */
    @Bean(name = ["test-llm"])
    fun testLlm(): Llm = Llm(
        name = "test-llm",
        model = mock(ChatModel::class.java),
        provider = "test",
        optionsConverter = DefaultOptionsConverter
    )

    /**
     * Test embedding service that matches the default-embedding-model configuration
     */
    @Bean(name = ["test-embedding"])
    fun testEmbedding(): EmbeddingService = EmbeddingService(
        name = "test-embedding",
        model = mock(EmbeddingModel::class.java),
        provider = "test"
    )

    /**
     * Additional test embedding model for the 'best' role
     */
    @Bean(name = ["test"])
    fun test(): EmbeddingService = EmbeddingService(
        name = "test",
        model = mock(EmbeddingModel::class.java),
        provider = "test"
    )
}
