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
package com.embabel.agent.tools.process

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import com.embabel.agent.core.AgentProcess
import java.time.Duration

/**
 * Tools for accessing information about the current agent process.
 *
 * This is an [UnfoldingTool] that exposes sub-tools for:
 * - Status: current state, running time, process ID
 * - Budget: limits and remaining capacity
 * - Cost: current spend, token usage, models used
 * - History: actions taken so far
 * - Tools: tool usage statistics
 *
 * Uses [AgentProcess.get] to access the current process.
 *
 * Example usage:
 * ```kotlin
 * val tools = AgentProcessTools().create()
 * // Add to an agentic tool
 * SimpleAgenticTool("assistant", "...")
 *     .withTools(tools)
 * ```
 */
class AgentProcessTools {

    /**
     * Create an UnfoldingTool for agent process information.
     */
    fun create(): UnfoldingTool = UnfoldingTool.of(
        name = "agent_process",
        description = "Access information about the current agent process including status, budget, cost, and history. " +
            "Invoke to see available operations.",
        innerTools = listOf(
            createStatusTool(),
            createBudgetTool(),
            createCostTool(),
            createHistoryTool(),
            createToolStatsTool(),
            createModelsUsedTool(),
        ),
        childToolUsageNotes = "Use process_status for current state and runtime. " +
            "Use process_budget to check limits. Use process_cost to see spending.",
    )

    private fun getAgentProcessOrError(): Pair<AgentProcess?, Tool.Result?> {
        val agentProcess = AgentProcess.get()
            ?: return null to Tool.Result.text("No agent process available. These tools require an active agent context.")
        return agentProcess to null
    }

    private fun formatDuration(duration: Duration): String {
        val totalSeconds = duration.seconds
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = duration.toMillisPart()

        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            seconds > 0 -> "${seconds}.${millis / 100}s"
            else -> "${millis}ms"
        }
    }

    private fun createStatusTool(): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = "process_status",
            description = "Get the current process status, ID, running time, and goal information",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result {
            val (process, error) = getAgentProcessOrError()
            if (error != null) return error

            val p = process!!
            val goalInfo = p.goal?.let { "Goal: ${it.name}" } ?: "No goal (utility process)"

            return Tool.Result.text(
                """
                |Process ID: ${p.id}
                |Status: ${p.status}
                |Running time: ${formatDuration(p.runningTime)}
                |$goalInfo
                |Parent ID: ${p.parentId ?: "none (root process)"}
                """.trimMargin()
            )
        }
    }

    private fun createBudgetTool(): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = "process_budget",
            description = "Get the budget limits and current usage against those limits",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result {
            val (process, error) = getAgentProcessOrError()
            if (error != null) return error

            val p = process!!
            val budget = p.processOptions.budget
            val currentCost = p.cost()
            val usage = p.usage()
            val actionsUsed = p.history.size

            val costRemaining = budget.cost - currentCost
            val tokensRemaining = budget.tokens - (usage.promptTokens ?: 0) - (usage.completionTokens ?: 0)
            val actionsRemaining = budget.actions - actionsUsed

            return Tool.Result.text(
                """
                |Budget Status:
                |
                |Cost limit: $${String.format("%.4f", budget.cost)}
                |  Used: $${String.format("%.4f", currentCost)}
                |  Remaining: $${String.format("%.4f", costRemaining)}
                |
                |Token limit: ${"%,d".format(budget.tokens)}
                |  Used: ${"%,d".format((usage.promptTokens ?: 0) + (usage.completionTokens ?: 0))}
                |  Remaining: ${"%,d".format(tokensRemaining)}
                |
                |Action limit: ${budget.actions}
                |  Used: $actionsUsed
                |  Remaining: $actionsRemaining
                """.trimMargin()
            )
        }
    }

    private fun createCostTool(): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = "process_cost",
            description = "Get detailed cost and token usage information",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result {
            val (process, error) = getAgentProcessOrError()
            if (error != null) return error

            val p = process!!
            val usage = p.usage()
            val totalCost = p.cost()
            val invocationCount = p.llmInvocations.size

            return Tool.Result.text(
                """
                |Cost and Usage:
                |
                |Total cost: $${String.format("%.6f", totalCost)}
                |LLM invocations: $invocationCount
                |
                |Tokens used:
                |  Prompt tokens: ${"%,d".format(usage.promptTokens ?: 0)}
                |  Completion tokens: ${"%,d".format(usage.completionTokens ?: 0)}
                |  Total tokens: ${"%,d".format((usage.promptTokens ?: 0) + (usage.completionTokens ?: 0))}
                """.trimMargin()
            )
        }
    }

    private fun createHistoryTool(): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = "process_history",
            description = "Get the history of actions taken in this process",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result {
            val (process, error) = getAgentProcessOrError()
            if (error != null) return error

            val p = process!!
            val history = p.history

            if (history.isEmpty()) {
                return Tool.Result.text("No actions have been executed yet.")
            }

            val historyText = history.mapIndexed { index, action ->
                "${index + 1}. ${action.actionName} (${formatDuration(action.runningTime)})"
            }.joinToString("\n")

            return Tool.Result.text(
                """
                |Action history (${history.size} action(s)):
                |
                |$historyText
                """.trimMargin()
            )
        }
    }

    private fun createToolStatsTool(): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = "process_tools_stats",
            description = "Get statistics about tool usage in this process",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result {
            val (process, error) = getAgentProcessOrError()
            if (error != null) return error

            val p = process!!
            val stats = p.toolsStats.toolsStats

            if (stats.isEmpty()) {
                return Tool.Result.text("No tools have been called yet.")
            }

            val totalCalls = stats.values.sumOf { it.calls }
            val toolsSummary = stats.values
                .sortedByDescending { it.calls }
                .joinToString("\n") { "  ${it.name}: ${it.calls} calls" }

            return Tool.Result.text(
                """
                |Tool Usage Statistics:
                |
                |Total tool calls: $totalCalls
                |Unique tools used: ${stats.size}
                |
                |Calls by tool:
                |$toolsSummary
                """.trimMargin()
            )
        }
    }

    private fun createModelsUsedTool(): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = "process_models",
            description = "Get information about which LLM models have been used",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result {
            val (process, error) = getAgentProcessOrError()
            if (error != null) return error

            val p = process!!
            val models = p.modelsUsed()

            if (models.isEmpty()) {
                return Tool.Result.text("No LLM models have been used yet.")
            }

            val modelsText = models.joinToString("\n") { model ->
                "- ${model.name} (${model.provider})"
            }

            return Tool.Result.text(
                """
                |Models used (${models.size}):
                |
                |$modelsText
                """.trimMargin()
            )
        }
    }
}
