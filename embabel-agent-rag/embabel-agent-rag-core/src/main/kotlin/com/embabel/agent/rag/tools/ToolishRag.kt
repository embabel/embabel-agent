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

import com.embabel.agent.api.common.LlmReference
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentElement
import com.embabel.agent.rag.model.Embeddable
import com.embabel.agent.rag.service.*
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.loggerFor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/**
 * Reference for fine-grained RAG tools, allowing the LLM to
 * control individual search operations directly.
 */
class ToolishRag @JvmOverloads constructor(
    override val name: String,
    override val description: String,
    private val searchOperations: SearchOperations,
    val goal: String = DEFAULT_GOAL,
    val formatter: RetrievableResultsFormatter = SimpleRetrievableResultsFormatter,
) : LlmReference {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val toolInstances: List<Any> = run {
        buildList {
            if (searchOperations is VectorSearch) {
                logger.info("Adding VectorSearchTools to ToolishRag tools {}", name)
                add(VectorSearchTools(searchOperations))
            }
            if (searchOperations is TextSearch) {
                logger.info("Adding TextSearchTools to ToolishRag tools {}", name)
                add(TextSearchTools(searchOperations))
            }
            if (searchOperations is ResultExpander) {
                logger.info("Adding ResultExpanderTools to ToolishRag tools {}", name)
                add(ResultExpanderTools(searchOperations))
            }
            if (searchOperations is RegexSearchOperations) {
                logger.info("Adding RegexSearchTools to ToolishRag tools {}", name)
                add(RegexSearchTools(searchOperations))
            }
        }
    }

    override fun toolInstances() = toolInstances

    override fun notes() = """
        ${
        (searchOperations as? TextSearch)?.let {
            "Lucene search syntax support: ${searchOperations.luceneSyntaxNotes}\n"
        }
    }
        Search acceptance criteria:
        $goal
      """.trimIndent()

    companion object {
        val DEFAULT_GOAL = """
            Continue search until the question is answered, or you have to give up.
            Be creative, try different types of queries. Generate HyDE queries if needed.
            Be thorough and try different approaches.
            If nothing works, report that you could not find the answer.
        """.trimIndent()
    }
}

/**
 * Classic vector search
 */
class VectorSearchTools(
    private val vectorSearch: VectorSearch,
) {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    @Tool(description = "Perform vector search. Specify topK and similarity threshold from 0-1")
    fun vectorSearch(
        query: String,
        topK: Int,
        @ToolParam(description = "similarity threshold from 0-1") threshold: ZeroToOne,
    ): String {
        logger.info("Performing vector search with query='{}', topK={}, threshold={}", query, topK, threshold)
        val results = vectorSearch.vectorSearch(
            SimpleSearchRequest(query, threshold, topK),
            Chunk::class.java
        )
        return SimpleRetrievableResultsFormatter.formatResults(SimilarityResults.fromList(results))
    }
}

/**
 * Tools to expand chunks around an anchor chunk that has already been retrieved
 */
class ResultExpanderTools(
    private val resultExpander: ResultExpander,
) {

    @Tool(description = "given a chunk ID, expand to surrounding chunks")
    fun broadenChunk(
        @ToolParam(description = "id of the chunk to expand") chunkId: String,
        @ToolParam(description = "chunksToAdd", required = false) chunksToAdd: Int = 2,
    ): String {
        val expandedElements = resultExpander.expandResult(chunkId, ResultExpander.Method.SEQUENCE, chunksToAdd)
        return expandedElements
            .filterIsInstance<Chunk>()
            .joinToString("\n") { chunk ->
                "Chunk ID: ${chunk.id}\nContent: ${chunk.text}\n"
            }
    }

    @Tool(description = "given a content element ID, expand to parent section")
    fun zoomOut(
        @ToolParam(description = "id of the content element to expand") id: String,
    ): String {
        val expandedElements: List<ContentElement> = resultExpander.expandResult(id, ResultExpander.Method.ZOOM_OUT, 1)
        return expandedElements
            .filter { it is Embeddable }
            .joinToString("\n") { contentElement ->
                "${contentElement.javaClass.simpleName}: id=${contentElement.id}\nContent: ${(contentElement as Embeddable).embeddableValue()}\n"
            }
    }

    // TODO related chunk expansion based on vector similarity

}

/**
 * Tools to perform text search operations with Lucene syntax
 */
class TextSearchTools(
    private val textSearch: TextSearch,
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    @Tool(
        description = """
        Perform BMI25 search. Specify topK and similarity threshold from 0-1
        Query follows Lucene syntax, e.g. +term for required terms, -term for negative terms,
        "quoted phrases", wildcards (*), fuzzy (~).
    """
    )
    fun textSearch(
        @ToolParam(
            description = """"
            Query in Lucene syntax,
            e.g. +term for required terms, -term for negative terms,
            quoted phrases", wildcards (*), fuzzy (~).
        """
        )
        query: String,
        topK: Int,
        @ToolParam(description = "similarity threshold from 0-1") threshold: ZeroToOne,
    ): String {
        logger.info("Performing text search with query='{}', topK={}, threshold={}", query, topK, threshold)
        val results = textSearch.textSearch(
            SimpleSearchRequest(query, threshold, topK),
            Chunk::class.java
        )
        return SimpleRetrievableResultsFormatter.formatResults(SimilarityResults.fromList(results))
    }
}

class RegexSearchTools(
    private val textSearch: RegexSearchOperations,
) {

    @Tool(description = "Perform regex search across content elements. Specify topK")
    fun regexSearch(
        regex: String,
        topK: Int,
    ): String {
        loggerFor<RegexSearchTools>().info("Performing regex search with regex='{}', topK={}", regex, topK)
        val results = textSearch.regexSearch(Regex(regex), topK, Chunk::class.java)
        return SimpleRetrievableResultsFormatter.formatResults(SimilarityResults.Companion.fromList(results))
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
// expand entity
