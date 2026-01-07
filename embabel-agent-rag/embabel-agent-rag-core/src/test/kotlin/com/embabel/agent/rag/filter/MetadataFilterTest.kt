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

import com.embabel.agent.rag.model.Chunk
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MetadataFilterTest {

    @Nested
    inner class InMemoryMatchingTests {

        @Test
        fun `Eq matches when key equals value`() {
            val filter = MetadataFilter.Eq("owner", "alice")
            val metadata = mapOf("owner" to "alice", "type" to "document")

            assertTrue(InMemoryMetadataFilter.matches(filter, metadata))
        }

        @Test
        fun `Eq does not match when key has different value`() {
            val filter = MetadataFilter.Eq("owner", "alice")
            val metadata = mapOf("owner" to "bob", "type" to "document")

            assertFalse(InMemoryMetadataFilter.matches(filter, metadata))
        }

        @Test
        fun `Eq does not match when key is missing`() {
            val filter = MetadataFilter.Eq("owner", "alice")
            val metadata = mapOf("type" to "document")

            assertFalse(InMemoryMetadataFilter.matches(filter, metadata))
        }

        @Test
        fun `Ne matches when key has different value`() {
            val filter = MetadataFilter.Ne("owner", "alice")
            val metadata = mapOf("owner" to "bob")

            assertTrue(InMemoryMetadataFilter.matches(filter, metadata))
        }

        @Test
        fun `Ne does not match when key equals value`() {
            val filter = MetadataFilter.Ne("owner", "alice")
            val metadata = mapOf("owner" to "alice")

            assertFalse(InMemoryMetadataFilter.matches(filter, metadata))
        }

        @Test
        fun `Gt matches when value is greater`() {
            val filter = MetadataFilter.Gt("score", 0.5)
            val metadata = mapOf("score" to 0.8)

            assertTrue(InMemoryMetadataFilter.matches(filter, metadata))
        }

        @Test
        fun `Gt does not match when value equals`() {
            val filter = MetadataFilter.Gt("score", 0.5)
            val metadata = mapOf("score" to 0.5)

            assertFalse(InMemoryMetadataFilter.matches(filter, metadata))
        }

        @Test
        fun `Gt does not match when value is less`() {
            val filter = MetadataFilter.Gt("score", 0.5)
            val metadata = mapOf("score" to 0.3)

            assertFalse(InMemoryMetadataFilter.matches(filter, metadata))
        }

        @Test
        fun `Gte matches when value is greater or equal`() {
            val filter = MetadataFilter.Gte("score", 0.5)

            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("score" to 0.8)))
            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("score" to 0.5)))
            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("score" to 0.3)))
        }

        @Test
        fun `Lt matches when value is less`() {
            val filter = MetadataFilter.Lt("score", 0.5)

            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("score" to 0.3)))
            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("score" to 0.5)))
            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("score" to 0.8)))
        }

        @Test
        fun `Lte matches when value is less or equal`() {
            val filter = MetadataFilter.Lte("score", 0.5)

            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("score" to 0.3)))
            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("score" to 0.5)))
            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("score" to 0.8)))
        }

        @Test
        fun `numeric comparison works with string values`() {
            val filter = MetadataFilter.Gt("score", 0.5)
            val metadata = mapOf("score" to "0.8")

            assertTrue(InMemoryMetadataFilter.matches(filter, metadata))
        }

        @Test
        fun `numeric comparison works with integer values`() {
            val filter = MetadataFilter.Gte("count", 10)
            val metadata = mapOf("count" to 15)

            assertTrue(InMemoryMetadataFilter.matches(filter, metadata))
        }

        @Test
        fun `In matches when value is in list`() {
            val filter = MetadataFilter.In("status", listOf("active", "pending"))

            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("status" to "active")))
            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("status" to "pending")))
            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("status" to "inactive")))
        }

        @Test
        fun `Nin matches when value is not in list`() {
            val filter = MetadataFilter.Nin("status", listOf("deleted", "archived"))

            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("status" to "active")))
            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("status" to "deleted")))
            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("status" to "archived")))
        }

        @Test
        fun `Contains matches when string contains substring`() {
            val filter = MetadataFilter.Contains("description", "machine learning")

            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("description" to "intro to machine learning")))
            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("description" to "machine learning basics")))
            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("description" to "deep learning")))
        }

        @Test
        fun `And matches when all filters match`() {
            val filter = MetadataFilter.And(
                MetadataFilter.Eq("owner", "alice"),
                MetadataFilter.Eq("status", "active")
            )
            val metadata = mapOf("owner" to "alice", "status" to "active")

            assertTrue(InMemoryMetadataFilter.matches(filter, metadata))
        }

        @Test
        fun `And does not match when any filter fails`() {
            val filter = MetadataFilter.And(
                MetadataFilter.Eq("owner", "alice"),
                MetadataFilter.Eq("status", "active")
            )

            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("owner" to "bob", "status" to "active")))
            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("owner" to "alice", "status" to "inactive")))
        }

        @Test
        fun `Or matches when any filter matches`() {
            val filter = MetadataFilter.Or(
                MetadataFilter.Eq("owner", "alice"),
                MetadataFilter.Eq("owner", "bob")
            )

            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("owner" to "alice")))
            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("owner" to "bob")))
            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("owner" to "charlie")))
        }

        @Test
        fun `Not inverts filter result`() {
            val filter = MetadataFilter.Not(MetadataFilter.Eq("owner", "alice"))

            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("owner" to "alice")))
            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("owner" to "bob")))
        }

        @Test
        fun `complex nested filter works correctly`() {
            // (owner == "alice" AND status == "active") OR role == "admin"
            val filter = MetadataFilter.Or(
                MetadataFilter.And(
                    MetadataFilter.Eq("owner", "alice"),
                    MetadataFilter.Eq("status", "active")
                ),
                MetadataFilter.Eq("role", "admin")
            )

            // alice with active status - matches first branch
            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("owner" to "alice", "status" to "active")))

            // admin role - matches second branch
            assertTrue(InMemoryMetadataFilter.matches(filter, mapOf("owner" to "bob", "role" to "admin")))

            // alice but inactive - fails both branches
            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("owner" to "alice", "status" to "inactive")))

            // bob, not admin - fails both branches
            assertFalse(InMemoryMetadataFilter.matches(filter, mapOf("owner" to "bob", "status" to "active")))
        }
    }

    @Nested
    inner class FilterResultsTests {

        @Test
        fun `filterResults returns all results when filter is null`() {
            val results = listOf(
                createResult("1", mapOf("owner" to "alice")),
                createResult("2", mapOf("owner" to "bob"))
            )

            val filtered = InMemoryMetadataFilter.filterResults(results, null)

            assertEquals(2, filtered.size)
        }

        @Test
        fun `filterResults filters results based on metadata`() {
            val results = listOf(
                createResult("1", mapOf("owner" to "alice")),
                createResult("2", mapOf("owner" to "bob")),
                createResult("3", mapOf("owner" to "alice"))
            )
            val filter = MetadataFilter.Eq("owner", "alice")

            val filtered = InMemoryMetadataFilter.filterResults(results, filter)

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
            val filter = MetadataFilter.Eq("owner", "alice")

            val filtered = InMemoryMetadataFilter.filterResults(results, filter)

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
        fun `matchesFilter returns true when filter is null`() {
            val chunk = Chunk(id = "1", text = "test", parentId = "parent", metadata = mapOf("owner" to "alice"))

            assertTrue(chunk.matchesFilter(null))
        }

        @Test
        fun `matchesFilter returns true when chunk matches filter`() {
            val chunk = Chunk(id = "1", text = "test", parentId = "parent", metadata = mapOf("owner" to "alice"))
            val filter = MetadataFilter.Eq("owner", "alice")

            assertTrue(chunk.matchesFilter(filter))
        }

        @Test
        fun `matchesFilter returns false when chunk does not match filter`() {
            val chunk = Chunk(id = "1", text = "test", parentId = "parent", metadata = mapOf("owner" to "bob"))
            val filter = MetadataFilter.Eq("owner", "alice")

            assertFalse(chunk.matchesFilter(filter))
        }
    }

    @Nested
    inner class CompanionDslTests {

        @Test
        fun `DSL methods create correct filter types`() {
            assertEquals(MetadataFilter.Eq("k", "v"), MetadataFilter.eq("k", "v"))
            assertEquals(MetadataFilter.Ne("k", "v"), MetadataFilter.ne("k", "v"))
            assertEquals(MetadataFilter.Gt("k", 1), MetadataFilter.gt("k", 1))
            assertEquals(MetadataFilter.Gte("k", 1), MetadataFilter.gte("k", 1))
            assertEquals(MetadataFilter.Lt("k", 1), MetadataFilter.lt("k", 1))
            assertEquals(MetadataFilter.Lte("k", 1), MetadataFilter.lte("k", 1))
            assertEquals(MetadataFilter.In("k", listOf("a", "b")), MetadataFilter.`in`("k", "a", "b"))
            assertEquals(MetadataFilter.Nin("k", listOf("a", "b")), MetadataFilter.nin("k", "a", "b"))
            assertEquals(MetadataFilter.Contains("k", "v"), MetadataFilter.contains("k", "v"))
        }

        @Test
        fun `DSL and-or-not methods work correctly`() {
            val f1 = MetadataFilter.Eq("a", "1")
            val f2 = MetadataFilter.Eq("b", "2")

            assertEquals(MetadataFilter.And(listOf(f1, f2)), MetadataFilter.and(f1, f2))
            assertEquals(MetadataFilter.Or(listOf(f1, f2)), MetadataFilter.or(f1, f2))
            assertEquals(MetadataFilter.Not(f1), MetadataFilter.not(f1))
        }
    }
}
