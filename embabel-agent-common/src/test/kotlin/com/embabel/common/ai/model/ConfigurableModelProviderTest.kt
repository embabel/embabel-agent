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


import com.embabel.common.ai.model.ModelProvider.Companion.BEST_ROLE
import com.embabel.common.ai.model.ModelProvider.Companion.CHEAPEST_ROLE
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import kotlin.test.assertContains

class ConfigurableModelProviderTest() {

    private val mp: ModelProvider = ConfigurableModelProvider(
        llms = listOf(
            Llm("gpt40", "OpenAI", mockk<ChatModel>(), DefaultOptionsConverter),
            Llm("gpt-4.1-mini", "OpenAI", mockk<ChatModel>(), DefaultOptionsConverter),
            Llm("embedding", "OpenAI", mockk<ChatModel>(), DefaultOptionsConverter)
        ),
        embeddingServices = listOf(
            SpringAiEmbeddingService("text-embedding-3-small", "OpenAI", mockk<EmbeddingModel>())
        ),
        properties = ConfigurableModelProviderProperties(
            llms = mapOf(
                BEST_ROLE to "gpt40",
                CHEAPEST_ROLE to "gpt40"
            ),
            embeddingServices = mapOf(
                CHEAPEST_ROLE to "text-embedding-3-small"
            )
        ),
    )

    @Nested
    inner class ListTests {

        @Test
        fun llmRoles() {
            val roles = mp.listRoles(Llm::class.java)
            assertFalse(roles.isEmpty())
            assertContains(roles, BEST_ROLE)
        }

        @Test
        fun embeddingRoles() {
            val roles = mp.listRoles(EmbeddingService::class.java)
            assertFalse(roles.isEmpty())
            assertContains(roles, CHEAPEST_ROLE)
        }

        @Test
        fun llmNames() {
            val names = mp.listModelNames(Llm::class.java)
            assertFalse(names.isEmpty())
            assertContains(names, "gpt40")
        }

        @Test
        fun embeddingNames() {
            val roles = mp.listModelNames(EmbeddingService::class.java)
            assertFalse(roles.isEmpty())
            assertContains(roles, "text-embedding-3-small")
        }

        @Test
        fun `models are of correct type`() {
            val models = mp.listModels()
            assertFalse(models.isEmpty())
            assertContains(models.map { it.name }, "gpt40")
            assertContains(models.map { it.name }, "text-embedding-3-small")
            assertEquals(
                0, models.filterIsInstance<Llm>().size, "Should not have Llm class, but safe metadata class",
            )
            assertEquals(
                0,
                models.filterIsInstance<EmbeddingService>().size,
                "Should not have EmbeddingService class, but safe metadata class",
            )
        }

        @Test
        fun `models are serializable`() {
            val models = mp.listModels()
            jacksonObjectMapper().writeValueAsString(models)
        }

    }

    @Nested
    inner class Llms {

        @Test
        fun `no such role`() {
            assertThrows<NoSuitableModelException> { mp.getLlm(ByRoleModelSelectionCriteria("what in God's holy name are you blathering about?")) }
        }

        @Test
        fun `valid role`() {
            val llm = mp.getLlm(ByRoleModelSelectionCriteria(BEST_ROLE))
            assertNotNull(llm)
        }

        @Test
        fun `no such name`() {
            assertThrows<NoSuitableModelException> { mp.getLlm(ByNameModelSelectionCriteria("what in God's holy name are you blathering about?")) }
        }

        @Test
        fun `valid name`() {
            val llm = mp.getLlm(ByNameModelSelectionCriteria("gpt-4.1-mini"))
            assertNotNull(llm)
        }
    }

    @Nested
    inner class Embeddings {

        @Test
        fun `no such role`() {
            assertThrows<NoSuitableModelException> { mp.getEmbeddingService(ByRoleModelSelectionCriteria("what in God's holy name are you blathering about?")) }
        }

        @Test
        fun `valid role`() {
            val ember = mp.getEmbeddingService(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
            assertNotNull(ember)
        }
    }
}
