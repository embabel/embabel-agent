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
import com.embabel.common.ai.model.ModelProvider.Companion.SCHEMA_ROLE
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import kotlin.test.assertContains

class ApplicationPropertiesModelProviderTest() {

    private val mp: ModelProvider = ApplicationPropertiesModelProvider(
        llms = listOf(
            Llm("gpt40", mockk<ChatModel>()),
            Llm("gpt40-mini", mockk<ChatModel>()),
            Llm("embedding", mockk<ChatModel>())
        ),
        embeddingServices = listOf(
            EmbeddingService("embedding", mockk<EmbeddingModel>())
        ),
        properties = ModelProperties(
            llms = mapOf(
                BEST_ROLE to "gpt40",
                SCHEMA_ROLE to "gpt40"
            ),
            embeddingServices = mapOf(
                SCHEMA_ROLE to "embedding"
            )
        )
    )

    @Nested
    inner class List {

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
            assertContains(roles, SCHEMA_ROLE)
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
            assertContains(roles, "embedding")
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
            val llm = mp.getLlm(ByNameModelSelectionCriteria("gpt40-mini"))
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
            val ember = mp.getEmbeddingService(ByRoleModelSelectionCriteria(SCHEMA_ROLE))
            assertNotNull(ember)
        }
    }
}
