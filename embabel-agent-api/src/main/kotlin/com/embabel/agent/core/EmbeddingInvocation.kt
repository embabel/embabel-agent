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
package com.embabel.agent.core

import com.embabel.common.ai.model.EmbeddingServiceMetadata
import com.embabel.common.core.types.Timed
import com.embabel.common.core.types.Timestamped
import org.springframework.ai.chat.metadata.DefaultUsage
import java.time.Duration
import java.time.Instant

/**
 * History of embedding invocations made during an agent process or other
 * embedding-consuming component (e.g. ingestion pipelines, RAG indexing).
 *
 * Methods on this interface report embedding-only figures.
 * For aggregate cost/usage that combines LLM and embedding calls, see [AgentProcess].
 */
interface EmbeddingInvocationHistory {

    val embeddingInvocations: List<EmbeddingInvocation>
        get() = emptyList()

    fun embeddingCost(): Double = embeddingInvocations.sumOf { it.cost() }

    /**
     * Distinct list of embedding services used, sorted by name.
     */
    fun embeddingModelsUsed(): List<EmbeddingServiceMetadata> =
        embeddingInvocations.map { it.embeddingMetadata }
            .distinctBy { it.name }
            .sortedBy { it.name }

    /**
     * Aggregated token usage across all embedding calls.
     * Embeddings have no completion tokens, so [Usage.completionTokens] is null.
     */
    fun embeddingUsage(): Usage {
        val promptTokens = embeddingInvocations.sumOf { it.usage.promptTokens ?: 0 }
        return Usage(promptTokens, null, null)
    }

    /** Total embedding call count. Default = own; overridable for subtree aggregation. */
    fun embeddingInvocationCount(): Int = embeddingInvocations.size

    fun embeddingCostInfoString(verbose: Boolean): String =
        formatInvocationSummary(
            label = "Embeddings",
            modelNames = embeddingModelsUsed().map { it.name },
            callCount = embeddingInvocationCount(),
            promptTokens = embeddingUsage().promptTokens ?: 0,
            completionTokens = null,
            cost = embeddingCost(),
            verbose = verbose,
        )
}

/**
 * Invocation we made to an [EmbeddingServiceMetadata].
 * Mirror of [LlmInvocation] for embedding services.
 *
 * Embeddings have no completion tokens, only prompt tokens.
 *
 * @param agentName name of the agent, if known
 */
data class EmbeddingInvocation(
    val embeddingMetadata: EmbeddingServiceMetadata,
    val usage: Usage,
    val agentName: String? = null,
    override val timestamp: Instant,
    override val runningTime: Duration,
) : Timestamped, Timed {

    /**
     * Dollar cost of this embedding call.
     * Computed from prompt tokens only (embeddings have no completion tokens).
     */
    fun cost(): Double = embeddingMetadata.pricingModel?.costOf(
        DefaultUsage(usage.promptTokens ?: 0, 0)
    ) ?: 0.0
}
