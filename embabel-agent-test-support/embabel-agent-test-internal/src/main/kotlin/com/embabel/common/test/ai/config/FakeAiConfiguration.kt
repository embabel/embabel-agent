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
package com.embabel.common.test.ai.config

import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.SpringAiEmbeddingService
import com.embabel.common.test.ai.FakeEmbeddingModel
import com.embabel.common.util.loggerFor
import io.mockk.mockk
import org.mockito.Mockito.mock
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Parallels the AiConfiguration class in src/main/java/com/embabel/server/AiConfiguration.kt.
 * Enables tests to run without OPENAI_API_KEY.
 */
@TestConfiguration
class FakeAiConfiguration {

    init {
        loggerFor<FakeAiConfiguration>().info("Using fake AI configuration")
    }

    @Bean
    fun cheapest(): LlmService<*> {
        return SpringAiLlmService(
            name = "gpt-4o-mini",
            chatModel = mockk<ChatModel>(),
            provider = "OpenAI",
            optionsConverter = DefaultOptionsConverter,
        )
    }

    @Bean
    fun best(): LlmService<*> {
        return SpringAiLlmService(
            name = "gpt-4o",
            chatModel = mockk<ChatModel>(),
            provider = "OpenAI",
            optionsConverter = DefaultOptionsConverter,
        )
    }

    /**
     * Mock bean to satisfy the dependency requirement for bedrockModels
     */
    @Bean(name = ["bedrockModels"])
    fun bedrockModels(): Any = Any()

    /**
     * Test LLM bean that matches the default-llm configuration
     */
    @Bean(name = ["test-llm"])
    fun testLlm(): LlmService<*> = SpringAiLlmService(
        name = "test-llm",
        chatModel = mock(ChatModel::class.java),
        provider = "test",
        optionsConverter = DefaultOptionsConverter
    )

    @Bean
    fun embedding(): EmbeddingService {
        return SpringAiEmbeddingService(
            name = "text-embedding-ada-002",
            model = FakeEmbeddingModel(),
            provider = "OpenAI",
        )
    }

    /**
     * Additional test embedding model for the 'best' role
     */
    @Bean(name = ["test"])
    fun test(): EmbeddingService = SpringAiEmbeddingService(
        name = "test",
        model = mock(EmbeddingModel::class.java),
        provider = "test"
    )

}
