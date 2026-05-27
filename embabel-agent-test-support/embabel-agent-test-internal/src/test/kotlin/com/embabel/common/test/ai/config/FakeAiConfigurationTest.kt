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
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Verifies the explicit test-only bean contract exposed by [FakeAiConfiguration].
 *
 * `FakeAiConfiguration` is a top-level `@TestConfiguration`, so this test loads it
 * explicitly with [ApplicationContextRunner] instead of booting a full application.
 */
class FakeAiConfigurationTest {

    private val runner = ApplicationContextRunner().withUserConfiguration(FakeAiConfiguration::class.java)

    @Test
    fun `fake ai configuration registers expected fake ai beans`() {

        // Act
        runner.run { ctx ->
            // Assert
            assertNotNull(ctx.getBean("bedrockModels"))
            assertEquals(
                setOf("best", "cheapest", "test-llm"), ctx.getBeansOfType(LlmService::class.java).keys,
            )
            assertEquals(
                setOf("embedding", "test"), ctx.getBeansOfType(EmbeddingService::class.java).keys,
            )

            val cheapest = ctx.getBean("cheapest", LlmService::class.java)
            assertIs<SpringAiLlmService>(cheapest)
            assertEquals("gpt-4o-mini", cheapest.name)
            assertEquals("OpenAI", cheapest.provider)
            assertSame(DefaultOptionsConverter, cheapest.optionsConverter)

            val best = ctx.getBean("best", LlmService::class.java)
            assertIs<SpringAiLlmService>(best)
            assertEquals("gpt-4o", best.name)
            assertEquals("OpenAI", best.provider)
            assertSame(DefaultOptionsConverter, best.optionsConverter)

            val testLlm = ctx.getBean("test-llm", LlmService::class.java)
            assertIs<SpringAiLlmService>(testLlm)
            assertEquals("test-llm", testLlm.name)
            assertEquals("test", testLlm.provider)
            assertSame(DefaultOptionsConverter, testLlm.optionsConverter)

            val embedding = ctx.getBean("embedding", EmbeddingService::class.java)
            assertIs<SpringAiEmbeddingService>(embedding)
            assertEquals("text-embedding-ada-002", embedding.name)
            assertEquals("OpenAI", embedding.provider)
            assertIs<FakeEmbeddingModel>(embedding.model)

            val testEmbedding = ctx.getBean("test", EmbeddingService::class.java)
            assertIs<SpringAiEmbeddingService>(testEmbedding)
            assertEquals("test", testEmbedding.name)
            assertEquals("test", testEmbedding.provider)
        }
    }
}
