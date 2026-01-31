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
package com.embabel.agent.rag.tools

import com.embabel.agent.api.common.LlmReference
import com.embabel.agent.api.tool.DelegatingTool
import com.embabel.agent.api.tool.MatryoshkaTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.filter.PropertyFilter
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.FinderOperations
import com.embabel.agent.rag.service.RegexSearchOperations
import com.embabel.agent.rag.service.ResultExpander
import com.embabel.agent.rag.service.RetrievableResultsFormatter
import com.embabel.agent.rag.service.SearchOperations
import com.embabel.agent.rag.service.SimpleRetrievableResultsFormatter
import com.embabel.agent.rag.service.TextSearch
import com.embabel.agent.rag.service.VectorSearch
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.Timed
import com.embabel.common.core.types.Timestamped
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Event representing results from a RAG search operation
 * @param source the source of the event, e.g. the ToolishRag instance
 */
data class ResultsEvent(
    val source: SearchTools,
    val query: String,
    val results: List<SimilarityResult<out Retrievable>>,
    override val runningTime: Duration,
    override val timestamp: Instant = Instant.now().minus(runningTime),
) : Timed, Timestamped

fun interface ResultsListener {

    fun onResultsEvent(event: ResultsEvent)

}

/**
 * Reference for fine-grained RAG tools, allowing the LLM to
 * control individual search operations directly.
 * Add hints as relevant.
 * If a ResultListener is provided, results will be sent to it as they are retrieved.
 * This enables logging, monitoring, or putting results on a blackboard for later use,
 * versus relying on the LLM to remember them.
 * @param name the name of the RAG reference
 * @param description the description of the RAG reference. Important to guide LLM to correct usage
 * @param searchOperations the search operations to use. If this implements the SearchTools tag interface,
 * its own tools will be exposed. For example, a Neo store might expose Cypher-driven search
 * or a relational database SQL-driven search.
 * @param goal the goal for acceptance criteria when searching
 * @param formatter the formatter to use for formatting results
 * @param vectorSearchFor list of retrievable types to enable vector search for.
 * Defaults to Chunk
 * @param textSearchFor list of retrievable types to enable text search for.
 * Defaults to Chunk
 * @param hints list of hints to provide to the LLM
 * @param listener optional listener to receive raw structured results as they are retrieved
 * @param metadataFilter optional filter applied to [com.embabel.agent.rag.model.Datum.metadata].
 * Useful for multi-tenant scenarios where searches should be scoped to a specific owner.
 * The filter is applied transparently - the LLM does not see or control it.
 * @param entityFilter optional filter applied to object properties
 * (e.g., [com.embabel.agent.rag.model.NamedEntityData.properties] or typed entity fields).
 * The filter is applied transparently - the LLM does not see or control it.
 */
