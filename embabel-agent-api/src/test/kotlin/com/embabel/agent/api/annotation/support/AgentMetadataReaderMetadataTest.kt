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

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.core.support.Rerun
import com.embabel.agent.support.containsAll
import com.embabel.agent.testing.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.plan.goap.ConditionDetermination
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertNull
import com.embabel.agent.core.Agent as CoreAgent


/**
 * Test for metadata extraction from annotations,
 * not invocation
 */
class AgentMetadataReaderMetadataTest {

    @Nested
    inner class Errors {

        @Test
        fun `no annotation`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(PersonWithReverseTool("John Doe"))
            assertNull(metadata)
        }

        @Test
        fun `no methods`() {
            val reader = AgentMetadataReader()
            assertNull(reader.createAgentMetadata(NoMethods()))
        }

        @Test
        @Disabled
        fun invalidConditionSignature() {
        }

        @Test
        fun `invalid action signature returning interface without serialization annotation`() {
            val reader = AgentMetadataReader()
            assertNull(reader.createAgentMetadata(InvalidActionNoDeserializationInInterfaceGoal()))
        }

        @Test
        fun `valid action signature returning interface with serialization annotation`() {
            val reader = AgentMetadataReader()
            assertNotNull(reader.createAgentMetadata(ValidActionWithDeserializationInInterfaceGoal()))
        }

    }

    @Nested
    inner class Goals {

        @Test
        fun `one goal only`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(OneGoalOnly())
            assertNotNull(metadata)
            assertEquals(1, metadata!!.goals.size)
        }

        @Test
        fun `metadata is preserved`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(OneGoalOnlyWithRichMetadata())
            assertNotNull(metadata)
            assertEquals(1, metadata!!.goals.size)
            val g = metadata.goals.single()
            assertEquals("This is a goal with rich metadata", g.description)
            assertEquals(setOf("foo", "bar"), g.tags)
            assertEquals(setOf("make me happy"), g.examples)
        }

        @Test
        fun `two goals only`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(TwoGoalsOnly())
            assertNotNull(metadata)
            assertEquals(2, metadata!!.goals.size)
            val expectedThing1GoalName = "${TwoGoalsOnly::class.java.name}.thing1"
            val expectedThing2GoalName = "${TwoGoalsOnly::class.java.name}.thing2"
            val t1 = metadata.goals.find { it.name == expectedThing1GoalName }
            val t2 = metadata.goals.find { it.name == expectedThing2GoalName }
            assertNotNull(t1, "Should have $expectedThing1GoalName goal: " + metadata.goals.map { it.name })
            assertNotNull(t2, "Should have $expectedThing2GoalName goal: " + metadata.goals.map { it.name })

            assertEquals("Thanks to Dr Seuss", t1!!.description)
            assertEquals("Thanks again to Dr Seuss", t2!!.description)
        }

        @Test
        fun `action goal requires output of action method`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(ActionGoal())
            assertNotNull(metadata)
            assertEquals(1, metadata!!.goals.size)
            val g = metadata.goals.single()
            assertEquals("Creating a person", g.description)
            assertTrue(
                g.preconditions.containsAll(mapOf("it:${PersonWithReverseTool::class.qualifiedName}" to ConditionDetermination.TRUE)),
                "Should have precondition for Person",
            )
        }

        @Test
        fun `action goal requires action method to have run`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(ActionGoal())
            assertNotNull(metadata)
            assertEquals(1, metadata!!.goals.size)
            val action = metadata.actions.single()
            val g = metadata.goals.single()
            assertEquals(PersonWithReverseTool::class.java, g.outputClass)
            assertEquals("Creating a person", g.description)
            val expected = mapOf(
                "it:${PersonWithReverseTool::class.qualifiedName}" to ConditionDetermination.TRUE,
                Rerun.hasRunCondition(action) to ConditionDetermination.TRUE
            )
            assertTrue(
                g.preconditions.containsAll(
                    expected,
                ),
                "Should have precondition for input to the action method: have\n${g.preconditions}, expected\n$expected",
            )
        }

        @Test
        fun `two distinct action goals`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(TwoActionGoals())
            assertNotNull(metadata)
            assertEquals(2, metadata!!.goals.size)
            val personGoal = metadata.goals.find { it.name == TwoActionGoals::class.java.name + ".toPerson" }
                ?: fail("Should have toPerson goal: " + metadata.goals.map { it.name })
            val frogGoal = metadata.goals.find { it.name == TwoActionGoals::class.java.name + ".toFrog" }
                ?: fail("Should have toFrog goal: " + metadata.goals.map { it.name })

            assertEquals("Creating a person", personGoal.description)
            assertTrue(
                personGoal.preconditions.containsAll(
                    mapOf(
                        "it:${PersonWithReverseTool::class.qualifiedName}" to ConditionDetermination.TRUE,
//                        "it:${UserInput::class.qualifiedName}" to ConditionDetermination.TRUE
                    )
                ),
                "Should have precondition for Person",
            )
            assertEquals("Creating a frog", frogGoal.description)
            assertTrue(
                frogGoal.preconditions.containsAll(
                    mapOf("it:${Frog::class.qualifiedName}" to ConditionDetermination.TRUE),
                ),
                "Should have precondition for Frog",
            )
        }

        @Test
        fun `two actually non conflicting action goals with different inputs but same output`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(TwoActuallyNonConflictingActionGoalsWithSameOutput())
            assertNotNull(metadata)
            assertEquals(2, metadata!!.goals.size)
            val expectedPersonGoalName =
                TwoActuallyNonConflictingActionGoalsWithSameOutput::class.java.name + ".toPerson"
            val personGoal =
                metadata.goals.find { it.name == expectedPersonGoalName }
                    ?: fail("Should have $expectedPersonGoalName goal: " + metadata.goals.map { it.name })

            val alsoGoal =
                metadata.goals.find { it.name == TwoActuallyNonConflictingActionGoalsWithSameOutput::class.java.name + ".alsoToPerson" }
                    ?: fail("Should have alsoToPerson goal: " + metadata.goals.map { it.name })

            assertEquals("Creating a person", personGoal.description)
            assertTrue(
                personGoal.preconditions.containsAll(
                    mapOf(
                        "it:${PersonWithReverseTool::class.qualifiedName}" to ConditionDetermination.TRUE,
//                        "it:${UserInput::class.qualifiedName}" to ConditionDetermination.TRUE,
                    )
                ),
                "Should have precondition for Person",
            )
            assertEquals("Also to person", alsoGoal.description)
            assertTrue(
                alsoGoal.preconditions.containsAll(
                    mapOf("it:${PersonWithReverseTool::class.qualifiedName}" to ConditionDetermination.TRUE)
                ),
                "Should have precondition for alsoPerson",
            )
        }

        @Test
        @Disabled("must decide what behavior should be")
        fun `two conflicting action goals`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(TwoConflictingActionGoals())
            TODO("decide what to do here: this invalid")
        }
    }


    @Nested
    inner class Agents {

        @Test
        fun `not an agent`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(ConditionFromBlackboard())
            assertNotNull(metadata)
            assertFalse(metadata!! is CoreAgent)
        }

        @Test
        fun `recognize an agent`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(AgentWithOneTransformerActionWith2ArgsOnly())
            assertNotNull(metadata)
            assertTrue(metadata is CoreAgent, "@Agent should create an agent")
            metadata as CoreAgent
            assertEquals(1, metadata.actions.size)
            assertEquals(
                AgentWithOneTransformerActionWith2ArgsOnly::class.java.simpleName,
                metadata.name,
            )
        }

        @Test
        fun `support agent with custom name, provider and version`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(AgentWithCustomName())
            assertNotNull(metadata)
            assertTrue(metadata is CoreAgent, "@Agent should create an agent")
            metadata as CoreAgent
            assertEquals(1, metadata.actions.size)
            assertEquals("magic", metadata.provider)
            assertEquals("1.1.1", metadata.version.value)
            assertEquals(
                "myAgentWithCustomName",
                metadata.name,
            )
        }

        @Test
        fun `not expose actions on opaque agent `() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(OpaqueAgent())
            assertNotNull(metadata)
            assertTrue(metadata is CoreAgent, "@Agent should create an agent")
            metadata as CoreAgent
            assertEquals(1, metadata.actions.size)
            val ap = dummyAgentPlatform()
            ap.deploy(metadata)
            assertEquals(1, ap.agents().size)
            assertEquals(0, ap.actions.size)
        }

        @Test
        fun `expose actions by default on non-opaque agent`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(AgentWithCustomName())
            assertNotNull(metadata)
            assertTrue(metadata is CoreAgent, "@Agent should create an agent")
            metadata as CoreAgent
            assertEquals(1, metadata.actions.size)
            val ap = dummyAgentPlatform()
            ap.deploy(metadata)
            assertEquals(1, ap.agents().size)
            assertEquals(1, ap.actions.size)
        }

        @Test
        fun `not treat OperationContext parameter as a precondition`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(
                AgentWithOneTransformerActionWith2ArgsOnlyAndOperationContextParameter()
            )
            assertNotNull(metadata)
            assertTrue(metadata is CoreAgent, "@Agent should create an agent")
            metadata as CoreAgent
            assertEquals(1, metadata.actions.size)
            val action = metadata.actions.single()
            assertFalse(
                action.preconditions.any { it.key == "it:${OperationContext::class.java.name}" },
                "OperationContext parameter should not be treated as an input precondition: ${action.preconditions}"
            )
        }

        @Test
        fun `not treat Ai parameter as a precondition`() {
            val reader = AgentMetadataReader()
            val metadata = reader.createAgentMetadata(AgentWithOneTransformerActionWith2ArgsOnlyAndAiParameter())
            assertNotNull(metadata)
            assertTrue(metadata is CoreAgent, "@Agent should create an agent")
            metadata as CoreAgent
            assertEquals(1, metadata.actions.size)
            val action = metadata.actions.single()
            assertFalse(
                action.preconditions.any { it.key == "it:${Ai::class.java.name}" },
                "Ai parameter should not be treated as an input precondition: ${action.preconditions}"
            )
        }
    }
}
