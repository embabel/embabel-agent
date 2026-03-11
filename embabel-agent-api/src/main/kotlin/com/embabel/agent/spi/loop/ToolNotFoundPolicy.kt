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
package com.embabel.agent.spi.loop

import com.embabel.agent.api.tool.Tool
import java.util.concurrent.atomic.AtomicInteger

/**
 * Determines how the tool loop responds when the LLM calls a tool
 * that does not exist in the available set.
 *
 * @see AutoCorrectionPolicy
 * @see ImmediateThrowPolicy
 */
interface ToolNotFoundPolicy {

    /**
     * Handle a tool-not-found event.
     *
     * @param requestedName the tool name the LLM requested
     * @param availableTools the currently available tools
     * @return the action the tool loop should take
     */
    fun handle(requestedName: String, availableTools: List<Tool>): ToolNotFoundAction

    /**
     * Called when a tool is found successfully, allowing stateful
     * policies to reset internal counters.
     */
    fun onToolFound() {
        // No-op default for stateless policies
    }
}

/**
 * Action returned by [ToolNotFoundPolicy.handle].
 */
sealed class ToolNotFoundAction {

    /**
     * Feed an error message back to the LLM so it can self-correct.
     */
    data class FeedbackToModel(val message: String) : ToolNotFoundAction()

    /**
     * Throw [ToolNotFoundException] — recovery is not possible or not desired.
     */
    data class Throw(val exception: ToolNotFoundException) : ToolNotFoundAction()
}

/**
 * Feeds the error back to the LLM with a fuzzy-match suggestion,
 * allowing it to self-correct. Matches tool names using case-insensitive
 * containment (either direction). Throws [ToolNotFoundException] after
 * [maxRetries] consecutive failures.
 */
class AutoCorrectionPolicy(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val minFuzzyLength: Int = DEFAULT_MIN_FUZZY_LENGTH,
) : ToolNotFoundPolicy {

    private val consecutiveFailures = AtomicInteger(0)

    override fun handle(requestedName: String, availableTools: List<Tool>): ToolNotFoundAction {
        val failures = consecutiveFailures.incrementAndGet()
        val availableNames = availableTools.map { it.definition.name }
        if (failures > maxRetries) {
            return ToolNotFoundAction.Throw(ToolNotFoundException(requestedName, availableNames))
        }
        val requestedLower = requestedName.lowercase()
        val matches = if (requestedLower.length < minFuzzyLength) {
            emptyList()
        } else {
            val requestedTokens = requestedLower.split("_", "-").filter { it.length >= minFuzzyLength }
            availableTools.filter {
                val nameLower = it.definition.name.lowercase()
                nameLower.length >= minFuzzyLength && (
                    requestedLower.contains(nameLower) || nameLower.contains(requestedLower) ||
                        requestedTokens.any { token -> nameLower.contains(token) }
                    )
            }
        }
        val suggestion = when {
            matches.size == 1 -> " Did you mean '${matches[0].definition.name}'?"
            matches.size > 1 -> " Possible matches: ${matches.map { "'${it.definition.name}'" }}."
            else -> ""
        }
        val message = "Tool '$requestedName' does not exist.$suggestion " +
            "Available tools: $availableNames. " +
            "Use the exact tool name from this list. " +
            "Do not combine or prefix tool names — tool names found in source code or search results are not callable."
        return ToolNotFoundAction.FeedbackToModel(message)
    }

    override fun onToolFound() {
        consecutiveFailures.set(0)
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_MIN_FUZZY_LENGTH = 3
    }
}

/**
 * Throws [ToolNotFoundException] immediately on first unknown tool call.
 */
object ImmediateThrowPolicy : ToolNotFoundPolicy {

    override fun handle(requestedName: String, availableTools: List<Tool>): ToolNotFoundAction {
        val availableNames = availableTools.map { it.definition.name }
        return ToolNotFoundAction.Throw(ToolNotFoundException(requestedName, availableNames))
    }
}
