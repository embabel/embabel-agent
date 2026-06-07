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
 * allowing it to self-correct. Uses a two-tier matching strategy for performance.
 * Throws [ToolNotFoundException] after [maxRetries] consecutive failures.
 *
 * Matching strategy:
 * 1. Fast path: Simple normalization (lowercase + remove non-alphanumeric) for exact matches
 * 2. Fallback: Token-based similarity (Jaccard index) for fuzzy matches
 *
 * Handles:
 * - Delimiter variations: `vector_search` ↔ `vectorSearch` ↔ `vector-search` (simple normalization)
 * - Case insensitivity: `VECTORSEARCH` → `vectorSearch` (simple normalization)
 * - Prefix hallucinations: `ragbot_vectorSearch` → `vectorSearch` (token-based)
 * - Wrapped names: `my_vectorSearch_v2` → `vectorSearch` (token-based)
 *
 * Performance optimizations:
 * - Simple normalization fast path handles ~80% of cases with O(n) complexity
 * - Token-based matching only used when needed with O(n*m) complexity
 * - Skips fuzzy matching for very short tool names (< [minTokenLength])
 * - Returns top 3 closest matches only
 *
 * @param maxRetries maximum number of consecutive failures before throwing
 * @param minTokenLength minimum token length to include in matching (performance optimization)
 * @param minTokenSimilarity minimum Jaccard similarity (0.0-1.0) to consider a match
 */
class AutoCorrectionPolicy(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val minTokenLength: Int = DEFAULT_MIN_TOKEN_LENGTH,
    private val minTokenSimilarity: Double = DEFAULT_MIN_TOKEN_SIMILARITY,
) : ToolNotFoundPolicy {

    private val consecutiveFailures = AtomicInteger(0)

    /**
     * Tokenizes a tool name into normalized tokens.
     * Splits on: camelCase boundaries OR any non-alphanumeric characters (_, -, space, tab, etc.).
     *
     * Examples:
     *   "vectorSearch"        → ["vector", "search"]
     *   "vector_search"       → ["vector", "search"]
     *   "ragbot_vectorSearch" → ["ragbot", "vector", "search"]
     *   "my-tool-v2"          → ["my", "tool", "v2"]
     */
    private fun tokenize(name: String): Set<String> =
        name
            .split(Regex("(?<=[a-z])(?=[A-Z])|[^a-zA-Z0-9]+")) // Split on: camelCase boundaries (lowercase before uppercase) OR non-alphanumeric (_, -, space, tab, etc.)
            .map { it.lowercase() }
            .filter { it.isNotEmpty() && it.length >= minTokenLength }
            .toSet()

    /**
     * Computes Jaccard similarity between two token sets.
     * Jaccard similarity = |intersection| / |union|
     *
     * Returns value between 0.0 (no common tokens) and 1.0 (identical token sets).
     */
    private fun jaccardSimilarity(tokens1: Set<String>, tokens2: Set<String>): Double {
        val intersection = tokens1.intersect(tokens2).size
        val union = tokens1.union(tokens2).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    /**
     * Finds matching tools using token-based similarity (fallback path).
     * Uses Jaccard index to measure token overlap, with substring containment as a boost.
     *
     * Returns up to 3 best matches, or single match if significantly better (>2x) than others.
     */
    private fun findMatchesUsingTokenSimilarity(requestedName: String, availableTools: List<Tool>): List<Tool> {
        val requestedLower = requestedName.lowercase()
        val requestedTokens = tokenize(requestedName)

        return if (requestedTokens.isEmpty()) {
            emptyList()
        } else {
            availableTools
                .filter { tokenize(it.definition.name).isNotEmpty() }
                .map { tool ->
                    val toolTokens = tokenize(tool.definition.name)
                    val toolNameLower = tool.definition.name.lowercase()

                    // Strategy 1: Jaccard token similarity
                    val tokenSimilarity = jaccardSimilarity(requestedTokens, toolTokens)

                    // Strategy 2: Substring containment (fallback for cases like VECTORSEARCH → vectorSearch)
                    val substringMatch = toolNameLower in requestedLower || requestedLower in toolNameLower

                    // Combine: add bounded bonus for substring match without overriding Jaccard ranking
                    val combinedSimilarity = if (substringMatch) minOf(1.0, tokenSimilarity + SUBSTRING_BONUS) else tokenSimilarity

                    tool to combinedSimilarity
                }
                .filter { (_, similarity) -> similarity >= minTokenSimilarity }
                .sortedByDescending { (_, similarity) -> similarity }
                .let { sorted ->
                    // If top match is significantly better (>2x second match), return only the top
                    val topMatch = sorted.firstOrNull()
                    val secondMatch = sorted.getOrNull(1)
                    if (topMatch != null && secondMatch != null && topMatch.second >= 2 * secondMatch.second) {
                        sorted.take(1)
                    } else {
                        sorted.take(3)
                    }
                }
                .map { (tool, _) -> tool }
        }
    }

    override fun handle(requestedName: String, availableTools: List<Tool>): ToolNotFoundAction {
        val failures = consecutiveFailures.incrementAndGet()
        val availableNames = availableTools.map { it.definition.name }
        if (failures > maxRetries) {
            return ToolNotFoundAction.Throw(ToolNotFoundException(requestedName, availableNames))
        }

        // Performance optimization: try simple normalization first (fast path for common cases)
        // Handles delimiter variations: vector_search, vector-search, vector search → vectorsearch
        val normalizedRequest = requestedName.lowercase().replace(Regex("[^a-z0-9]"), "")
        val simpleMatches = availableTools.filter { tool ->
            tool.definition.name.lowercase().replace(Regex("[^a-z0-9]"), "") == normalizedRequest
        }

        // If simple normalization found matches, use them; otherwise fall back to token-based matching
        val matches = simpleMatches.ifEmpty {
            findMatchesUsingTokenSimilarity(requestedName, availableTools)
        }

        val suggestion = when {
            matches.size == 1 -> " Did you mean '${matches[0].definition.name}'?"
            matches.size > 1 -> " Possible matches: ${matches.map { "'${it.definition.name}'" }}."
            else -> ""
        }
        val message = """
            Tool '$requestedName' does not exist.$suggestion
            Available tools: $availableNames.
            Use the exact tool name from this list.
            Do not combine or prefix tool names — tool names found in source code or search results are not callable.
        """.trimIndent().replace("\n", " ")
        return ToolNotFoundAction.FeedbackToModel(message)
    }

    override fun onToolFound() {
        consecutiveFailures.set(0)
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_MIN_TOKEN_LENGTH = 3
        const val DEFAULT_MIN_TOKEN_SIMILARITY = 0.25
        const val SUBSTRING_BONUS = 0.2
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
