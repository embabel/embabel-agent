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
package com.embabel.agent.a2a.server.config

import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.testing.integration.DummyObjectCreatingLlmOperations
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.Llm
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile


@Profile(value = ["test", "a2a"])
@TestConfiguration
class FakeAiConfiguration {

    private val logger = LoggerFactory.getLogger(FakeAiConfiguration::class.java)

    init {
        logger.info("Using fake AI configuration for A2A.")
    }

    /**
     * Mock LLM operations bean for testing purposes.
     */
    @Bean
    @Primary
    fun llmOperations(): LlmOperations = DummyObjectCreatingLlmOperations.LoremIpsum

    /**
     * Mock bean to satisfy the dependency requirement for bedrockModels
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
