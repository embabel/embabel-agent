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

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.Embeddable
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.*
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.loggerFor
import com.embabel.common.util.time
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Classic vector search
 */
internal class VectorSearchTools @JvmOverloads constructor(
    private val vectorSearch: VectorSearch,
    private val searchFor: List<Class<out Retrievable>> = listOf(Chunk::class.java),
    private val metadataFilter: PropertyFilter? = null,
    private val entityFilter: EntityFilter? = null,
    private val resultsListener: ResultsListener? = null,
) : SearchTools {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    @LlmTool(description = "Perform vector search. Specify topK and similarity threshold from 0-1")
    fun vectorSearch(
        query: String,
        topK: Int,
        @LlmTool.Param(description = "similarity threshold from 0-1") threshold: ZeroToOne,
    ): String {
        logger.info(
            "Performing vector search with query='{}', topK={}, threshold={}, types={}, metadataFilter={}, entityFilter={}",
            query, topK, threshold, searchFor.map { it.simpleName }, metadataFilter, entityFilter
        )
        val request = TextSimilaritySearchRequest(query, threshold, topK)
        val (results, ms) = time {
            searchForAllTypes(request)
        }
        resultsListener?.onResultsEvent(ResultsEvent(this, query, results, Duration.ofMillis(ms)))
        return SimpleRetrievableResultsFormatter.formatResults(SimilarityResults.fromList<Retrievable>(results))
    }

    private fun searchForAllTypes(request: TextSimilaritySearchRequest): List<SimilarityResult<out Retrievable>> {
        val allResults = searchFor.flatMap { clazz ->
            searchWithFilter(request, clazz)
        }
        return deduplicateByIdKeepingHighestScore(allResults)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Retrievable> searchWithFilter(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        if (metadataFilter == null && entityFilter == null) {
            return vectorSearch.vectorSearch(request, clazz)
        }

        // If backend supports native filtering, use it
        if (vectorSearch is FilteringVectorSearch) {
            return vectorSearch.vectorSearchWithFilter(request, clazz, metadataFilter, entityFilter)
        }

        // Fallback: inflate topK, search, post-filter, take topK
        // Note: PostFilteringSearch requires Datum constraint, so we cast
        return PostFilteringSearch.search(
            request,
            metadataFilter,
            entityFilter,
            TopKInflationStrategy.DEFAULT
        ) { inflatedRequest ->
            vectorSearch.vectorSearch(inflatedRequest, clazz)
        } as List<SimilarityResult<T>>
    }
}

/**
 * Tools to expand chunks around an anchor chunk that has already been retrieved
 */
internal class ResultExpanderTools(
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
        val expandedElements = resultExpander.expandResult(id, ResultExpander.Method.ZOOM_OUT, 1)
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
internal class TextSearchTools @JvmOverloads constructor(
    private val textSearch: TextSearch,
    private val searchFor: List<Class<out Retrievable>> = listOf(Chunk::class.java),
    private val metadataFilter: PropertyFilter? = null,
    private val entityFilter: EntityFilter? = null,
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
        logger.info(
            "Performing text search with query='{}', topK={}, threshold={}, types={}, metadataFilter={}, entityFilter={}",
            query, topK, threshold, searchFor.map { it.simpleName }, metadataFilter, entityFilter
        )

        val request = TextSimilaritySearchRequest(query, threshold, topK)
        val (results, ms) = time {
            searchForAllTypes(request)
        }
        resultsListener?.onResultsEvent(ResultsEvent(this, query, results, Duration.ofMillis(ms)))
        return SimpleRetrievableResultsFormatter.formatResults(SimilarityResults.fromList<Retrievable>(results))
    }

    private fun searchForAllTypes(request: TextSimilaritySearchRequest): List<SimilarityResult<out Retrievable>> {
        val allResults = searchFor.flatMap { clazz ->
            searchWithFilter(request, clazz)
        }
        return deduplicateByIdKeepingHighestScore(allResults)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Retrievable> searchWithFilter(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        if (metadataFilter == null && entityFilter == null) {
            return textSearch.textSearch(request, clazz)
        }

        // If backend supports native filtering, use it
        if (textSearch is FilteringTextSearch) {
            return textSearch.textSearchWithFilter(request, clazz, metadataFilter, entityFilter)
        }

        // Fallback: inflate topK, search, post-filter, take topK
        return PostFilteringSearch.search(
            request,
            metadataFilter,
            entityFilter,
            TopKInflationStrategy.DEFAULT
        ) { inflatedRequest ->
            textSearch.textSearch(inflatedRequest, clazz)
        } as List<SimilarityResult<T>>
    }
}

internal class RegexSearchTools(
    private val regexSearch: RegexSearchOperations,
    private val metadataFilter: PropertyFilter? = null,
    private val entityFilter: EntityFilter? = null,
    private val resultsListener: ResultsListener? = null,
) : SearchTools {

    @LlmTool(description = "Perform regex search across content elements. Specify topK")
    fun regexSearch(
        regex: String,
        topK: Int,
    ): String {
        loggerFor<RegexSearchTools>().info(
            "Performing regex search with regex='{}', topK={}, metadataFilter={}, entityFilter={}",
            regex, topK, metadataFilter, entityFilter
        )
        val start = Instant.now()
        val results = searchWithFilter(Regex(regex), topK)
        val runningTime = Duration.between(start, Instant.now())
        resultsListener?.onResultsEvent(ResultsEvent(this, regex, results, runningTime))
        return SimpleRetrievableResultsFormatter.formatResults(SimilarityResults.fromList(results))
    }

    private fun searchWithFilter(
        regex: Regex,
        topK: Int,
    ): List<SimilarityResult<Chunk>> {
        if (metadataFilter == null && entityFilter == null) {
            return regexSearch.regexSearch(regex, topK, Chunk::class.java)
        }

        // If backend supports native filtering, use it
        if (regexSearch is FilteringRegexSearch) {
            return regexSearch.regexSearchWithFilter(regex, topK, Chunk::class.java, metadataFilter, entityFilter)
        }

        // Fallback: inflate topK, search, post-filter, take topK
        return PostFilteringSearch.regexSearch(
            topK,
            metadataFilter,
            entityFilter,
            TopKInflationStrategy.DEFAULT
        ) { inflatedTopK ->
            regexSearch.regexSearch(regex, inflatedTopK, Chunk::class.java)
        }
    }
}

/**
 * Tools to check if a type is supported by this store.
 */
internal class TypeRetrievalTools(
    private val typeRetrievalOperations: TypeRetrievalOperations,
) : SearchTools {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    @LlmTool(description = "Check if a type is supported for retrieval. Provide the simple class name.")
    fun isTypeSupported(
        @LlmTool.Param(description = "The type (usually simple class) name to check") typeName: String,
    ): String {
        logger.info("Checking if type '{}' is supported", typeName)
        return if (typeRetrievalOperations.supportsType(typeName)) {
            "Type '$typeName' is supported"
        } else {
            "Type '$typeName' is not supported by this store"
        }
    }
}

/**
 * Tools to retrieve items by ID from the store.
 */
internal class FinderTools(
    private val finderOperations: FinderOperations,
) : SearchTools {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    @LlmTool(description = "Retrieve an item by its ID. Provide the type as a simple class name.")
    fun findById(
        @LlmTool.Param(description = "The ID of the item to retrieve") id: String,
        @LlmTool.Param(description = "The type name (usually simple class name) of the item") typeName: String,
    ): String {
        logger.info("Finding retrievable by id='{}', type='{}'", id, typeName)

        if (!finderOperations.supportsType(typeName)) {
            return "Type '$typeName' is not supported by this store"
        }

        val result = finderOperations.findById<Retrievable>(id, typeName)
        return if (result != null) {
            "Found ${result.javaClass.simpleName} with id '$id': ${result.infoString(verbose = true)}"
        } else {
            "No item found with id '$id' of type '$typeName'"
        }
    }
}

/**
 * Deduplicate results by ID, keeping the result with the highest score for each unique ID.
 */
internal fun deduplicateByIdKeepingHighestScore(
    results: List<SimilarityResult<out Retrievable>>,
): List<SimilarityResult<out Retrievable>> =
    results
        .groupBy { it.match.id }
        .map { (_, group) -> group.maxBy { it.score } }
        .sortedByDescending { it.score }
