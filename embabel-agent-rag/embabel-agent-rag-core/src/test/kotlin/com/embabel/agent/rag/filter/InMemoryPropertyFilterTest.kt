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
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Covers the entity-aware and reflection-based matching paths of [InMemoryPropertyFilter].
 * Generic map matching and the metadata `filterResults` path are exercised in [PropertyFilterTest];
 * this focuses on [InMemoryPropertyFilter.matchesEntity], [InMemoryPropertyFilter.matchesObject],
 * and the per-family filter helpers.
 */
class InMemoryPropertyFilterTest {

    private fun entity(
        id: String = "e1",
        labels: Set<String> = setOf("Person"),
        properties: Map<String, Any> = mapOf("owner" to "alice"),
        metadata: Map<String, Any?> = emptyMap(),
    ) = SimpleNamedEntityData(
        id = id,
        name = "Entity $id",
        description = "an entity",
        labels = labels,
        properties = properties,
        metadata = metadata,
    )

    private fun chunk(id: String, metadata: Map<String, Any?> = emptyMap()) =
        Chunk(id = id, text = "text", parentId = "parent", metadata = metadata)

    @Nested
    inner class MatchesMaps {

        @Test
        fun `matches delegates to the generic property filter`() {
            val filter = PropertyFilter.Eq("owner", "alice")

            assertTrue(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "alice")))
            assertFalse(InMemoryPropertyFilter.matches(filter, mapOf("owner" to "bob")))
        }

        @Test
        fun `matchesProperties delegates to the generic property filter`() {
            val filter = PropertyFilter.Eq("owner", "alice")

            assertTrue(InMemoryPropertyFilter.matchesProperties(filter, mapOf("owner" to "alice")))
            assertFalse(InMemoryPropertyFilter.matchesProperties(filter, mapOf("owner" to "bob")))
        }
    }

    @Nested
    inner class MatchesEntity {

        @Test
        fun `HasAnyLabel matches when the entity carries one of the labels`() {
            val entity = entity(labels = setOf("Person", "Customer"))

            assertTrue(InMemoryPropertyFilter.matchesEntity(EntityFilter.hasAnyLabel("Customer"), entity))
            assertTrue(InMemoryPropertyFilter.matchesEntity(EntityFilter.hasAnyLabel("Company", "Person"), entity))
        }

        @Test
        fun `HasAnyLabel does not match when no label overlaps`() {
            val entity = entity(labels = setOf("Person"))

            assertFalse(InMemoryPropertyFilter.matchesEntity(EntityFilter.hasAnyLabel("Company"), entity))
        }

        @Test
        fun `a non-label filter is evaluated against the entity properties`() {
            val entity = entity(properties = mapOf("owner" to "alice"))

            assertTrue(InMemoryPropertyFilter.matchesEntity(PropertyFilter.Eq("owner", "alice"), entity))
            assertFalse(InMemoryPropertyFilter.matchesEntity(PropertyFilter.Eq("owner", "bob"), entity))
        }
    }

    @Nested
    inner class MatchesObject {

        @Test
        fun `entities are routed through entity matching`() {
            val entity = entity(labels = setOf("Person"), properties = mapOf("owner" to "alice"))

            assertTrue(InMemoryPropertyFilter.matchesObject(EntityFilter.hasAnyLabel("Person"), entity))
            assertTrue(InMemoryPropertyFilter.matchesObject(PropertyFilter.Eq("owner", "alice"), entity))
            assertFalse(InMemoryPropertyFilter.matchesObject(EntityFilter.hasAnyLabel("Company"), entity))
        }

        @Test
        fun `an entity filter never matches a non-entity object`() {
            assertFalse(
                InMemoryPropertyFilter.matchesObject(EntityFilter.hasAnyLabel("Person"), PlainDocument("alice", "active"))
            )
        }

        @Test
        fun `plain objects are matched by reflecting over their properties`() {
            val document = PlainDocument(owner = "alice", status = "active")

            assertTrue(InMemoryPropertyFilter.matchesObject(PropertyFilter.Eq("owner", "alice"), document))
            assertTrue(InMemoryPropertyFilter.matchesObject(PropertyFilter.Eq("status", "active"), document))
            assertFalse(InMemoryPropertyFilter.matchesObject(PropertyFilter.Eq("owner", "bob"), document))
        }
    }

    @Nested
    inner class FilterByMetadata {

        @Test
        fun `returns every result when the filter is null`() {
            val results = listOf(
                SimpleSimilaritySearchResult(match = chunk("1", mapOf("owner" to "alice")), score = 0.9),
                SimpleSimilaritySearchResult(match = chunk("2", mapOf("owner" to "bob")), score = 0.8),
            )

            assertEquals(results, InMemoryPropertyFilter.filterByMetadata(results, null))
        }

        @Test
        fun `keeps only results whose metadata matches`() {
            val results = listOf(
                SimpleSimilaritySearchResult(match = chunk("1", mapOf("owner" to "alice")), score = 0.9),
                SimpleSimilaritySearchResult(match = chunk("2", mapOf("owner" to "bob")), score = 0.8),
            )

            val filtered = InMemoryPropertyFilter.filterByMetadata(results, PropertyFilter.Eq("owner", "alice"))

            assertEquals(1, filtered.size)
            assertEquals("1", filtered.single().match.id)
        }
    }

    @Nested
    inner class FilterByProperties {

        @Test
        fun `returns every result when the filter is null`() {
            val results = listOf(SimpleSimilaritySearchResult(match = entity("1"), score = 0.9))

            assertEquals(results, InMemoryPropertyFilter.filterByProperties(results, null))
        }

        @Test
        fun `keeps only results whose entity properties match`() {
            val results = listOf(
                SimpleSimilaritySearchResult(match = entity("1", properties = mapOf("owner" to "alice")), score = 0.9),
                SimpleSimilaritySearchResult(match = entity("2", properties = mapOf("owner" to "bob")), score = 0.8),
            )

            val filtered = InMemoryPropertyFilter.filterByProperties(results, PropertyFilter.Eq("owner", "alice"))

            assertEquals(1, filtered.size)
            assertEquals("1", filtered.single().match.id)
        }
    }

    @Nested
    inner class FilterResults {

        @Test
        fun `applies an entity filter on top of the results`() {
            val results = listOf(
                SimpleSimilaritySearchResult(match = entity("1", labels = setOf("Person")), score = 0.9),
                SimpleSimilaritySearchResult(match = entity("2", labels = setOf("Company")), score = 0.8),
            )

            val filtered = InMemoryPropertyFilter.filterResults(
                results,
                metadataFilter = null,
                entityFilter = EntityFilter.hasAnyLabel("Person"),
            )

            assertEquals(1, filtered.size)
            assertEquals("1", filtered.single().match.id)
        }

        @Test
        fun `requires both metadata and entity filters to match`() {
            val results = listOf(
                SimpleSimilaritySearchResult(
                    match = entity("1", labels = setOf("Person"), metadata = mapOf("region" to "us")),
                    score = 0.9,
                ),
                SimpleSimilaritySearchResult(
                    match = entity("2", labels = setOf("Company"), metadata = mapOf("region" to "us")),
                    score = 0.8,
                ),
                SimpleSimilaritySearchResult(
                    match = entity("3", labels = setOf("Person"), metadata = mapOf("region" to "eu")),
                    score = 0.7,
                ),
            )

            val filtered = InMemoryPropertyFilter.filterResults(
                results,
                metadataFilter = PropertyFilter.Eq("region", "us"),
                entityFilter = EntityFilter.hasAnyLabel("Person"),
            )

            assertEquals(1, filtered.size)
            assertEquals("1", filtered.single().match.id)
        }
    }
}

/**
 * A plain object (not a [com.embabel.agent.rag.model.NamedEntityData]) used to exercise the
 * reflection-based branch of [InMemoryPropertyFilter.matchesObject]. Declared at top level so its
 * properties are reachable via reflection.
 */
internal data class PlainDocument(val owner: String, val status: String)
