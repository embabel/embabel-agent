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
import com.embabel.agent.rag.EntityData
import com.embabel.agent.rag.RagRequest
import com.embabel.agent.rag.Retrievable
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import com.embabel.common.core.types.SimilarityResult

data class RagFacetResults<out R : Retrievable>(
    val facetName: String,
    val results: List<SimilarityResult<out R>>,
)

interface RagFacet<R : Retrievable> : Named {

    fun search(ragRequest: RagRequest): RagFacetResults<R>
}

class FunctionRagFacet<R : Retrievable>(
    override val name: String,
    private val searchFunction: (RagRequest) -> RagFacetResults<R>,
) : RagFacet<R> {

    override fun search(ragRequest: RagRequest): RagFacetResults<R> = searchFunction(ragRequest)
}

interface RagFacetProvider {

    fun facets(): List<RagFacet<out Retrievable>>
}

/**
 * Degenerate case of traditional vector RAG, where we don't really understand the Chunks
 */
interface ChunkFinder : RagFacet<Chunk>

/**
 * Match over an entity of type E. May be persisted in JPA or the like.
 */
interface EntityMatch<E : Any> : EntityData, Described {

    /**
     * Underlying entity
     */
    val entity: E

}

interface EntityFinder : RagFacet<EntityMatch<Any>>
