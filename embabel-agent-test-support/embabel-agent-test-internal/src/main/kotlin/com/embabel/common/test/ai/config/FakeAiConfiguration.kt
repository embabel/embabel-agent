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

import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.SpringEmbeddingService
import com.embabel.common.test.ai.FakeEmbeddingModel
import com.embabel.common.util.loggerFor
import io.mockk.mockk
import org.springframework.ai.chat.model.ChatModel
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
    fun cheapest(): Llm {
        return Llm(
            name = "gpt-4o-mini",
            model = mockk<ChatModel>(),
            provider = "OpenAI",
            optionsConverter = DefaultOptionsConverter,
        )
    }

    @Bean
    fun best(): Llm {
        return Llm(
            name = "gpt-4o",
            model = mockk<ChatModel>(),
            provider = "OpenAI",
            optionsConverter = DefaultOptionsConverter,
        )
    }

    @Bean
    fun embedding(): EmbeddingService {
        return SpringEmbeddingService(
            name = "text-embedding-ada-002",
            model = FakeEmbeddingModel(),
            provider = "OpenAI",
        )
    }

}
