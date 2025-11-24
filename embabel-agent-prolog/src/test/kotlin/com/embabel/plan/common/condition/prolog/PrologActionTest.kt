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
package com.embabel.plan.common.condition.prolog

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.plan.common.condition.ConditionDetermination
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

class PrologActionTest {

    @Test
    fun `invoke two actions`() {
        val reader = AgentMetadataReader()
        val metadata =
            reader.createAgentMetadata(
                Prolog2ActionsNoGoal()
            )
        assertNotNull(metadata)
        assertEquals(2, metadata!!.actions.size)

        // Create Prolog rules for elephant age checking
        val prologRules = """
            % elephant_age is automatically generated from the Elephant object
            % The PrologFactConverter will create facts like:
            % elephant_age('Zaboya', 30)
        """.trimIndent()

        val prologParser = PrologLogicalExpressionParser.fromRules(prologRules)

        // Create a custom platform that sets the Prolog evaluation context
//        val customParser = object : com.embabel.plan.common.condition.LogicalExpressionParser {
//            override fun parse(expression: String): com.embabel.plan.common.condition.LogicalExpression? {
//                val baseExpression = prologParser.parse(expression)
//                if (baseExpression == null) return null
//
//                // Wrap to inject blackboard objects during evaluation
//                return object : com.embabel.plan.common.condition.LogicalExpression {
//                    override fun evaluate(determineCondition: (String) -> ConditionDetermination): ConditionDetermination {
//                        // Note: In real usage, the blackboard would be set by a custom WorldStateDeterminer
//                        // For this test, we're demonstrating the mechanism works with withObjects()
//                        return baseExpression.evaluate(determineCondition)
//                    }
//                }
//            }
//        }

        val ap = IntegrationTestUtils.dummyAgentPlatform(
            logicalExpressionParser = prologParser
        )
        val agent = metadata as CoreAgent
        val agentProcess =
            ap.runAgentFrom(
                agent,
                ProcessOptions(plannerType = PlannerType.UTILITY),
                emptyMap(),
            )
        assertEquals(
            AgentProcessStatusCode.STUCK, agentProcess.status,
            "Should be stuck, not finished: status=${agentProcess.status}",
        )
        assertTrue(
            agentProcess.objects.any { it == Elephant("Zaboya", 30) },
            "Should have an elephant: blackboard=${agentProcess.objects}"
        )
        assertTrue(
            agentProcess.objects.any { it is Zoo },
            "Should have a zoo: blackboard=${agentProcess.objects}",
        )
    }

    @Test
    fun `verify Prolog condition`() {
        // Test that Prolog facts are correctly generated from domain objects
        val elephant = Elephant("Zaboya", 30)
        val converter = PrologFactConverter()
        val facts = converter.convertToFacts(elephant)

        // Verify the facts are generated correctly
        assertTrue(facts.any { it.contains("elephant_name") })
        assertTrue(facts.any { it.contains("elephant_age('Zaboya', 30)") })

        // Create a Prolog engine with these facts
        val engine = TuPrologEngine.create("")
            .assertFacts(facts)

        // Verify the query works
        assertTrue(engine.query("elephant_age('Zaboya', Age), Age > 20"))
        assertFalse(engine.query("elephant_age('Zaboya', Age), Age > 50"))

        // Now test with the actual action precondition
        val expression = PrologLogicalExpression(
            "elephant_age(Elephant, Age), Age > 20",
            TuPrologEngine.create("")
        )

        // Use withObjects to inject the elephant
        val result = expression.withObjects(elephant)
            .evaluate { ConditionDetermination.UNKNOWN }

        assertEquals(ConditionDetermination.TRUE, result)
    }

    @Test
    fun `rejects young elephant for zoo`() {
        // Test that the Prolog condition correctly rejects young elephants
        val youngElephant = Elephant("Dumbo", 15)
        val converter = PrologFactConverter()
        val facts = converter.convertToFacts(youngElephant)

        // Verify facts are generated
        assertTrue(facts.any { it.contains("elephant_age('Dumbo', 15)") })

        // Create engine with facts
        val engine = TuPrologEngine.create("")
            .assertFacts(facts)

        // Verify the query fails for young elephant
        assertFalse(engine.query("elephant_age('Dumbo', Age), Age > 20"))

        // Test with the expression
        val expression = PrologLogicalExpression(
            "elephant_age(Elephant, Age), Age > 20",
            TuPrologEngine.create("")
        )

        val result = expression.withObjects(youngElephant)
            .evaluate { ConditionDetermination.UNKNOWN }

        assertEquals(
            ConditionDetermination.FALSE, result,
            "Should be FALSE because elephant is only 15 years old"
        )
    }

//    @Test
//    fun `completes with single explicit satisfiable goal`() {
//        val reader = AgentMetadataReader()
//        val metadata =
//            reader.createAgentMetadata(
//                Utility2Actions1SatisfiableGoal()
//            )
//        assertNotNull(metadata)
//        assertEquals(2, metadata!!.actions.size)
//
//        val ap = IntegrationTestUtils.dummyAgentPlatform()
//        val agent = metadata as CoreAgent
//        val agentProcess =
//            ap.runAgentFrom(
//                agent,
//                ProcessOptions(
//                    plannerType = PlannerType.UTILITY,
//                ),
//                emptyMap(),
//            )
//        assertEquals(
//            AgentProcessStatusCode.COMPLETED, agentProcess.status,
//            "Should be completed: status=${agentProcess.status}",
//        )
//        assertTrue(
//            agentProcess.objects.any { it == PersonWithReverseTool("Kermit") },
//            "Should have a person: blackboard=${agentProcess.objects}",
//        )
//    }
//
//    @Test
//    fun `does not complete with single explicit unsatisfiable goal`() {
//        val reader = AgentMetadataReader()
//        val metadata =
//            reader.createAgentMetadata(
//                Utility2Actions1UnsatisfiableGoal()
//            )
//        assertNotNull(metadata)
//        assertEquals(3, metadata!!.actions.size)
//
//        val ap = IntegrationTestUtils.dummyAgentPlatform()
//        val agent = metadata as CoreAgent
//        val agentProcess =
//            ap.runAgentFrom(
//                agent,
//                ProcessOptions(
//                    plannerType = PlannerType.UTILITY,
//                ),
//                emptyMap(),
//            )
//        assertEquals(
//            AgentProcessStatusCode.STUCK, agentProcess.status,
//            "Should be stuck, not completed: status=${agentProcess.status}",
//        )
//        assertTrue(
//            agentProcess.objects.any { it == PersonWithReverseTool("Kermit") },
//            "Should have a person: blackboard=${agentProcess.objects}",
//        )
//    }
//
//    @Test
//    fun `accept void return and invoke two actions`() {
//        val reader = AgentMetadataReader()
//        val instance = Utility2Actions1VoidNoGoal()
//        val metadata =
//            reader.createAgentMetadata(
//                instance
//            )
//        assertNotNull(metadata)
//        assertEquals(2, metadata!!.actions.size)
//
//        val ap = IntegrationTestUtils.dummyAgentPlatform()
//        val agent = metadata as CoreAgent
//        val agentProcess =
//            ap.runAgentFrom(
//                agent,
//                ProcessOptions(plannerType = PlannerType.UTILITY),
//                emptyMap(),
//            )
//        assertEquals(
//            AgentProcessStatusCode.STUCK, agentProcess.status,
//            "Should be stuck, not finished: status=${agentProcess.status}",
//        )
//        assertTrue(
//            instance.invokedThing2,
//            "Should have invoked second method",
//        )
//
//    }
}
