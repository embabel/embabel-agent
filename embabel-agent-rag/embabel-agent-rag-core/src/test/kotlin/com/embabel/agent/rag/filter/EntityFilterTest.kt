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
import com.embabel.agent.rag.model.SimpleNamedEntityData
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies [EntityFilter] construction and entity matching by labels and properties. */
class EntityFilterTest {

    private fun entity(vararg labels: String) = SimpleNamedEntityData(
        id = "e1",
        name = "Entity",
        description = "desc",
        labels = labels.toSet(),
        properties = emptyMap(),
    )

    @Nested
    inner class FactoryMethods {

        @Test
        fun `secondary vararg constructor creates HasAnyLabel`() {
            val filter = EntityFilter.HasAnyLabel("Person", "Organization", "Person")

            assertEquals(setOf("Person", "Organization"), filter.labels)
        }

        @Test
        fun `vararg factory creates HasAnyLabel`() {
            val filter = EntityFilter.hasAnyLabel("Person", "Organization")

            assertEquals(setOf("Person", "Organization"), filter.labels)
        }

        @Test
        fun `set factory creates HasAnyLabel`() {
            val filter = EntityFilter.hasAnyLabel(setOf("Person"))

            assertEquals(setOf("Person"), filter.labels)
        }

        @Test
        fun `collection factory creates HasAnyLabel`() {
            val filter = EntityFilter.hasAnyLabel(listOf("Person", "Person", "Org"))

            assertEquals(setOf("Person", "Org"), filter.labels)
        }

        @Test
        fun `empty labels are rejected`() {
            assertFailsWith<IllegalArgumentException> {
                EntityFilter.hasAnyLabel()
            }
            assertFailsWith<IllegalArgumentException> {
                EntityFilter.hasAnyLabel(emptySet())
            }
            assertFailsWith<IllegalArgumentException> {
                EntityFilter.hasAnyLabel(emptyList())
            }
            assertFailsWith<IllegalArgumentException> {
                EntityFilter.HasAnyLabel(emptySet())
            }
        }
    }

    @Nested
    inner class MatchesEntityFilterExtension {

        @Test
        fun `null filter always matches`() {
            assertTrue(entity("Person").matchesEntityFilter(null))
        }

        @Test
        fun `matches when entity has overlapping label`() {
            val filter = EntityFilter.hasAnyLabel("Person", "Organization")

            assertTrue(entity("Person", "Employee").matchesEntityFilter(filter))
        }

        @Test
        fun `does not match when labels are disjoint`() {
            val filter = EntityFilter.hasAnyLabel("Organization")

            assertFalse(entity("Person").matchesEntityFilter(filter))
        }

        @Test
        fun `label matching is case sensitive`() {
            val filter = EntityFilter.hasAnyLabel("person")

            assertFalse(entity("Person").matchesEntityFilter(filter))
        }

        @Test
        fun `property eq filter matches entity properties`() {
            val entity = SimpleNamedEntityData(
                id = "e1",
                name = "Alice",
                description = "desc",
                labels = setOf("Person"),
                properties = mapOf("role" to "admin"),
            )

            assertTrue(entity.matchesEntityFilter(PropertyFilter.Eq(key = "role", value = "admin")))
            assertFalse(entity.matchesEntityFilter(PropertyFilter.Eq(key = "role", value = "guest")))
        }
    }
}
