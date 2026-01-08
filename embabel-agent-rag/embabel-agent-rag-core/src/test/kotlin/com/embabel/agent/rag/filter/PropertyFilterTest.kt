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

class PropertyFilterTest {

    @Nested
    inner class InMemoryMatchingTests {

        @Test
        fun `Eq matches when key equals value`() {
            val filter = PropertyFilter.Eq("owner", "alice")
            val metadata = mapOf("owner" to "alice", "type" to "document")

            assertTrue(InMemoryPropertyFilter.matches(filter, metadata))
        }

        @Test
        fun `Eq does not match when key has different value`() {
            val filter = PropertyFilter.Eq("owner", "alice")
            val metadata = mapOf("owner" to "bob", "type" to "document")

            assertFalse(InMemoryPropertyFilter.matches(filter, metadata))
        }

        @Test
        fun `Eq does not match when key is missing`() {
            val filter = PropertyFilter.Eq("owner", "alice")
            val metadata = mapOf("type" to "document")

            assertFalse(InMemoryPropertyFilter.matches(filter, metadata))
        }

        @Test
        fun `Ne matches when key has different value`() {
            val filter = PropertyFilter.Ne("owner", "alice")
            val metadata = mapOf("owner" to "bob")

            assertTrue(InMemoryPropertyFilter.matches(filter, metadata))
        }

        @Test
        fun `Ne does not match when key equals value`() {
            val filter = PropertyFilter.Ne("owner", "alice")
            val metadata = mapOf("owner" to "alice")

            assertFalse(InMemoryPropertyFilter.matches(filter, metadata))
        }

        @Test
        fun `Gt matches when value is greater`() {
            val filter = PropertyFilter.Gt("score", 0.5)
            val metadata = mapOf("score" to 0.8)

            assertTrue(InMemoryPropertyFilter.matches(filter, metadata))
        }

        @Test
        fun `Gt does not match when value equals`() {
            val filter = PropertyFilter.Gt("score", 0.5)
            val metadata = mapOf("score" to 0.5)

            assertFalse(InMemoryPropertyFilter.matches(filter, metadata))
        }

        @Test
        fun `Gt does not match when value is less`() {
            val filter = PropertyFilter.Gt("score", 0.5)
            val metadata = mapOf("score" to 0.3)

            assertFalse(InMemoryPropertyFilter.matches(filter, metadata))
        }

        @Test
        fun `Gte matches when value is greater or equal`() {
            val filter = PropertyFilter.Gte("score", 0.5)

            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("score" to 0.8)))
            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("score" to 0.5)))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("score" to 0.3)))
        }

        @Test
        fun `Lt matches when value is less`() {
            val filter = PropertyFilter.Lt("score", 0.5)

            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("score" to 0.3)))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("score" to 0.5)))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("score" to 0.8)))
        }

        @Test
        fun `Lte matches when value is less or equal`() {
            val filter = PropertyFilter.Lte("score", 0.5)

            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("score" to 0.3)))
            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("score" to 0.5)))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("score" to 0.8)))
        }

        @Test
        fun `numeric comparison works with string values`() {
            val filter = PropertyFilter.Gt("score", 0.5)
            val metadata = mapOf("score" to "0.8")

            assertTrue(InMemoryPropertyFilter.matches(filter, metadata))
        }

        @Test
        fun `numeric comparison works with integer values`() {
            val filter = PropertyFilter.Gte("count", 10)
            val metadata = mapOf("count" to 15)

            assertTrue(InMemoryPropertyFilter.matches(filter, metadata))
        }

        @Test
        fun `In matches when value is in list`() {
            val filter = PropertyFilter.In("status", listOf("active", "pending"))

            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("status" to "active")))
            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("status" to "pending")))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("status" to "inactive")))
        }

        @Test
        fun `Nin matches when value is not in list`() {
            val filter = PropertyFilter.Nin("status", listOf("deleted", "archived"))

            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("status" to "active")))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("status" to "deleted")))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("status" to "archived")))
        }

        @Test
        fun `Contains matches when string contains substring`() {
            val filter = PropertyFilter.Contains("description", "machine learning")

            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("description" to "intro to machine learning")))
            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("description" to "machine learning basics")))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("description" to "deep learning")))
        }

        @Test
        fun `And matches when all filters match`() {
            val filter = PropertyFilter.And(
                PropertyFilter.Eq("owner", "alice"),
                PropertyFilter.Eq("status", "active")
            )
            val metadata = mapOf("owner" to "alice", "status" to "active")

            assertTrue(InMemoryPropertyFilter.matches(filter, metadata))
        }

        @Test
        fun `And does not match when any filter fails`() {
            val filter = PropertyFilter.And(
                PropertyFilter.Eq("owner", "alice"),
                PropertyFilter.Eq("status", "active")
            )

            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "bob", "status" to "active")))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice", "status" to "inactive")))
        }

        @Test
        fun `Or matches when any filter matches`() {
            val filter = PropertyFilter.Or(
                PropertyFilter.Eq("owner", "alice"),
                PropertyFilter.Eq("owner", "bob")
            )

            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice")))
            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "bob")))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "charlie")))
        }

        @Test
        fun `Not inverts filter result`() {
            val filter = PropertyFilter.Not(PropertyFilter.Eq("owner", "alice"))

            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice")))
            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "bob")))
        }

        @Test
        fun `complex nested filter works correctly`() {
            // (owner == "alice" AND status == "active") OR role == "admin"
            val filter = PropertyFilter.Or(
                PropertyFilter.And(
                    PropertyFilter.Eq("owner", "alice"),
                    PropertyFilter.Eq("status", "active")
                ),
                PropertyFilter.Eq("role", "admin")
            )

            // alice with active status - matches first branch
            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice", "status" to "active")))

            // admin role - matches second branch
            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "bob", "role" to "admin")))

            // alice but inactive - fails both branches
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice", "status" to "inactive")))

            // bob, not admin - fails both branches
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "bob", "status" to "active")))
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

    @Nested
    inner class CompanionDslTests {

        @Test
        fun `DSL methods create correct filter types`() {
            assertEquals(PropertyFilter.Eq("k", "v"), PropertyFilter.eq("k", "v"))
            assertEquals(PropertyFilter.Ne("k", "v"), PropertyFilter.ne("k", "v"))
            assertEquals(PropertyFilter.Gt("k", 1), PropertyFilter.gt("k", 1))
            assertEquals(PropertyFilter.Gte("k", 1), PropertyFilter.gte("k", 1))
            assertEquals(PropertyFilter.Lt("k", 1), PropertyFilter.lt("k", 1))
            assertEquals(PropertyFilter.Lte("k", 1), PropertyFilter.lte("k", 1))
            assertEquals(PropertyFilter.In("k", listOf("a", "b")), PropertyFilter.`in`("k", "a", "b"))
            assertEquals(PropertyFilter.Nin("k", listOf("a", "b")), PropertyFilter.nin("k", "a", "b"))
            assertEquals(PropertyFilter.Contains("k", "v"), PropertyFilter.contains("k", "v"))
        }

        @Test
        fun `DSL and-or-not methods work correctly`() {
            val f1 = PropertyFilter.Eq("a", "1")
            val f2 = PropertyFilter.Eq("b", "2")

            assertEquals(PropertyFilter.And(listOf(f1, f2)), PropertyFilter.and(f1, f2))
            assertEquals(PropertyFilter.Or(listOf(f1, f2)), PropertyFilter.or(f1, f2))
            assertEquals(PropertyFilter.Not(f1), PropertyFilter.not(f1))
        }
    }

    @Nested
    inner class KotlinOperatorTests {

        @Test
        fun `not operator creates Not filter`() {
            val filter = PropertyFilter.eq("owner", "alice")
            val notFilter = !filter

            assertEquals(PropertyFilter.Not(filter), notFilter)
        }

        @Test
        fun `not operator works with matching`() {
            val filter = !PropertyFilter.eq("owner", "alice")

            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice")))
            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "bob")))
        }

        @Test
        fun `infix and creates And filter`() {
            val f1 = PropertyFilter.eq("owner", "alice")
            val f2 = PropertyFilter.eq("status", "active")

            val combined = f1 and f2

            assertEquals(PropertyFilter.And(listOf(f1, f2)), combined)
        }

        @Test
        fun `infix and works with matching`() {
            val filter = PropertyFilter.eq("owner", "alice") and PropertyFilter.eq("status", "active")

            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice", "status" to "active")))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice", "status" to "inactive")))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "bob", "status" to "active")))
        }

        @Test
        fun `infix or creates Or filter`() {
            val f1 = PropertyFilter.eq("owner", "alice")
            val f2 = PropertyFilter.eq("owner", "bob")

            val combined = f1 or f2

            assertEquals(PropertyFilter.Or(listOf(f1, f2)), combined)
        }

        @Test
        fun `infix or works with matching`() {
            val filter = PropertyFilter.eq("owner", "alice") or PropertyFilter.eq("owner", "bob")

            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice")))
            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "bob")))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "charlie")))
        }

        @Test
        fun `operators can be chained for complex expressions`() {
            // (owner == "alice" AND status == "active") OR role == "admin"
            val filter = (PropertyFilter.eq("owner", "alice") and PropertyFilter.eq("status", "active")) or
                PropertyFilter.eq("role", "admin")

            // alice with active status - matches first branch
            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice", "status" to "active")))

            // admin role - matches second branch
            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "bob", "role" to "admin")))

            // alice but inactive - fails both branches
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice", "status" to "inactive")))
        }

        @Test
        fun `not can be combined with and-or`() {
            // owner == "alice" AND NOT status == "deleted"
            val filter = PropertyFilter.eq("owner", "alice") and !PropertyFilter.eq("status", "deleted")

            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice", "status" to "active")))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice", "status" to "deleted")))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "bob", "status" to "active")))
        }

        @Test
        fun `double negation works correctly`() {
            val filter = PropertyFilter.eq("owner", "alice")
            val doubleNegated = !!filter

            // Double negation should behave like original
            assertTrue(InMemoryPropertyFilter.matches(doubleNegated, mapOf("owner" to "alice")))
            assertFalse(InMemoryPropertyFilter.matches(doubleNegated, mapOf("owner" to "bob")))
        }
    }
}
