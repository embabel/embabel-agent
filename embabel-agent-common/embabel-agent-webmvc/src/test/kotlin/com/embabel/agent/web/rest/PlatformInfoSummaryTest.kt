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
package com.embabel.agent.web.rest

import com.embabel.common.ai.model.ModelMetadata
import com.embabel.agent.core.ToolGroupMetadata
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlatformInfoSummaryTest {

    @Test
    fun `should create PlatformInfoSummary with all parameters`() {
        // Arrange
        val agentNames = setOf("agent1", "agent2")
        val domainTypes = setOf("type1", "type2")
        val models = emptyList<ModelMetadata>()
        val toolGroups = emptyList<ToolGroupMetadata>()

        // Act
        val summary = PlatformInfoSummary(
            agentCount = 2,
            agentNames = agentNames,
            actionCount = 5,
            goalCount = 3,
            conditionCount = 4,
            name = "Test Platform",
            domainTypes = domainTypes,
            models = models,
            toolGroups = toolGroups
        )

        // Assert
        assertEquals(2, summary.agentCount)
        assertEquals(agentNames, summary.agentNames)
        assertEquals(5, summary.actionCount)
        assertEquals(3, summary.goalCount)
        assertEquals(4, summary.conditionCount)
        assertEquals("Test Platform", summary.name)
        assertEquals(domainTypes, summary.domainTypes)
        assertEquals(models, summary.models)
        assertEquals(toolGroups, summary.toolGroups)
    }

    @Test
    fun `should handle empty collections`() {
        // Arrange & Act
        val summary = PlatformInfoSummary(
            agentCount = 0,
            agentNames = emptySet(),
            actionCount = 0,
            goalCount = 0,
            conditionCount = 0,
            name = "Empty Platform",
            domainTypes = emptySet(),
            models = emptyList(),
            toolGroups = emptyList()
        )

        // Assert
        assertEquals(0, summary.agentCount)
        assertTrue(summary.agentNames.isEmpty())
        assertEquals(0, summary.actionCount)
        assertEquals(0, summary.goalCount)
        assertEquals(0, summary.conditionCount)
        assertTrue(summary.domainTypes.isEmpty())
        assertTrue(summary.models.isEmpty())
        assertTrue(summary.toolGroups.isEmpty())
    }

    @Test
    fun `should support copy with modified agentCount`() {
        // Arrange
        val original = PlatformInfoSummary(
            agentCount = 2,
            agentNames = setOf("agent1"),
            actionCount = 5,
            goalCount = 3,
            conditionCount = 4,
            name = "Platform",
            domainTypes = emptySet(),
            models = emptyList(),
            toolGroups = emptyList()
        )

        // Act
        val modified = original.copy(agentCount = 5)

        // Assert
        assertEquals(5, modified.agentCount)
        assertEquals(2, original.agentCount)
    }

    @Test
    fun `should support copy with modified agentNames`() {
        // Arrange
        val original = PlatformInfoSummary(
            agentCount = 2,
            agentNames = setOf("agent1"),
            actionCount = 5,
            goalCount = 3,
            conditionCount = 4,
            name = "Platform",
            domainTypes = emptySet(),
            models = emptyList(),
            toolGroups = emptyList()
        )

        // Act
        val modified = original.copy(agentNames = setOf("agent1", "agent2", "agent3"))

        // Assert
        assertEquals(3, modified.agentNames.size)
        assertEquals(1, original.agentNames.size)
    }

    @Test
    fun `should support copy with modified name`() {
        // Arrange
        val original = PlatformInfoSummary(
            agentCount = 2,
            agentNames = setOf("agent1"),
            actionCount = 5,
            goalCount = 3,
            conditionCount = 4,
            name = "Original Platform",
            domainTypes = emptySet(),
            models = emptyList(),
            toolGroups = emptyList()
        )

        // Act
        val modified = original.copy(name = "Modified Platform")

        // Assert
        assertEquals("Modified Platform", modified.name)
        assertEquals("Original Platform", original.name)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val summary1 = PlatformInfoSummary(
            agentCount = 2,
            agentNames = setOf("agent1"),
            actionCount = 5,
            goalCount = 3,
            conditionCount = 4,
            name = "Platform",
            domainTypes = emptySet(),
            models = emptyList(),
            toolGroups = emptyList()
        )
        val summary2 = PlatformInfoSummary(
            agentCount = 2,
            agentNames = setOf("agent1"),
            actionCount = 5,
            goalCount = 3,
            conditionCount = 4,
            name = "Platform",
            domainTypes = emptySet(),
            models = emptyList(),
            toolGroups = emptyList()
        )
        val summary3 = PlatformInfoSummary(
            agentCount = 3,
            agentNames = setOf("agent1"),
            actionCount = 5,
            goalCount = 3,
            conditionCount = 4,
            name = "Platform",
            domainTypes = emptySet(),
            models = emptyList(),
            toolGroups = emptyList()
        )

        // Assert
        assertEquals(summary1, summary2)
        assertNotEquals(summary1, summary3)
    }

    @Test
    fun `should handle large counts`() {
        // Arrange & Act
        val summary = PlatformInfoSummary(
            agentCount = 1000,
            agentNames = (1..1000).map { "agent$it" }.toSet(),
            actionCount = 5000,
            goalCount = 300,
            conditionCount = 400,
            name = "Large Platform",
            domainTypes = emptySet(),
            models = emptyList(),
            toolGroups = emptyList()
        )

        // Assert
        assertEquals(1000, summary.agentCount)
        assertEquals(1000, summary.agentNames.size)
        assertEquals(5000, summary.actionCount)
        assertEquals(300, summary.goalCount)
        assertEquals(400, summary.conditionCount)
    }

    @Test
    fun `should handle multiple domain types`() {
        // Arrange
        val domainTypes = setOf("Banking", "Healthcare", "Retail", "Education")

        // Act
        val summary = PlatformInfoSummary(
            agentCount = 5,
            agentNames = setOf("agent1"),
            actionCount = 10,
            goalCount = 5,
            conditionCount = 8,
            name = "Multi-Domain Platform",
            domainTypes = domainTypes,
            models = emptyList(),
            toolGroups = emptyList()
        )

        // Assert
        assertEquals(4, summary.domainTypes.size)
        assertTrue(summary.domainTypes.contains("Banking"))
        assertTrue(summary.domainTypes.contains("Healthcare"))
        assertTrue(summary.domainTypes.contains("Retail"))
        assertTrue(summary.domainTypes.contains("Education"))
    }

    @Test
    fun `should preserve unique agent names in set`() {
        // Arrange
        val agentNames = setOf("agent1", "agent2", "agent1", "agent3")

        // Act
        val summary = PlatformInfoSummary(
            agentCount = 3,
            agentNames = agentNames,
            actionCount = 5,
            goalCount = 3,
            conditionCount = 4,
            name = "Platform",
            domainTypes = emptySet(),
            models = emptyList(),
            toolGroups = emptyList()
        )

        // Assert
        assertEquals(3, summary.agentNames.size)
        assertTrue(summary.agentNames.contains("agent1"))
        assertTrue(summary.agentNames.contains("agent2"))
        assertTrue(summary.agentNames.contains("agent3"))
    }
}
