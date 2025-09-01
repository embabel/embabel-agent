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

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.testing.unit.FakeOperationContext
import com.embabel.plan.goap.ConditionDetermination
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class AgentMetadataReaderConditionsTest {

    @Test
    fun `no conditions`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(NoConditions())
        assertNotNull(metadata)
        assertEquals(0, metadata!!.conditions.size)
    }

    @Test
    fun `one condition taking OperationContext`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(OneOperationContextConditionOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        assertEquals(
            "${OneOperationContextConditionOnly::class.java.name}.condition1",
            metadata.conditions.first().name
        )
    }

    @Test
    fun `one condition taking Ai`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(OneOperationContextAiOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        assertEquals(
            "${OneOperationContextAiOnly::class.java.name}.condition1",
            metadata.conditions.first().name
        )
    }

    @Test
    fun `processContext condition invocation`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(OneOperationContextConditionOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        val mockProcessContext = mockk<OperationContext>()
        every { mockProcessContext.processContext.agentProcess } returns mockk()
        assertEquals(ConditionDetermination.TRUE, condition.evaluate(mockProcessContext))
    }

    @Test
    fun `blackboard condition invocation not found`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(ConditionFromBlackboard())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        val fakeOperationContext = FakeOperationContext()
        assertEquals(ConditionDetermination.FALSE, condition.evaluate(fakeOperationContext))
    }

    @Test
    fun `blackboard condition invocation found and true`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(ConditionFromBlackboard())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        val fakeOperationContext = FakeOperationContext()
        fakeOperationContext += PersonWithReverseTool("Rod")
        assertEquals(ConditionDetermination.TRUE, condition.evaluate(fakeOperationContext))
    }

    @Test
    fun `custom named blackboard condition invocation found and true`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(CustomNameConditionFromBlackboard())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        assertEquals("condition1", condition.name)
        val fakeOperationContext = FakeOperationContext()
        fakeOperationContext += PersonWithReverseTool("Rod")
        assertEquals(ConditionDetermination.TRUE, condition.evaluate(fakeOperationContext))
    }

    @Test
    fun `blackboard condition invocation found and false`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(ConditionFromBlackboard())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        val fakeOperationContext = FakeOperationContext()
        fakeOperationContext += PersonWithReverseTool("ted")
        assertEquals(ConditionDetermination.FALSE, condition.evaluate(fakeOperationContext))
    }

    @Test
    fun `blackboard conditions invocation not all found and false`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(ConditionsFromBlackboard())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        val fakeOperationContext = FakeOperationContext()
        fakeOperationContext += PersonWithReverseTool("Rod")
        assertEquals(ConditionDetermination.FALSE, condition.evaluate(fakeOperationContext))
    }

    @Test
    fun `blackboard conditions invocation all found and true`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(ConditionsFromBlackboard())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.conditions.size)
        val condition = metadata.conditions.first()
        val fakeOperationContext = FakeOperationContext()
        fakeOperationContext += PersonWithReverseTool("Rod")
        fakeOperationContext += Frog("Kermit")
        assertEquals(ConditionDetermination.TRUE, condition.evaluate(fakeOperationContext))
    }

}