data class ToolishRag @JvmOverloads constructor(
    override val name: String,
    override val description: String,
    private val searchOperations: SearchOperations,
    val goal: String = DEFAULT_GOAL,
    val formatter: RetrievableResultsFormatter = SimpleRetrievableResultsFormatter,
    val vectorSearchFor: List<Class<out Retrievable>> = listOf(Chunk::class.java),
    val textSearchFor: List<Class<out Retrievable>> = listOf(Chunk::class.java),
    val hints: List<PromptContributor> = listOf(),
    val listener: ResultsListener? = null,
    val metadataFilter: PropertyFilter? = null,
    val entityFilter: EntityFilter? = null,
) : LlmReference, DelegatingTool {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val validHints = hints.toMutableList()

    private val toolObjects: List<Any> = run {
        buildList {
            // If the search operations already implement SearchTools, use them directly
            if (searchOperations is SearchTools) {
                logger.info("Adding existing SearchTools to ToolishRag '{}'", name)
                add(searchOperations)
            }
            // This can confuse guide. Let's skip it for now.
//            if (searchOperations is TypeRetrievalOperations) {
//                logger.info("Adding TypeRetrievalTools to ToolishRag '{}'", name)
//                add(TypeRetrievalTools(searchOperations))
//            }
            if (searchOperations is FinderOperations) {
                logger.info("Adding FinderTools to ToolishRag '{}'", name)
                add(FinderTools(searchOperations))
            }
            if (searchOperations is VectorSearch) {
                logger.info("Adding VectorSearchTools to ToolishRag '{}'", name)
                add(VectorSearchTools(searchOperations, vectorSearchFor, metadataFilter, entityFilter, listener))
            } else {
                if (hints.any { it is TryHyDE }) {
                    logger.warn(
                        "HyDE hint provided but no VectorSearch available in ToolishRag: Removing this hint {}",
                        name
                    )
                    validHints.removeIf { it is TryHyDE }
                }
            }
            if (searchOperations is TextSearch) {
                logger.info("Adding TextSearchTools to ToolishRag '{}'", name)
                add(TextSearchTools(searchOperations, textSearchFor, metadataFilter, entityFilter, listener))
            }
            if (searchOperations is ResultExpander) {
                logger.info("Adding ResultExpanderTools to ToolishRag '{}'", name)
                add(ResultExpanderTools(searchOperations))
            }
            if (searchOperations is RegexSearchOperations) {
                logger.info("Adding RegexSearchTools to ToolishRag '{}'", name)
                add(RegexSearchTools(searchOperations, metadataFilter, entityFilter, listener))
            }
        }
    }

    /**
     * Set the types to search for with vector and text search
     * @param vectorSearchFor list of retrievable types to enable vector search for
     * @param textSearchFor list of retrievable types to enable text search for
     * If only vectorSearchFor is provided, textSearchFor will be set to the same types
     */
    @JvmOverloads
    fun withSearchFor(
        vectorSearchFor: List<Class<out Retrievable>>,
        textSearchFor: List<Class<out Retrievable>> = vectorSearchFor,
    ): ToolishRag =
        copy(
            vectorSearchFor = vectorSearchFor,
            textSearchFor = textSearchFor,
        )

    /**
     * Add a hint to the RAG reference
     */
    fun withHint(hint: PromptContributor): ToolishRag =
        copy(hints = hints + hint)

    /**
     * Set a custom goal for acceptance criteria
     */
    fun withGoal(goal: String): ToolishRag =
        copy(goal = goal)

    /**
     * With a listener that sees the raw (structured) results rather than strings.
     * This can be useful for logging, monitoring, gathering data to improve quality
     * or putting results in the blackboard
     */
    fun withListener(listener: ResultsListener): ToolishRag =
        copy(listener = listener)

    /**
     * Set a metadata filter to apply to all searches.
     * Useful for multi-tenant scenarios where searches should be scoped to a specific owner.
     * The filter is applied transparently - the LLM does not see or control it.
     */
    fun withMetadataFilter(filter: PropertyFilter): ToolishRag =
        copy(metadataFilter = filter)

    /**
     * Set an entity filter to apply to all searches.
     * Filters on object properties (e.g., entity fields) and labels rather than metadata.
     * The filter is applied transparently - the LLM does not see or control it.
     */
    fun withEntityFilter(filter: EntityFilter): ToolishRag =
        copy(entityFilter = filter)

    // LlmReference: returns flat list of inner tools (backward compatible)
    override fun tools(): List<Tool> = toolObjects.flatMap { Tool.fromInstance(it) }

    // Tool interface implementation via lazy MatryoshkaTool
    // When used directly as a Tool, wraps all inner tools in a MatryoshkaTool
    // Implements DelegatingTool so MatryoshkaToolInjectionStrategy can unwrap it
    override val delegate: Tool by lazy {
        MatryoshkaTool.of(
            name = name,
            description = description,
            innerTools = tools(),
        )
    }

    override val definition: Tool.Definition
        get() = delegate.definition

    override fun call(input: String): Tool.Result =
        delegate.call(input)

    override fun notes() = """
        ${
        (searchOperations as? TextSearch)?.let {
            "Lucene search syntax support: ${searchOperations.luceneSyntaxNotes}\n"
        }
    }
        Hints: ${validHints.joinToString("\n") { it.contribution() }}
        Search acceptance criteria:
        $goal
      """.trimIndent()

    companion object {
        val DEFAULT_GOAL = """
            Continue search until the question is answered, or you have to give up.
            Be creative, try different types of queries.
            Be thorough and try different approaches.
            If nothing works, report that you could not find the answer.
        """.trimIndent()
    }
}

/**
 * Marker interface for RAG search tools
 * Implementations should provide search functionality
 * via methods annotated with @LlmTool
 */
interface SearchTools
