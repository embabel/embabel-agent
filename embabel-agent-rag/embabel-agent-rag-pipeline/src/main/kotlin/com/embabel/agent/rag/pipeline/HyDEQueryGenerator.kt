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
package com.embabel.agent.rag.pipeline

import com.embabel.agent.api.common.Ai
import com.embabel.agent.rag.service.HyDE
import com.embabel.agent.rag.service.RagRequest
import com.embabel.common.ai.model.LlmOptions

/**
 * Generates HyDE queries from RagRequests.
 * HyDE stands for Hypothetical Document Embeddings,
 * and was introduced in the paper "Precise Zero-Shot Dense Retrieval without Relevance Labels"
 * See https://arxiv.org/abs/2212.10496
 */
interface HyDEQueryGenerator {

    /**
     * Generate a HyDE query from a RagRequest and HyDE context.
     * @return generated HyDE query, or null if none could be generated
     * because HyDE is not configured
     * @param ragRequest the RAG request
     * @param llm LLM options to use for generation
     * @param ai AI operations to use for generation in this particular context
     */
    fun hydeQuery(
        ragRequest: RagRequest,
        hyDE: HyDE,
        llm: LlmOptions,
        ai: Ai,
    ): String?
}
