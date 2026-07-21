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
package com.embabel.agent.rag.service.support

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.service.RagRequest
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FacetedRagServiceTest {

    private fun chunkResult(id: String, score: Double) = SimpleSimilaritySearchResult(
        match = Chunk(id = id, text = "text-$id", parentId = "doc", metadata = emptyMap()),
        score = score,
    )

    private fun facet(name: String, vararg results: SimpleSimilaritySearchResult<Chunk>): RagFacet<Chunk> =
        FunctionRagFacet(name) {
            RagFacetResults(facetName = name, results = results.toList())
        }

    @Nested
    inner class EmptyFacets {

        @Test
        fun `search returns empty results`() {
            val service = FacetedRagService(
                name = "empty",
                facets = emptyList(),
                facetProviders = emptyList(),
            )

            val request = RagRequest("q")

            val response = service.search(request)

            assertEquals("empty", response.service)
            assertSame(request, response.request)
            assertTrue(response.results.isEmpty())
            assertEquals("No RagFacets", service.infoString(verbose = false))
        }
    }

    @Nested
    inner class CombiningFacets {

        @Test
        fun `merges facets and providers then keeps highest scoring duplicate`() {
            val provider = object : RagFacetProvider {
                override fun facets(): List<RagFacet<*>> = listOf(
                    facet("provider-facet", chunkResult("shared", 0.95), chunkResult("from-provider", 0.7)),
                )
            }
            val service = FacetedRagService(
                name = "combo",
                description = "combo service",
                facets = listOf(facet("direct", chunkResult("shared", 0.5), chunkResult("from-direct", 0.85))),
                facetProviders = listOf(provider),
            )

            val response = service.search(RagRequest("q", similarityThreshold = 0.0, topK = 10))

            assertEquals(listOf("shared", "from-direct", "from-provider"), response.results.map { it.match.id })
            assertEquals(0.95, response.results.first().score)
            assertEquals("RagFacets of combo service", service.infoString(verbose = true))
        }

        @Test
        fun `filters by similarity threshold and topK after merge`() {
            val service = FacetedRagService(
                name = "ranked",
                facets = listOf(
                    facet(
                        "a",
                        chunkResult("low", 0.2),
                        chunkResult("high", 0.95),
                        chunkResult("mid", 0.6),
                        chunkResult("also-high", 0.9),
                    ),
                ),
                facetProviders = emptyList(),
            )

            val response = service.search(RagRequest("q", similarityThreshold = 0.5, topK = 2))

            assertEquals(listOf("high", "also-high"), response.results.map { it.match.id })
        }

        @Test
        fun `keeps scores equal to threshold`() {
            val service = FacetedRagService(
                name = "edge",
                facets = listOf(facet("a", chunkResult("edge", 0.8))),
                facetProviders = emptyList(),
            )

            val response = service.search(RagRequest("q", similarityThreshold = 0.8, topK = 5))

            assertEquals(1, response.results.size)
        }

        @Test
        fun `forwards the same request instance to every facet and response`() {
            val receivedRequests = mutableListOf<RagRequest>()
            fun recordingFacet(name: String) = FunctionRagFacet<Chunk>(name) { request ->
                receivedRequests += request
                RagFacetResults(facetName = name, results = emptyList())
            }
            val service = FacetedRagService(
                name = "forwarding",
                facets = listOf(recordingFacet("a"), recordingFacet("b")),
                facetProviders = emptyList(),
            )
            val request = RagRequest("same request")

            val response = service.search(request)

            assertEquals(2, receivedRequests.size)
            assertTrue(receivedRequests.all { it === request })
            assertSame(request, response.request)
        }

        @Test
        fun `topK zero returns no results`() {
            val service = FacetedRagService(
                name = "zero",
                facets = listOf(facet("a", chunkResult("result", 0.9))),
                facetProviders = emptyList(),
            )

            val response = service.search(RagRequest("q", similarityThreshold = 0.0, topK = 0))

            assertTrue(response.results.isEmpty())
        }
    }
}
