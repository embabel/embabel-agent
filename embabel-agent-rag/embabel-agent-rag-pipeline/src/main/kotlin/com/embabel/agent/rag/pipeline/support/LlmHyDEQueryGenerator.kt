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
package com.embabel.agent.rag.pipeline.support

import com.embabel.agent.api.common.Ai
import com.embabel.agent.rag.pipeline.HyDEQueryGenerator
import com.embabel.agent.rag.service.HyDE
import com.embabel.agent.rag.service.RagRequest
import com.embabel.common.ai.model.LlmOptions
import org.slf4j.LoggerFactory

/**
 * Uses an LLM to generate HyDE queries.
 * @param promptPath resource path to the prompt template (not used in this implementation)
 */
class LlmHyDEQueryGenerator(
    val promptPath: String,
) : HyDEQueryGenerator {

    private val logger = LoggerFactory.getLogger(HyDEQueryGenerator::class.java)

    override fun hydeQuery(
        ragRequest: RagRequest,
        hyDE: HyDE,
        llm: LlmOptions,
        ai: Ai,
    ): String {
        val hydeQuery = ai
            .withLlm(llm)
            .withTemplate(promptPath)
            .generateText(
                mapOf(
                    "ragRequest" to ragRequest,
                    "hyDE" to hyDE,
                )
            )
        logger.info("Initial query '{}' -> Generated HyDE query '{}'", ragRequest.query, hydeQuery)
        return hydeQuery
    }
}
