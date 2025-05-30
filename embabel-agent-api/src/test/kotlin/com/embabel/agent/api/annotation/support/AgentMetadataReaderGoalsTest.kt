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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.plan.goap.ConditionDetermination
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import kotlin.test.assertNull

class AgentMetadataReaderGoalsTest {

    @Test
    fun `no conditions`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(NoConditions())
        assertNull(metadata) // The metadata should be null! Due to the NoPathToCompletionValidator
    }

    @Test
    fun `one condition taking ProcessContext`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(OneProcessContextConditionOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        assertEquals(
            "${OneProcessContextConditionOnly::class.java.name}.condition1",
            metadata.conditions.first().name
        )
    }

    @Test
    fun `processContext condition invocation`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(OneProcessContextConditionOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        val mockProcessContext = mockk<ProcessContext>()
        every { mockProcessContext.agentProcess } returns mockk()
        assertEquals(ConditionDetermination.TRUE, condition.evaluate(mockProcessContext))
    }

    @Test
    fun `blackboard condition invocation not found`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(ConditionFromBlackboard())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        val mockProcessContext = mockk<ProcessContext>()
        every { mockProcessContext.blackboard } returns InMemoryBlackboard()
        every { mockProcessContext.agentProcess.agent.domainTypes } returns listOf(
            PersonWithReverseTool::class.java,
        )
        assertEquals(ConditionDetermination.FALSE, condition.evaluate(mockProcessContext))
    }

    @Test
    fun `blackboard condition invocation found and true`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(ConditionFromBlackboard())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        val mockProcessContext = mockk<ProcessContext>()
        val bb = InMemoryBlackboard()
        bb += PersonWithReverseTool("Rod")
        every { mockProcessContext.blackboard } returns bb
        every { mockProcessContext.agentProcess.agent.domainTypes } returns listOf(
            PersonWithReverseTool::class.java,
        )
        assertEquals(ConditionDetermination.TRUE, condition.evaluate(mockProcessContext))
    }

    @Test
    fun `custom named blackboard condition invocation found and true`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(CustomNameConditionFromBlackboard())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        assertEquals("condition1", condition.name)
        val mockProcessContext = mockk<ProcessContext>()
        val bb = InMemoryBlackboard()
        bb += PersonWithReverseTool("Rod")
        every { mockProcessContext.blackboard } returns bb
        every { mockProcessContext.agentProcess.agent.domainTypes } returns listOf(
            PersonWithReverseTool::class.java,
        )
        assertEquals(ConditionDetermination.TRUE, condition.evaluate(mockProcessContext))
    }

    @Test
    fun `blackboard condition invocation found and false`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(ConditionFromBlackboard())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        val mockProcessContext = mockk<ProcessContext>()
        val bb = InMemoryBlackboard()
        bb += PersonWithReverseTool("ted")
        every { mockProcessContext.blackboard } returns bb
        every { mockProcessContext.agentProcess.agent.domainTypes } returns listOf(
            PersonWithReverseTool::class.java,
        )
        assertEquals(ConditionDetermination.FALSE, condition.evaluate(mockProcessContext))
    }

    @Test
    fun `blackboard conditions invocation not all found and false`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(ConditionsFromBlackboard())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        val mockProcessContext = mockk<ProcessContext>()
        val bb = InMemoryBlackboard()
        bb += PersonWithReverseTool("Rod")
        every { mockProcessContext.blackboard } returns bb
        every { mockProcessContext.agentProcess.agent.domainTypes } returns listOf(
            PersonWithReverseTool::class.java, Frog::class.java,
        )
        assertEquals(ConditionDetermination.FALSE, condition.evaluate(mockProcessContext))
    }

    @Test
    fun `blackboard conditions invocation all found and true`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(ConditionsFromBlackboard())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        val mockProcessContext = mockk<ProcessContext>()
        val bb = InMemoryBlackboard()
        bb += PersonWithReverseTool("Rod")
        bb += Frog("Kermit")
        every { mockProcessContext.blackboard } returns bb
        every { mockProcessContext.agentProcess.agent.domainTypes } returns listOf(
            PersonWithReverseTool::class.java, Frog::class.java,
        )
        assertEquals(ConditionDetermination.TRUE, condition.evaluate(mockProcessContext))
    }

}
