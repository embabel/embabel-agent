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

import com.embabel.agent.rag.RagRequest
import com.embabel.agent.rag.RagResponse
import com.embabel.agent.rag.RagService
import com.embabel.agent.rag.Retrievable
import org.slf4j.LoggerFactory

/**
 * Rag service that combines multiple RagFacets and returns the best results
 */
class FacetedRagService(
    facets: List<RagFacet<out Retrievable>>,
    facetProviders: List<RagFacetProvider>,
) : RagService {

    private val logger = LoggerFactory.getLogger(FacetedRagService::class.java)

    val ragFacets = facets.toList() + facetProviders.flatMap { it.facets() }

    init {
        logger.info("Discovered {} RagFacets:", ragFacets.size)
    }

    override val name: String
        get() = "facets: " + ragFacets.joinToString(" & ") { it.name }

    override fun search(ragRequest: RagRequest): RagResponse {
        // TODO could parallelize
        val allResults = ragFacets.flatMap { facet ->
            facet.search(ragRequest).results
        }
        val ragResponse = RagResponse(
            request = ragRequest,
            service = name,
            results = allResults.distinctBy { it.match.id },
        )
        logger.debug("RagResponse: {}", ragResponse)
        return ragResponse
    }

    override val description: String
        get() = name

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        if (ragFacets.isEmpty()) "No RagFacets" else
            "Composite of $description"

}
