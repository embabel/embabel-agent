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

import com.embabel.agent.filter.ObjectFilter
import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.filter.InMemoryPropertyFilter
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
        metadataFilter: PropertyFilter?,
        entityFilter: EntityFilter?,
    ): List<SimilarityResult<T>> {
        // Apply metadata filter natively via Spring AI
        val results = executeSearch<T>(request, metadataFilter?.toSpringAiExpression())
        // Apply property filter in-memory if specified
        return if (entityFilter != null) {
            InMemoryPropertyFilter.filterByProperties(results, entityFilter)
        } else {
            results
        }
    }

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
 * Translate [PropertyFilter] to Spring AI [Filter.Expression].
 */
fun PropertyFilter.toSpringAiExpression(): Filter.Expression = when (this) {
    is PropertyFilter.Eq -> Filter.Expression(
        Filter.ExpressionType.EQ,
        Filter.Key(key),
        Filter.Value(value)
    )

    is PropertyFilter.Ne -> Filter.Expression(
        Filter.ExpressionType.NE,
        Filter.Key(key),
        Filter.Value(value)
    )

    is PropertyFilter.Gt -> Filter.Expression(
        Filter.ExpressionType.GT,
        Filter.Key(key),
        Filter.Value(value)
    )

    is PropertyFilter.Gte -> Filter.Expression(
        Filter.ExpressionType.GTE,
        Filter.Key(key),
        Filter.Value(value)
    )

    is PropertyFilter.Lt -> Filter.Expression(
        Filter.ExpressionType.LT,
        Filter.Key(key),
        Filter.Value(value)
    )

    is PropertyFilter.Lte -> Filter.Expression(
        Filter.ExpressionType.LTE,
        Filter.Key(key),
        Filter.Value(value)
    )

    is PropertyFilter.In -> Filter.Expression(
        Filter.ExpressionType.IN,
        Filter.Key(key),
        Filter.Value(values)
    )

    is PropertyFilter.Nin -> Filter.Expression(
        Filter.ExpressionType.NIN,
        Filter.Key(key),
        Filter.Value(values)
    )

    is PropertyFilter.Contains -> Filter.Expression(
        Filter.ExpressionType.EQ,  // Spring AI doesn't have CONTAINS, fallback to EQ
        Filter.Key(key),
        Filter.Value(value)
    )

    // String filters not natively supported by Spring AI - require in-memory filtering
    is PropertyFilter.ContainsIgnoreCase -> throw UnsupportedOperationException(
        "ContainsIgnoreCase filter cannot be translated to Spring AI filter expression. " +
                "Use in-memory filtering instead."
    )

    is PropertyFilter.EqIgnoreCase -> throw UnsupportedOperationException(
        "EqIgnoreCase filter cannot be translated to Spring AI filter expression. " +
                "Use in-memory filtering instead."
    )

    is PropertyFilter.StartsWith -> throw UnsupportedOperationException(
        "StartsWith filter cannot be translated to Spring AI filter expression. " +
                "Use in-memory filtering instead."
    )

    is PropertyFilter.EndsWith -> throw UnsupportedOperationException(
        "EndsWith filter cannot be translated to Spring AI filter expression. " +
                "Use in-memory filtering instead."
    )

    is PropertyFilter.Like -> throw UnsupportedOperationException(
        "Like filter cannot be translated to Spring AI filter expression. " +
                "Use in-memory filtering instead."
    )

    is PropertyFilter.And -> filters
        .map { it.toSpringAiExpression() }
        .reduce { left, right ->
            Filter.Expression(Filter.ExpressionType.AND, left, right)
        }

    is PropertyFilter.Or -> filters
        .map { it.toSpringAiExpression() }
        .reduce { left, right ->
            Filter.Expression(Filter.ExpressionType.OR, left, right)
        }

    is PropertyFilter.Not -> Filter.Expression(
        Filter.ExpressionType.NOT,
        filter.toSpringAiExpression(),
        null
    )

    is ObjectFilter -> throw UnsupportedOperationException(
        "ObjectFilter subtypes cannot be translated to Spring AI filter expressions."
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
