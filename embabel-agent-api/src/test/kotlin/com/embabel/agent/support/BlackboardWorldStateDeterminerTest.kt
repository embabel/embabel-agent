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
package com.embabel.agent.support

import com.embabel.agent.api.annotation.support.PersonWithReverseTool
import com.embabel.agent.api.common.Aggregation
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.BlackboardWorldStateDeterminer
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.PlatformServices
import com.embabel.agent.testing.common.EventSavingAgenticEventListener
import com.embabel.plan.goap.ConditionDetermination
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

data class AllOfTheAbove(
    val userInput: UserInput,
    val person: PersonWithReverseTool,
) : Aggregation

val SimpleTestAgent = agent("SimpleTest", description = "Simple test agent") {
    transformation<UserInput, PersonWithReverseTool>(name = "thing") {
        PersonWithReverseTool(name = "Rod")
    }

    transformation<AllOfTheAbove, PersonWithReverseTool>(name = "reverse-name") {
        PersonWithReverseTool(it.input.person.name.reversed())

    }

    goal(name = "done", description = "done", satisfiedBy = PersonWithReverseTool::class)
}

interface Fancy

data class FancyPerson(
    val name: String,
) : Fancy

val InterfaceTestAgent = agent("SimpleTest", description = "Simple test agent") {
    transformation<UserInput, FancyPerson>(name = "thing") {
        FancyPerson(name = "Rod")
    }

    transformation<AllOfTheAbove, FancyPerson>(name = "reverse-name") {
        FancyPerson(it.input.person.name.reversed())
    }

    goal(name = "done", description = "done", satisfiedBy = FancyPerson::class)
}

class BlackboardWorldStateDeterminerTest {

    val eventListener = EventSavingAgenticEventListener()
    val mockPlatformServices = mockk<PlatformServices>()

    init {
        every { mockPlatformServices.eventListener } returns eventListener
        every { mockPlatformServices.llmOperations } returns mockk()
        every { mockPlatformServices.outputChannel } returns mockk()
    }

    private fun createBlackboardWorldStateDeterminer(blackboard: Blackboard): BlackboardWorldStateDeterminer {
        val mockAgentProcess = mockk<AgentProcess>()
        every { mockAgentProcess.history } returns emptyList()
        every { mockAgentProcess.infoString(any()) } returns ""
        every { mockAgentProcess.getValue(any(), any(), any()) } answers {
            blackboard.getValue(
                firstArg(),
                secondArg(),
                thirdArg(),
            )
        }
        every { mockAgentProcess.get(any()) } answers {
            blackboard.get(firstArg())
        }
        every { mockAgentProcess.agent } returns SimpleTestAgent
        val bsb = BlackboardWorldStateDeterminer(
            processContext = ProcessContext(
                platformServices = mockPlatformServices,
                agentProcess = mockAgentProcess,
                processOptions = ProcessOptions()
            )
        )
        return bsb
    }

    @Nested
    inner class Worlds {

        @Test
        fun `negative world`() {
            val blackboard = InMemoryBlackboard()
            val bsb = createBlackboardWorldStateDeterminer(blackboard)
            val worldState = bsb.determineWorldState().state
            assertTrue(
                worldState.containsAll(
                    mapOf(
                        "it:${UserInput::class.qualifiedName}" to ConditionDetermination.FALSE,
                        "it:${PersonWithReverseTool::class.qualifiedName}" to ConditionDetermination.FALSE,
                    )
                ),
                "World state must use qualified names",
            )

        }

        @Test
        fun `one element world`() {
            val blackboard = InMemoryBlackboard()

            val bsb = createBlackboardWorldStateDeterminer(blackboard)

            blackboard += UserInput("xyz")
            val worldState = bsb.determineWorldState().state
            assertTrue(
                worldState.containsAll(
                    mapOf(
                        "it:${UserInput::class.qualifiedName}" to ConditionDetermination.TRUE,
                        "it:${PersonWithReverseTool::class.qualifiedName}" to ConditionDetermination.FALSE,
                    )
                ),
                "World state must use qualified names"
            )
        }

        @Test
        fun `activated megazord`() {
            val blackboard = InMemoryBlackboard()

            val bsb = createBlackboardWorldStateDeterminer(blackboard)

            blackboard["input"] = UserInput("xyz")
            blackboard["person"] = PersonWithReverseTool("Rod")
            val worldState = bsb.determineWorldState()
            assertTrue(
                worldState.state.containsAll(
                    mapOf(
                        "it:${UserInput::class.qualifiedName}" to ConditionDetermination.TRUE,
                        "it:${PersonWithReverseTool::class.qualifiedName}" to ConditionDetermination.TRUE,
                    )
                ),
                "World state must use qualified names",
            )
            val action = SimpleTestAgent.actions.single { it.name == "reverse-name" }
            assertTrue(
                action.isAchievable(worldState)
            )
        }
    }

    @Nested
    inner class TypeChecks {

        @Test
        fun `exact type match with simple name`() {
            val blackboard = InMemoryBlackboard()
            val bsb = createBlackboardWorldStateDeterminer(blackboard)

            blackboard["input"] = UserInput("xyz")
            blackboard["person"] = PersonWithReverseTool("Rod")
            val pc = bsb.determineCondition("it:${PersonWithReverseTool::class.simpleName}")
            assertEquals(ConditionDetermination.TRUE, pc)
        }

        @Test
        fun `subclass match`() {
            val blackboard = InMemoryBlackboard()
            val bsb = createBlackboardWorldStateDeterminer(blackboard)

            blackboard["input"] = UserInput("xyz")
            blackboard["person"] = FancyPerson("Rod")
            val pc = bsb.determineCondition("it:${FancyPerson::class.simpleName}")
            assertEquals(ConditionDetermination.FALSE, bsb.determineCondition("it:Person"))
            assertEquals(ConditionDetermination.TRUE, pc)
            assertEquals(
                ConditionDetermination.TRUE, bsb.determineCondition("it:${Fancy::class.simpleName}"),
                "Should match against interface",
            )
        }


        @Test
        fun `exact type match with fqn`() {
            val blackboard = InMemoryBlackboard()
            val bsb = createBlackboardWorldStateDeterminer(blackboard)

            blackboard["input"] = UserInput("xyz")
            blackboard["person"] = PersonWithReverseTool("Rod")
            assertEquals(
                ConditionDetermination.TRUE,
                bsb.determineCondition("it:${PersonWithReverseTool::class.qualifiedName}"),
                "Should match against fully qualified name",
            )
        }
    }

    @Nested
    inner class MapValue {

        @Test
        fun `failed match by name`() {
            val blackboard = InMemoryBlackboard()
            val bsb = createBlackboardWorldStateDeterminer(blackboard)

            blackboard["story"] = UserInput("xyz")
            assertEquals(
                ConditionDetermination.FALSE,
                bsb.determineCondition("story:Story"),
                "Should not match against non map",
            )
        }

        @Test
        fun `match by name`() {
            val blackboard = InMemoryBlackboard()
            val bsb = createBlackboardWorldStateDeterminer(blackboard)

            blackboard["story"] = mapOf("content " to "xyz")
            assertEquals(
                ConditionDetermination.TRUE,
                bsb.determineCondition("story:Story"),
                "Should match against map with name",
            )
        }
    }

}

fun <K, V> Map<K, V>.containsAll(other: Map<K, V>): Boolean {
    return other.all { (key, value) ->
        this.containsKey(key) && this[key] == value
    }
}
