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

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.util.loggerFor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the [SetupRequiredEmbedding] placeholder so a deployment with no provider key can start
 * even when it uses RAG or memory.
 *
 * Registered unconditionally, for the same reason as [SetupRequiredLlmConfig]: provider
 * autoconfigurations register their models imperatively with `registerSingleton` rather than as
 * `@Bean`s, so `@ConditionalOnMissingBean` would be evaluated before those singletons exist and
 * would always see none. Opting in through `embabel.models.default-embedding-model` avoids the
 * ordering question entirely.
 *
 * An unused placeholder is harmless: it is only ever reached by a deployment that names it, or by
 * one whose configured default embedding model is not registered — where the alternative is
 * failing to start.
 */
@Configuration(proxyBeanMethods = false)
class SetupRequiredEmbeddingConfig {

    @Bean(SetupRequiredEmbedding.NAME)
    fun setupRequiredEmbeddingService(): EmbeddingService {
        loggerFor<SetupRequiredEmbeddingConfig>().info(
            """
            Registered placeholder embedding service '{}'. Set embabel.models.default-embedding-model={}
            to start with no provider key; anything that embeds or provisions a vector index should check
            for PlaceholderEmbeddingService and wait for a real model.
            """.trimIndent(),
            SetupRequiredEmbedding.NAME,
            SetupRequiredEmbedding.NAME,
        )
        return SetupRequiredEmbedding.embeddingService()
    }
}
