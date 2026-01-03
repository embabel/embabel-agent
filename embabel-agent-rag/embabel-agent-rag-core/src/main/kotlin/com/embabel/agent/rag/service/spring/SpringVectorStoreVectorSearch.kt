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
package com.embabel.agent.rag.service.spring

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.VectorSearch
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.trim
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore

/**
 * Embabel VectorSearch wrapping a Spring AI VectorStore.
 */
class SpringVectorStoreVectorSearch(
    private val vectorStore: VectorStore,
) : VectorSearch {

    override fun supportedRetrievableTypes(): Set<Class<out Retrievable>> {
        return setOf(Chunk::class.java)
    }

    override fun <T : Retrievable> vectorSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        val searchRequest = SearchRequest
            .builder()
            .query(request.query)
            .similarityThreshold(request.similarityThreshold)
            .topK(request.topK)
            .build()
        val results: List<Document> = vectorStore.similaritySearch(searchRequest)!!
        return results.map {
            DocumentSimilarityResult(
                document = it,
                score = it.score!!,
            )
        } as List<SimilarityResult<T>>
    }
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
