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

import com.embabel.agent.event.RagEventListener
import com.embabel.agent.rag.CompressionConfig
import com.embabel.agent.rag.RagRequestRefinement
import com.embabel.agent.rag.RagResponseFormatter
import com.embabel.agent.rag.SimpleRagResponseFormatter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.ZeroToOne

/**
 * Operations for RAG use as an LLM tool. Options are immutable and stable.
 * @param similarityThreshold minimum similarity threshold for results (0.0 to 1.0)
 * @param topK maximum number of results to return
 * @param labels optional set of labels to filter results. If not set all entities may be
 * returned. If set, only the given entities will be searched for.
 * @param ragResponseFormatter formatter to convert RagResponse to String
 * @param service optional name of the RAG service to use. If null, the default service will be used.
 */
data class RagOptions @JvmOverloads constructor(
    override val similarityThreshold: ZeroToOne = 0.7,
    override val topK: Int = 8,
    override val compressionConfig: CompressionConfig = CompressionConfig(),
    val llm: LlmOptions = LlmOptions.withAutoLlm(),
    override val labels: Set<String> = emptySet(),
    val ragResponseFormatter: RagResponseFormatter = SimpleRagResponseFormatter,
    val service: String? = null,
    val listener: RagEventListener = RagEventListener.NOOP,
) : RagRequestRefinement {

    fun withSimilarityThreshold(similarityThreshold: ZeroToOne): RagOptions {
        return copy(similarityThreshold = similarityThreshold)
    }

    fun withTopK(topK: Int): RagOptions {
        return copy(topK = topK)
    }

    fun withListener(listener: RagEventListener): RagOptions {
        return copy(listener = this.listener + listener)
    }

    fun withCompressionConfig(compressionConfig: CompressionConfig): RagOptions {
        return copy(compressionConfig = compressionConfig)
    }

    /**
     * Return an instance using the given Rag service name.
     */
    fun withService(service: String): RagOptions {
        return copy(service = service)
    }

}
