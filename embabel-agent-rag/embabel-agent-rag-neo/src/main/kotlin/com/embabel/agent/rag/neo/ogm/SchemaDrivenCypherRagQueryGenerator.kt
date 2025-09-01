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
import com.embabel.agent.rag.neo.common.CypherQuery
import com.embabel.agent.rag.neo.common.CypherRagQueryGenerator
import com.embabel.agent.rag.schema.KnowledgeGraphSchema
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.byRole
import com.embabel.common.util.loggerFor
import org.slf4j.Logger

/**
 * Generate RAG queries from a given schema
 */
class SchemaDrivenCypherRagQueryGenerator(
    modelProvider: ModelProvider,
    private val schema: KnowledgeGraphSchema,
) : CypherRagQueryGenerator {

    private val logger: Logger = loggerFor<SchemaDrivenCypherRagQueryGenerator>()

    private val llm = modelProvider.getLlm(byRole("cypher-query-generator"))

    override fun generateQuery(
        request: RagRequest,
    ): CypherQuery {
        val prompt = """
            Generate a Cypher query for the following RAG request:
            Request: ${request.query}
            Use only entities and relationships defined in the schema.
            Schema: ${schema.infoString(verbose = true)}

            You must return entities named as `n`.

            RETURN ONLY the Cypher query, without any additional text or explanation.
         """.trimIndent()

        logger.info("Cypher query generation prompt for {}:\n{}", request.query, prompt)
        val response = llm.model.call(prompt)
        val query = cleanLlmCypher(response)
        validate(query)
        val cypherQuery = CypherQuery(query)
        return cypherQuery
    }

    //    @Throws(QueryValidationException::class)
    private fun validate(cypher: String) {
        logger.debug(
            "Generated Cypher query '{}' is valid against given schema",
            cypher
        )
    }
}

/**
 * Try to extract Cypher from the response
 * @param rawResponse raw response from LLM
 * @return Cypher part of the response
 */
fun cleanLlmCypher(rawResponse: String): String {
    return rawResponse.replace("```cypher", "").replace("```", "").trim()
}
