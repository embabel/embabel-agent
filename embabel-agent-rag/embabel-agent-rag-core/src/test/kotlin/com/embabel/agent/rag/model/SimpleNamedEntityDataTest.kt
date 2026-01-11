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
package com.embabel.agent.rag.model

import com.embabel.agent.rag.model.NamedEntityData.Companion.ENTITY_LABEL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [SimpleNamedEntityData] focusing on label handling.
 *
 * These tests verify that data container classes don't pollute entity labels
 * with their class names, which would cause duplicate nodes in Neo4j when
 * using MERGE with all labels.
 */
class SimpleNamedEntityDataTest {

    @Nested
    inner class LabelsTests {

        @Test
        fun `labels should include ENTITY_LABEL`() {
            val entity = SimpleNamedEntityData(
                id = "test-1",
                name = "Test Entity",
                description = "A test entity",
                labels = setOf("Person"),
                properties = emptyMap(),
            )

            assertTrue(entity.labels().contains(ENTITY_LABEL))
        }

        @Test
        fun `labels should include provided labels`() {
            val entity = SimpleNamedEntityData(
                id = "test-1",
                name = "Test Entity",
                description = "A test entity",
                labels = setOf("Person", "Employee"),
                properties = emptyMap(),
            )

            assertTrue(entity.labels().contains("Person"))
            assertTrue(entity.labels().contains("Employee"))
        }

        @Test
        fun `labels should NOT include SimpleNamedEntityData class name`() {
            // This is critical - if the class name is included as a label,
            // Neo4j MERGE will create duplicate nodes when labels differ
            val entity = SimpleNamedEntityData(
                id = "test-1",
                name = "Test Entity",
                description = "A test entity",
                labels = setOf("Composer", "Reference"),
                properties = emptyMap(),
            )

            assertFalse(
                entity.labels().contains("SimpleNamedEntityData"),
                "SimpleNamedEntityData class name should NOT be in labels - " +
                        "this causes duplicate nodes in Neo4j. Actual labels: ${entity.labels()}"
            )
        }

        @Test
        fun `labels should NOT include NamedEntityData class name`() {
            val entity = SimpleNamedEntityData(
                id = "test-1",
                name = "Test Entity",
                description = "A test entity",
                labels = setOf("Work"),
                properties = emptyMap(),
            )

            assertFalse(
                entity.labels().contains("NamedEntityData"),
                "NamedEntityData class name should NOT be in labels. Actual labels: ${entity.labels()}"
            )
        }

        @Test
        fun `labels should only contain provided labels plus ENTITY_LABEL`() {
            val providedLabels = setOf("Composer", "Reference")
            val entity = SimpleNamedEntityData(
                id = "mahler",
                name = "Mahler",
                description = "Gustav Mahler",
                labels = providedLabels,
                properties = emptyMap(),
            )

            val expectedLabels = providedLabels + ENTITY_LABEL
            assertEquals(
                expectedLabels,
                entity.labels(),
                "Labels should exactly match provided labels + $ENTITY_LABEL"
            )
        }

        @Test
        fun `empty labels should only contain ENTITY_LABEL`() {
            val entity = SimpleNamedEntityData(
                id = "test-1",
                name = "Test",
                description = "Test",
                labels = emptySet(),
                properties = emptyMap(),
            )

            assertEquals(
                setOf(ENTITY_LABEL),
                entity.labels(),
                "Empty labels should result in only $ENTITY_LABEL"
            )
        }
    }

    @Nested
    inner class ConsistencyTests {

        @Test
        fun `labels should be consistent across multiple calls`() {
            val entity = SimpleNamedEntityData(
                id = "test-1",
                name = "Test",
                description = "Test",
                labels = setOf("Person"),
                properties = emptyMap(),
            )

            val labels1 = entity.labels()
            val labels2 = entity.labels()

            assertEquals(labels1, labels2, "Labels should be consistent across calls")
        }

        @Test
        fun `two entities with same labels should have identical label sets`() {
            val entity1 = SimpleNamedEntityData(
                id = "entity-1",
                name = "Entity 1",
                description = "First",
                labels = setOf("Composer", "Reference"),
                properties = emptyMap(),
            )

            val entity2 = SimpleNamedEntityData(
                id = "entity-2",
                name = "Entity 2",
                description = "Second",
                labels = setOf("Composer", "Reference"),
                properties = emptyMap(),
            )

            assertEquals(
                entity1.labels(),
                entity2.labels(),
                "Entities with same label configuration should produce identical label sets"
            )
        }
    }
}
