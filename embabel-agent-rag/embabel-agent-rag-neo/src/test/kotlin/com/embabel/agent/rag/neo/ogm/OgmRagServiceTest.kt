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
package com.embabel.agent.rag.neo.ogm

import com.embabel.agent.rag.RagRequest
import com.embabel.common.ai.model.Llm
import com.embabel.test.NeoIntegrationTestSupport
import io.mockk.every
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import kotlin.test.assertEquals

@DisabledIfEnvironmentVariable(named = "SKIP_TESTCONTAINER_TESTS", matches = "true")
class OgmRagServiceTest(
    @param:Autowired @param:Qualifier("best")
    private val cypherGenerationLlm: Llm,
) : NeoIntegrationTestSupport() {

    private fun createVectorIndexes() {
        driver().session().executeWrite { tx ->
            tx.run(
                """
            CREATE VECTOR INDEX `spring-ai-document-index` IF NOT EXISTS
            FOR (n:Document) ON (n.embedding)
            OPTIONS {indexConfig: {
            `vector.dimensions`: 1536,
            `vector.similarity_function`: 'cosine'
            }};
        """.trimIndent()
            ).consume()

            tx.run(
                """
            CREATE VECTOR INDEX entity_embeddings IF NOT EXISTS
            FOR (n:Entity)
            ON (n.embedding)
            OPTIONS {indexConfig: {
            `vector.dimensions`: 1536,
            `vector.similarity_function`: 'cosine'
            }};
        """.trimIndent()
            ).consume()
        }
    }

    @Test
    fun `should find nothing in empty db`() {
        createVectorIndexes()
        every { cypherGenerationLlm.model.call(any<String>()) } returns "MATCH (n) WHERE n.name CONTAINS 'test' RETURN n"

        val results = ragService!!.search(
            RagRequest(
                query = "test",
                topK = 10,
            )
        )
        assertEquals(0, results.results.size, "Expected no results in empty database")
    }

}
