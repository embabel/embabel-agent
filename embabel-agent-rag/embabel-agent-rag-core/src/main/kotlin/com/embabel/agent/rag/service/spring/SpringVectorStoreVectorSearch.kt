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
package com.embabel.agent.rag.service.spring

import com.embabel.agent.rag.filter.MetadataFilter
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.FilteringVectorSearch
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.trim
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.Filter

/**
 * Embabel VectorSearch wrapping a Spring AI VectorStore.
 * Implements [FilteringVectorSearch] for native metadata filtering support.
 */
class SpringVectorStoreVectorSearch(
    private val vectorStore: VectorStore,
) : FilteringVectorSearch {

    override fun supportsType(type: String): Boolean =
        type.equals("Chunk", ignoreCase = true)

    override fun <T : Retrievable> vectorSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> = executeSearch(request, filterExpression = null)

    override fun <T : Retrievable> vectorSearchWithFilter(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
        filter: MetadataFilter,
    ): List<SimilarityResult<T>> = executeSearch(request, filter.toSpringAiExpression())

    @Suppress("UNCHECKED_CAST")
    private fun <T : Retrievable> executeSearch(
        request: TextSimilaritySearchRequest,
        filterExpression: Filter.Expression?,
    ): List<SimilarityResult<T>> {
        val searchRequestBuilder = SearchRequest
            .builder()
            .query(request.query)
            .similarityThreshold(request.similarityThreshold)
            .topK(request.topK)

        filterExpression?.let { searchRequestBuilder.filterExpression(it) }

        val results: List<Document> = vectorStore.similaritySearch(searchRequestBuilder.build())!!
        return results.map {
            DocumentSimilarityResult(
                document = it,
                score = it.score!!,
            )
        } as List<SimilarityResult<T>>
    }
}

/**
 * Translate [MetadataFilter] to Spring AI [Filter.Expression].
 */
fun MetadataFilter.toSpringAiExpression(): Filter.Expression = when (this) {
    is MetadataFilter.Eq -> Filter.Expression(
        Filter.ExpressionType.EQ,
        Filter.Key(key),
        Filter.Value(value)
    )
    is MetadataFilter.Ne -> Filter.Expression(
        Filter.ExpressionType.NE,
        Filter.Key(key),
        Filter.Value(value)
    )
    is MetadataFilter.Gt -> Filter.Expression(
        Filter.ExpressionType.GT,
        Filter.Key(key),
        Filter.Value(value)
    )
    is MetadataFilter.Gte -> Filter.Expression(
        Filter.ExpressionType.GTE,
        Filter.Key(key),
        Filter.Value(value)
    )
    is MetadataFilter.Lt -> Filter.Expression(
        Filter.ExpressionType.LT,
        Filter.Key(key),
        Filter.Value(value)
    )
    is MetadataFilter.Lte -> Filter.Expression(
        Filter.ExpressionType.LTE,
        Filter.Key(key),
        Filter.Value(value)
    )
    is MetadataFilter.In -> Filter.Expression(
        Filter.ExpressionType.IN,
        Filter.Key(key),
        Filter.Value(values)
    )
    is MetadataFilter.Nin -> Filter.Expression(
        Filter.ExpressionType.NIN,
        Filter.Key(key),
        Filter.Value(values)
    )
    is MetadataFilter.Contains -> Filter.Expression(
        Filter.ExpressionType.EQ,  // Spring AI doesn't have CONTAINS, fallback to EQ
        Filter.Key(key),
        Filter.Value(value)
    )
    is MetadataFilter.And -> filters
        .map { it.toSpringAiExpression() }
        .reduce { left, right ->
            Filter.Expression(Filter.ExpressionType.AND, left, right)
        }
    is MetadataFilter.Or -> filters
        .map { it.toSpringAiExpression() }
        .reduce { left, right ->
            Filter.Expression(Filter.ExpressionType.OR, left, right)
        }
    is MetadataFilter.Not -> Filter.Expression(
        Filter.ExpressionType.NOT,
        filter.toSpringAiExpression(),
        null
    )
}

internal class DocumentSimilarityResult(
    private val document: Document,
    override val score: ZeroToOne,
) : SimilarityResult<Chunk> {

    override val match: Chunk = Chunk(
        id = document.id,
        text = document.text!!,
        metadata = document.metadata,
        parentId = document.id,
    )

    override fun toString(): String {
        return "${javaClass.simpleName}(id=${document.id}, score=$score, text=${
            trim(
                s = document.text,
                max = 120,
                keepRight = 5
            )
        })"
    }
}
