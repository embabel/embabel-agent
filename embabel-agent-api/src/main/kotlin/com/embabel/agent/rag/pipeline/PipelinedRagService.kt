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
package com.embabel.agent.rag.pipeline

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.rag.RagRequest
import com.embabel.agent.rag.RagResponse
import com.embabel.agent.rag.RagService
import com.embabel.common.ai.model.LlmOptions

/**
 * Decorates a Rag Service with an enhancement pipeline.
 */
class PipelinedRagService(
    val delegate: RagService,
    val operationContext: OperationContext,
    val llm: LlmOptions,
) : RagService {

    override val name
        get() = "pipelined(${delegate.name})"

    override val description
        get() = "Pipelined RAG service wrapping ${delegate.name}: ${delegate.description}"

    override fun search(ragRequest: RagRequest): RagResponse {
        val initialResponse = delegate.search(ragRequest)
        val pipeline = AdaptivePipelineRagResponseEnhancer(
            enhancers = buildList {
                add(DeduplicatingEnhancer)
                if (ragRequest.compressionConfig.enabled) {
                    // TODO hardcoded concurrency
                    add(PromptedContextualCompressionEnhancer(operationContext, llm, 15))
                }
                // Add reranking enhancer for improved result relevance
                add(RerankingEnhancer(operationContext, llm))
            }
        )
        val enhanced = pipeline.enhance(initialResponse)
        return enhanced
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "PipelinedRagService wrapping:\n${delegate.infoString(verbose, indent + 2)}"
    }
}
