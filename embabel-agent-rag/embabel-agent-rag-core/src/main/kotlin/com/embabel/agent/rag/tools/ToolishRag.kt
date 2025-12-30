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

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.common.LlmReference
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentElement
import com.embabel.agent.rag.model.Embeddable
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.*
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.*
import com.embabel.common.util.loggerFor
import org.slf4j.Logger
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
 * @param hints list of hints to provide to the LLM
 * @param listener optional listener to receive raw structured results as they are retrieved
 */
data class ToolishRag @JvmOverloads constructor(
    override val name: String,
    override val description: String,
    private val searchOperations: SearchOperations,
    val goal: String = DEFAULT_GOAL,
    val formatter: RetrievableResultsFormatter = SimpleRetrievableResultsFormatter,
    val hints: List<PromptContributor> = listOf(),
    val listener: ResultsListener? = null,
) : LlmReference {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val validHints = hints.toMutableList()

    private val toolInstances: List<Any> = run {
        buildList {
            if (searchOperations is SearchTools) {
                logger.info("Adding existing SearchTools to ToolishRag tools {}", name)
                add(searchOperations)
            }
            if (searchOperations is VectorSearch) {
                logger.info("Adding VectorSearchTools to ToolishRag tools {}", name)
                add(VectorSearchTools(searchOperations, listener))
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
                logger.info("Adding TextSearchTools to ToolishRag tools {}", name)
                add(TextSearchTools(searchOperations, listener))
            }
            if (searchOperations is ResultExpander) {
                logger.info("Adding ResultExpanderTools to ToolishRag tools {}", name)
                add(ResultExpanderTools(searchOperations))
            }
            if (searchOperations is RegexSearchOperations) {
                logger.info("Adding RegexSearchTools to ToolishRag tools {}", name)
                add(RegexSearchTools(searchOperations, listener))
            }
        }
    }

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

    override fun toolInstances() = toolInstances

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
 */
interface SearchTools

/**
 * Classic vector search
 */
class VectorSearchTools(
    private val vectorSearch: VectorSearch,
    private val resultsListener: ResultsListener? = null,
) : SearchTools {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    @LlmTool(description = "Perform vector search. Specify topK and similarity threshold from 0-1")
    fun vectorSearch(
        query: String,
        topK: Int,
        @LlmTool.Param(description = "similarity threshold from 0-1") threshold: ZeroToOne,
    ): String {
        logger.info("Performing vector search with query='{}', topK={}, threshold={}", query, topK, threshold)
        val start = Instant.now()
        val results = vectorSearch.vectorSearch(
            SimpleSearchRequest(query, threshold, topK),
            Chunk::class.java
        )
        val runningTime = Duration.between(start, Instant.now())
        resultsListener?.onResultsEvent(ResultsEvent(this, query, results, runningTime))
        return SimpleRetrievableResultsFormatter.formatResults(SimilarityResults.fromList(results))
    }
}

/**
 * Tools to expand chunks around an anchor chunk that has already been retrieved
 */
class ResultExpanderTools(
    private val resultExpander: ResultExpander,
) : SearchTools {

    @LlmTool(description = "given a chunk ID, expand to surrounding chunks")
    fun broadenChunk(
        @LlmTool.Param(description = "id of the chunk to expand") chunkId: String,
        @LlmTool.Param(description = "chunksToAdd", required = false) chunksToAdd: Int = 2,
    ): String {
        val expandedElements = resultExpander.expandResult(chunkId, ResultExpander.Method.SEQUENCE, chunksToAdd)
        return expandedElements
            .filterIsInstance<Chunk>()
            .joinToString("\n") { chunk ->
                "Chunk ID: ${chunk.id}\nContent: ${chunk.text}\n"
            }
    }

    @LlmTool(description = "given a content element ID, expand to parent section")
    fun zoomOut(
        @LlmTool.Param(description = "id of the content element to expand") id: String,
    ): String {
        val expandedElements: List<ContentElement> = resultExpander.expandResult(id, ResultExpander.Method.ZOOM_OUT, 1)
        return expandedElements
            .filter { it is Embeddable }
            .joinToString("\n") { contentElement ->
                "${contentElement.javaClass.simpleName}: id=${contentElement.id}\nContent: ${(contentElement as Embeddable).embeddableValue()}\n"
            }
    }

}

/**
 * Tools to perform text search operations with Lucene syntax
 */
class TextSearchTools(
    private val textSearch: TextSearch,
    private val resultsListener: ResultsListener? = null,
) : SearchTools {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    @LlmTool(
        description = """
        Perform BMI25 search. Specify topK and similarity threshold from 0-1
        Query follows Lucene syntax, e.g. +term for required terms, -term for negative terms,
        "quoted phrases", wildcards (*), fuzzy (~).
    """
    )
    fun textSearch(
        @LlmTool.Param(
            description = """"
            Query in Lucene syntax,
            e.g. +term for required terms, -term for negative terms,
            quoted phrases", wildcards (*), fuzzy (~).
        """
        )
        query: String,
        topK: Int,
        @LlmTool.Param(description = "similarity threshold from 0-1") threshold: ZeroToOne,
    ): String {
        logger.info("Performing text search with query='{}', topK={}, threshold={}", query, topK, threshold)
        val start = Instant.now()
        val results = textSearch.textSearch(
            SimpleSearchRequest(query, threshold, topK),
            Chunk::class.java
        )
        val runningTime = Duration.between(start, Instant.now())
        resultsListener?.onResultsEvent(ResultsEvent(this, query, results, runningTime))
        return SimpleRetrievableResultsFormatter.formatResults(SimilarityResults.fromList(results))
    }
}

class RegexSearchTools(
    private val textSearch: RegexSearchOperations,
    private val resultsListener: ResultsListener? = null,
) : SearchTools {

    @LlmTool(description = "Perform regex search across content elements. Specify topK")
    fun regexSearch(
        regex: String,
        topK: Int,
    ): String {
        loggerFor<RegexSearchTools>().info("Performing regex search with regex='{}', topK={}", regex, topK)
        val start = Instant.now()
        val results = textSearch.regexSearch(Regex(regex), topK, Chunk::class.java)
        val runningTime = Duration.between(start, Instant.now())
        resultsListener?.onResultsEvent(ResultsEvent(this, regex, results, runningTime))
        return SimpleRetrievableResultsFormatter.formatResults(SimilarityResults.fromList(results))
    }
}

/**
 * Simple implementation of TextSimilaritySearchRequest for use in ToolishRag tools.
 */
private data class SimpleSearchRequest(
    override val query: String,
    override val similarityThreshold: ZeroToOne,
    override val topK: Int,
) : TextSimilaritySearchRequest
