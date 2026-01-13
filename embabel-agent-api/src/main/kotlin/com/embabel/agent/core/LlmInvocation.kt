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
import com.embabel.agent.spi.LlmService
import com.embabel.common.core.types.Timed
import com.embabel.common.core.types.Timestamped
import org.springframework.ai.chat.metadata.DefaultUsage
import java.time.Duration
import java.time.Instant

interface LlmInvocationHistory {

    val llmInvocations: List<LlmInvocation>

    val toolsStats: ToolsStats

    fun cost(): Double {
        return llmInvocations.sumOf { it.cost() }
    }

    /**
     * Distinct list of LLMs use, sorted by name.
     */
    fun modelsUsed(): List<LlmService<*>> {
        return llmInvocations.map { it.llmService }
            .distinctBy { it.name }
            .sortedBy { it.name }
    }

    /**
     * Note that this is not apples to apples: The usage
     * may be across different LLMs, and the cost may be different.
     * Cost will correctly reflect this.
     * Look in the list for more details about what tokens were spent where.
     */
    fun usage(): Usage {
        val promptTokens = llmInvocations.sumOf { it.usage.promptTokens ?: 0 }
        val completionTokens = llmInvocations.sumOf { it.usage.completionTokens ?: 0 }
        return Usage(promptTokens, completionTokens, null)
    }

    fun costInfoString(verbose: Boolean): String {
        val usage = usage()
        return if (verbose)
            """|LLMs used: ${modelsUsed().map { it.name }} across ${llmInvocations.size} calls
               |Prompt tokens: ${"%,d".format(usage.promptTokens)},
               |Completion tokens: ${"%,d".format(usage.completionTokens)}
               |Cost: $${"%.4f".format(cost())}
               |"""
                .trimMargin()
        else "LLMs: ${modelsUsed().map { it.name }} across ${llmInvocations.size} calls; " +
                "prompt tokens: ${"%,d".format(usage.promptTokens)}; completion tokens: ${
                    "%,d".format(
                        usage.completionTokens
                    )
                }; cost: $${"%.4f".format(cost())}"
    }

}

/**
 * LLM usage data
 */
data class Usage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val nativeUsage: Any?,
) {

    val totalTokens: Int?
        get() = when {
            promptTokens == null && completionTokens == null -> null
            else -> (promptTokens ?: 0) + (completionTokens ?: 0)
        }

    /**
     * Combine two Usage instances by summing their token counts.
     * Used for accumulating usage across multiple LLM calls in a tool loop.
     */
    operator fun plus(other: Usage): Usage = Usage(
        promptTokens = when {
            this.promptTokens == null && other.promptTokens == null -> null
            else -> (this.promptTokens ?: 0) + (other.promptTokens ?: 0)
        },
        completionTokens = when {
            this.completionTokens == null && other.completionTokens == null -> null
            else -> (this.completionTokens ?: 0) + (other.completionTokens ?: 0)
        },
        nativeUsage = null, // Cannot combine native usage objects
    )
}

/**
 * Invocation we made to an LLM
 * @param agentName name of the agent, if known
 */
data class LlmInvocation(
    val llmService: LlmService<*>,
    val usage: Usage,
    val agentName: String? = null,
    override val timestamp: Instant,
    override val runningTime: Duration,
) : Timestamped, Timed {

    /**
     * Dollar cost of this interaction.
     */
    fun cost(): Double = llmService.pricingModel?.costOf(
        DefaultUsage(
            usage.promptTokens ?: 0,
            usage.completionTokens ?: 0,
        )
    ) ?: 0.0
}
