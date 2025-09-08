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
package com.embabel.agent.rag.support

import com.embabel.agent.rag.Chunk
import com.embabel.agent.rag.RagRequest
import com.embabel.agent.rag.RagResponse
import com.embabel.agent.rag.WritableRagService
import com.embabel.agent.rag.ingestion.MaterializedDocument
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.indent
import com.embabel.common.util.trim
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore

/**
 * RagService wrapping a Spring AI VectorStore.
 */
@ApiStatus.Experimental
class SpringVectorStoreRagService(
    private val vectorStore: VectorStore,
    override val description: String,
) : WritableRagService {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val name: String
        get() = vectorStore.name

    override fun search(ragRequest: RagRequest): RagResponse {
        val searchRequest = SearchRequest
            .builder()
            .query(ragRequest.query)
            .similarityThreshold(ragRequest.similarityThreshold)
            .topK(ragRequest.topK)
            .build()
        val results: List<Document> = vectorStore.similaritySearch(searchRequest)!!
        return RagResponse(
            request = ragRequest,
            service = name,
            results = results.map { it ->
                DocumentSimilarityResult(
                    document = it,
                    score = it.score!!,
                )
            }
        )
    }

    override fun accept(documents: List<Document>) {
        logger.info("Writing ${documents.size} documents into Spring vector store")
        vectorStore.accept(documents)
    }

    override fun writeContent(root: MaterializedDocument): List<String> {
        TODO("Not yet implemented")
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "${vectorStore.name}: ${vectorStore.javaClass.name}".indent(indent)
    }
}

class DocumentSimilarityResult(
    private val document: Document,
    override val score: ZeroToOne,
) : SimilarityResult<Chunk> {

    override val match: Chunk = Chunk(
        document.id, document.text!!
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
