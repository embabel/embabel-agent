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

import com.embabel.agent.api.common.ToolsStats
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.core.types.Timed
import com.embabel.common.core.types.Timestamped
import java.time.Duration
import java.time.Instant

/**
 * History of LLM invocations made during an agent process.
 *
 * Methods on this interface report LLM-only figures.
 * For the aggregate including other invocation types (e.g. embeddings),
 * see [AgentProcess.totalCost], [AgentProcess.totalUsage], etc.
 */
interface LlmInvocationHistory {

    val llmInvocations: List<LlmInvocation>

    val toolsStats: ToolsStats

    fun cost(): Double {
        return llmInvocations.sumOf { it.cost() }
    }

    /**
     * Distinct list of LLMs used, sorted by name.
     */
    fun modelsUsed(): List<LlmMetadata> {
        return llmInvocations.map { it.llmMetadata }
            .distinctBy { it.name }
            .sortedBy { it.name }
    }

    /**
     * Note that this is not apples to apples: The usage
     * may be across different LLMs, and the cost may be different.
     * Cost will correctly reflect this.
     * Look in the list for more details about what tokens were spent where.
     */
    fun usage(): Usage =
        llmInvocations.fold(Usage(0, 0, null)) { total, invocation -> total + invocation.usage }

    /** Total LLM call count. Default = own; overridable for subtree aggregation. */
    fun llmInvocationCount(): Int = llmInvocations.size

    fun costInfoString(verbose: Boolean): String =
        formatInvocationSummary(
            label = "LLMs",
            modelNames = modelsUsed().map { it.name },
            callCount = llmInvocationCount(),
            promptTokens = usage().promptTokens ?: 0,
            completionTokens = usage().completionTokens,
            cost = cost(),
            verbose = verbose,
        )
}

/**
 * Invocation we made to an LLM
 * @param agentName name of the agent, if known
 */
data class LlmInvocation(
    val llmMetadata: LlmMetadata,
    val usage: Usage,
    val agentName: String? = null,
    override val timestamp: Instant,
    override val runningTime: Duration,
) : Timestamped, Timed {

    /**
     * Dollar cost of this interaction.
     */
    fun cost(): Double {
        val pricingModel = llmMetadata.pricingModel ?: return 0.0
        val cacheRead = usage.cacheReadInputTokens ?: 0
        val cacheWrite = usage.cacheWriteInputTokens ?: 0
        return pricingModel.costOf(
            inputTokens = ((usage.promptTokens ?: 0) - cacheRead - cacheWrite).coerceAtLeast(0),
            outputTokens = usage.completionTokens ?: 0,
            cacheReadInputTokens = cacheRead,
            cacheWriteInputTokens = cacheWrite,
        )
    }
}
