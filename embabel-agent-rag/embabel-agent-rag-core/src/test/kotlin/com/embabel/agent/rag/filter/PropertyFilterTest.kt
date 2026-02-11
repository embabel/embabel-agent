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
package com.embabel.agent.rag.filter

import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.model.Chunk
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * RAG-specific PropertyFilter tests.
 * Generic matching, DSL, and operator tests are in embabel-agent-api PropertyFilterTest.
 */
class PropertyFilterTest {

    @Nested
    inner class FilterResultsTests {

        @Test
        fun `filterResults returns all results when filter is null`() {
            val results = listOf(
                createResult("1", mapOf("owner" to "alice")),
                createResult("2", mapOf("owner" to "bob"))
            )

            val filtered = InMemoryPropertyFilter.filterResults(results, null, null)

            assertEquals(2, filtered.size)
        }

        @Test
        fun `filterResults filters results based on metadata`() {
            val results = listOf(
                createResult("1", mapOf("owner" to "alice")),
                createResult("2", mapOf("owner" to "bob")),
                createResult("3", mapOf("owner" to "alice"))
            )
            val filter = PropertyFilter.Eq("owner", "alice")

            val filtered = InMemoryPropertyFilter.filterResults(results, filter, null)

            assertEquals(2, filtered.size)
            assertTrue(filtered.all { it.match.id == "1" || it.match.id == "3" })
        }

        @Test
        fun `filterResults preserves score ordering`() {
            val results = listOf(
                createResult("1", mapOf("owner" to "alice"), 0.9),
                createResult("2", mapOf("owner" to "alice"), 0.7),
                createResult("3", mapOf("owner" to "bob"), 0.8)
            )
            val filter = PropertyFilter.Eq("owner", "alice")

            val filtered = InMemoryPropertyFilter.filterResults(results, filter, null)

            assertEquals(2, filtered.size)
            assertEquals("1", filtered[0].match.id)
            assertEquals("2", filtered[1].match.id)
        }

        private fun createResult(id: String, metadata: Map<String, Any?>, score: Double = 0.8) =
            SimpleSimilaritySearchResult(
                match = Chunk(id = id, text = "test", parentId = "parent", metadata = metadata),
                score = score
            )
    }

    @Nested
    inner class DatumExtensionTests {

        @Test
        fun `matchesMetadataFilter returns true when filter is null`() {
            val chunk = Chunk(id = "1", text = "test", parentId = "parent", metadata = mapOf("owner" to "alice"))

            assertTrue(chunk.matchesMetadataFilter(null))
        }

        @Test
        fun `matchesMetadataFilter returns true when chunk matches filter`() {
            val chunk = Chunk(id = "1", text = "test", parentId = "parent", metadata = mapOf("owner" to "alice"))
            val filter = PropertyFilter.Eq("owner", "alice")

            assertTrue(chunk.matchesMetadataFilter(filter))
        }

        @Test
        fun `matchesMetadataFilter returns false when chunk does not match filter`() {
            val chunk = Chunk(id = "1", text = "test", parentId = "parent", metadata = mapOf("owner" to "bob"))
            val filter = PropertyFilter.Eq("owner", "alice")

            assertFalse(chunk.matchesMetadataFilter(filter))
        }
    }
}
