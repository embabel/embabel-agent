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
package com.embabel.agent.rag.service

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentElement
import com.embabel.common.core.types.SimilarityCutoff
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.Timestamped
import com.embabel.common.core.types.ZeroToOne
import org.jetbrains.annotations.ApiStatus
import java.time.Instant

interface RetrievalFilters<T : RetrievalFilters<T>> : SimilarityCutoff {

    @get:ApiStatus.Experimental
    val entitySearch: EntitySearch?

    val contentElementSearch: ContentElementSearch

    fun withSimilarityThreshold(similarityThreshold: ZeroToOne): T

    fun withTopK(topK: Int): T

    fun withEntitySearch(entitySearch: EntitySearch): T

    fun withContentElementSearch(contentElementSearch: ContentElementSearch): T

}

/**
 * Narrowing of RagRequest
 */
interface RagRequestRefinement<T : RagRequestRefinement<T>> : RetrievalFilters<T> {

    /**
     * Hints to guide retrieval.
     * Allows RAG to be extensible.
     * Not all implementations will support all hints.
     */
    val hints: List<RagHint>

    fun <T : RagHint> hintOfType(hintClass: Class<T>): T? {
        return hints.filterIsInstance(hintClass).firstOrNull()
    }

    /**
     * Create a RagRequest from this refinement and a query.
     */
    fun toRequest(query: String): RagRequest {
        return RagRequest(
            query = query,
            similarityThreshold = similarityThreshold,
            topK = topK,
            hints = hints,
            contentElementSearch = contentElementSearch,
            entitySearch = entitySearch,
        )
    }

    // Java-friendly builder

    fun withHint(hint: RagHint): T

}

inline fun <reified T : RagHint> RagRequestRefinement<*>.hintOfType(): T? {
    return hintOfType(T::class.java)
}

data class ContentElementSearch(
    val types: List<Class<out ContentElement>>,
) {
    companion object {

        @JvmField
        val NONE = ContentElementSearch(emptyList())

        @JvmField
        val CHUNKS_ONLY: ContentElementSearch = ContentElementSearch(
            types = listOf(Chunk::class.java),
        )
    }
}


/**
 * Controls entity search
 * Open to allow specializations
 *
 */
open class EntitySearch(
    val labels: Set<String>,
    val generateQueries: Boolean = false,
)

open class TypedEntitySearch(
    val entities: List<Class<*>>,
    generateQueries: Boolean = false,
) : EntitySearch(
    labels = entities.map { it.simpleName }.toSet(),
    generateQueries = generateQueries,
) {

    constructor (vararg entities: Class<*>) : this(entities.toList())
}


/**
 * RAG request.
 * Contains a query and parameters for similarity search.
 * @param query the query string to search for
 * @param similarityThreshold the minimum similarity score for results (default is 0.8)
 * @param topK the maximum number of results to return (default is 8)
 * @param hints hints to guide retrieval. Allows RAG to be extensible.
 * Not all implementations will support all hints.
 * @param contentElementSearch controls which content elements will be
 * If set, only the given entities will be searched for.
 */
data class RagRequest(
    override val query: String,
    override val similarityThreshold: ZeroToOne = .8,
    override val topK: Int = 8,
    override val hints: List<RagHint> = emptyList(),
    override val contentElementSearch: ContentElementSearch = ContentElementSearch.CHUNKS_ONLY,
    override val entitySearch: EntitySearch? = null,
    override val timestamp: Instant = Instant.now(),
) : TextSimilaritySearchRequest, RagRequestRefinement<RagRequest>, Timestamped {

    override fun withSimilarityThreshold(similarityThreshold: ZeroToOne): RagRequest {
        return this.copy(similarityThreshold = similarityThreshold)
    }

    override fun withTopK(topK: Int): RagRequest {
        return this.copy(topK = topK)
    }

    override fun withHint(hint: RagHint): RagRequest {
        return this.copy(hints = hints + hint)
    }

    @ApiStatus.Experimental
    override fun withEntitySearch(entitySearch: EntitySearch): RagRequest {
        return this.copy(entitySearch = entitySearch)
    }

    override fun withContentElementSearch(contentElementSearch: ContentElementSearch): RagRequest {
        return this.copy(contentElementSearch = contentElementSearch)
    }

    companion object {

        @JvmStatic
        fun query(
            query: String,
        ): RagRequest = RagRequest(query = query)
    }
}
