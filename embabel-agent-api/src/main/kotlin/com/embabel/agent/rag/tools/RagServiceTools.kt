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
package com.embabel.agent.rag.tools

import com.embabel.agent.api.common.support.SelfToolCallbackPublisher
import com.embabel.agent.rag.RagRequestRefinement
import com.embabel.agent.rag.RagResponseFormatter
import com.embabel.agent.rag.RagService
import com.embabel.agent.rag.SimpleRagResponseFormatter
import com.embabel.common.core.types.ZeroToOne
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/**
 * Operations for RAG use as an LLM tool.
 */
data class RagOptions @JvmOverloads constructor(
    override val similarityThreshold: ZeroToOne = 0.7,
    override val topK: Int = 8,
    override val labels: Set<String> = emptySet(),
    val ragResponseFormatter: RagResponseFormatter = SimpleRagResponseFormatter,
) : RagRequestRefinement {

    fun withSimilarityThreshold(similarityThreshold: ZeroToOne): RagOptions {
        return copy(similarityThreshold = similarityThreshold)
    }

    fun withTopK(topK: Int): RagOptions {
        return copy(topK = topK)
    }
}

/**
 * Expose a RagService as tools. Options are stable.
 */
interface RagServiceTools : SelfToolCallbackPublisher {

    val ragService: RagService

    val options: RagOptions

    @Tool(description = "Query the RAG service")
    fun search(
        @ToolParam(
            description = "Query to search for",
        )
        query: String,
    ): String {
        return options.ragResponseFormatter.format(ragService.search(options.toRequest(query)))
    }

    companion object {

        operator fun invoke(
            ragService: RagService,
            options: RagOptions,
        ): RagServiceTools = RagServiceToolsImpl(
            ragService = ragService,
            options = options,
        )

        @JvmStatic
        fun create(
            ragService: RagService,
            options: RagOptions,
        ) = invoke(ragService, options)
    }

}

private class RagServiceToolsImpl(
    override val ragService: RagService,
    override val options: RagOptions,
) : RagServiceTools
