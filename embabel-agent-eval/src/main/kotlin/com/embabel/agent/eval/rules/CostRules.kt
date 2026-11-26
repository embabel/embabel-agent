package com.example.agenteval.rules

import com.embabel.agent.core.AgentProcess
import com.example.agenteval.AgentEvalRule

/** Token/cost summary of one run. */
data class CostReport(
    val totalTokens: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalCost: Double,
    val llmCalls: Int,
    val models: List<String>,
)

object CostRules {

    const val DEFAULT_TOKEN_BUDGET: Int = 100_000
    const val DEFAULT_COST_BUDGET: Double = 1.00

    fun overTokenBudget(maxTokens: Int = DEFAULT_TOKEN_BUDGET): AgentEvalRule =
        AgentEvalRule { p -> (p.totalUsage().totalTokens ?: 0) > maxTokens }

    fun overPromptTokens(maxTokens: Int): AgentEvalRule =
        AgentEvalRule { p -> (p.totalUsage().promptTokens ?: 0) > maxTokens }

    fun overCompletionTokens(maxTokens: Int): AgentEvalRule =
        AgentEvalRule { p -> (p.totalUsage().completionTokens ?: 0) > maxTokens }


    fun overCostBudget(maxCost: Double = DEFAULT_COST_BUDGET): AgentEvalRule =
        AgentEvalRule { p -> p.totalCost() > maxCost }

    fun anyLlmCallOverCost(max: Double): AgentEvalRule =
        AgentEvalRule { p -> p.llmInvocations.any { it.cost() > max } }


    fun llmCallsAtLeast(n: Int): AgentEvalRule =
        AgentEvalRule { p -> p.llmInvocationCount() >= n }


    fun usedModel(modelName: String): AgentEvalRule =
        AgentEvalRule { p -> p.modelsUsed().any { it.name == modelName } }

    val usedNoLlm = AgentEvalRule { p -> p.llmInvocationCount() == 0 }

    fun costReport(process: AgentProcess): CostReport {
        val u = process.totalUsage()
        return CostReport(
            totalTokens = u.totalTokens ?: 0,
            promptTokens = u.promptTokens ?: 0,
            completionTokens = u.completionTokens ?: 0,
            totalCost = process.totalCost(),
            llmCalls = process.llmInvocationCount(),
            models = process.modelsUsed().map { it.name },
        )
    }

    val named: Map<String, AgentEvalRule> = mapOf(
        "overTokenBudget" to overTokenBudget(),
        "overCostBudget" to overCostBudget(),
    )
}