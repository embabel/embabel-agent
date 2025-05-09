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
package com.embabel.common.ai.model

import com.embabel.common.ai.model.ModelProvider.Companion.BEST_ROLE
import com.embabel.common.ai.model.ModelProvider.Companion.CHEAPEST_ROLE
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertContains

/**
 *  Spring boot integration tests that employs model properties from config properties file.
 */
@SpringBootTest(classes = [ConfigurableModelProviderIntegrationTest.TestConfig::class])
@TestPropertySource("classpath:application-test.properties")
class ConfigurableModelProviderIntegrationTest {

    @Configuration
    @EnableConfigurationProperties(ModelProperties::class)
    class TestConfig {

        @Bean
        fun llmList(): List<Llm> = listOf(
            Llm("gpt-4o", "OpenAI", mockk<ChatModel>()),
            Llm("gpt-4o-mini", "OpenAI", mockk<ChatModel>())
        )

        @Bean
        fun embeddingServices(): List<EmbeddingService> = listOf(
            EmbeddingService("text-embedding-ada-002", "OpenAI", mockk<EmbeddingModel>())
        )

        @Bean
        fun applicationPropertiesModelProvider(
            llms: List<Llm>,
            embeddingServices: List<EmbeddingService>,
            properties: ModelProperties
        ): ModelProvider = ConfigurableModelProvider(llms, embeddingServices, properties)
    }

    @Autowired
    private lateinit var modelProvider: ModelProvider

    @Nested
    inner class ListTests {

        @Test
        fun llmRoles() {
            val roles = modelProvider.listRoles(Llm::class.java)
            assertFalse(roles.isEmpty())
            assertContains(roles, BEST_ROLE)
            assertContains(roles, CHEAPEST_ROLE)
        }

        @Test
        fun embeddingRoles() {
            val roles = modelProvider.listRoles(EmbeddingService::class.java)
            assertFalse(roles.isEmpty())
            assertContains(roles, "schema")
        }

        @Test
        fun llmNames() {
            val names = modelProvider.listModelNames(Llm::class.java)
            assertFalse(names.isEmpty())
            assertContains(names, "gpt-4o")
            assertContains(names, "gpt-4o-mini")
        }

        @Test
        fun embeddingNames() {
            val names = modelProvider.listModelNames(EmbeddingService::class.java)
            assertFalse(names.isEmpty())
            assertContains(names, "text-embedding-ada-002")
        }
    }

    @Nested
    inner class Llms {

        @Test
        fun `no such role`() {
            assertThrows<NoSuitableModelException> {
                modelProvider.getLlm(ByRoleModelSelectionCriteria("nonexistent"))
            }
        }

        @Test
        fun `valid role best`() {
            val llm = modelProvider.getLlm(ByRoleModelSelectionCriteria(BEST_ROLE))
            assertNotNull(llm)
            assertEquals("gpt-4o", llm.name)
        }

        @Test
        fun `valid role cheapest`() {
            val llm = modelProvider.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
            assertNotNull(llm)
            assertEquals("gpt-4o-mini", llm.name)
        }

        @Test
        fun `no such name`() {
            assertThrows<NoSuitableModelException> {
                modelProvider.getLlm(ByNameModelSelectionCriteria("nonexistent"))
            }
        }

        @Test
        fun `valid name`() {
            val llm = modelProvider.getLlm(ByNameModelSelectionCriteria("gpt-4o-mini"))
            assertNotNull(llm)
            assertEquals("gpt-4o-mini", llm.name)
        }
    }

    @Nested
    inner class Embeddings {

        @Test
        fun `no such role`() {
            assertThrows<NoSuitableModelException> {
                modelProvider.getEmbeddingService(ByRoleModelSelectionCriteria("nonexistent"))
            }
        }

        @Test
        fun `valid role`() {
            val embedding = modelProvider.getEmbeddingService(ByRoleModelSelectionCriteria("schema"))
            assertNotNull(embedding)
            assertEquals("text-embedding-ada-002", embedding.name)
        }
    }
}
