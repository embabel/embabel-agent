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
package com.embabel.agent.support

import com.embabel.agent.api.annotation.support.PersonWithReverseTool
import com.embabel.agent.api.channel.DevNullOutputChannel
import com.embabel.agent.api.common.Aggregation
import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.*
import com.embabel.agent.core.expression.LogicalExpression
import com.embabel.agent.core.expression.LogicalExpressionParser
import com.embabel.agent.core.support.BlackboardWorldStateDeterminer
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.plan.common.condition.ConditionDetermination
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

class FakeAndExpression(
    private val left: String,
    private val right: String,
) : LogicalExpression {
    override fun evaluate(blackboard: Blackboard): ConditionDetermination {
        val leftResult =
            blackboard.getCondition(left)?.let { ConditionDetermination(it) } ?: ConditionDetermination.UNKNOWN
        val rightResult =
            blackboard.getCondition(right)?.let { ConditionDetermination(it) } ?: ConditionDetermination.UNKNOWN

        return when {
            leftResult == ConditionDetermination.FALSE || rightResult == ConditionDetermination.FALSE ->
                ConditionDetermination.FALSE

            leftResult == ConditionDetermination.TRUE && rightResult == ConditionDetermination.TRUE ->
                ConditionDetermination.TRUE

            else -> ConditionDetermination.UNKNOWN
        }
    }
}

class FakeOrExpression(
    private val left: String,
    private val right: String,
) : LogicalExpression {
    override fun evaluate(blackboard: Blackboard): ConditionDetermination {
        val leftResult =
            blackboard.getCondition(left)?.let { ConditionDetermination(it) } ?: ConditionDetermination.UNKNOWN
        val rightResult =
            blackboard.getCondition(right)?.let { ConditionDetermination(it) } ?: ConditionDetermination.UNKNOWN

        return when {
            leftResult == ConditionDetermination.TRUE || rightResult == ConditionDetermination.TRUE ->
                ConditionDetermination.TRUE

            leftResult == ConditionDetermination.FALSE && rightResult == ConditionDetermination.FALSE ->
                ConditionDetermination.FALSE

            else -> ConditionDetermination.UNKNOWN
        }
    }
}

class FakeLogicalExpressionParser : LogicalExpressionParser {
    override fun parse(expression: String): LogicalExpression? {
        if (!expression.startsWith("expr:")) return null

        val content = expression.substringAfter("expr:")
        return when {
            " AND " in content -> {
                val parts = content.split(" AND ", limit = 2)
                FakeAndExpression(parts[0].trim(), parts[1].trim())
            }

            " OR " in content -> {
                val parts = content.split(" OR ", limit = 2)
                FakeOrExpression(parts[0].trim(), parts[1].trim())
            }

            else -> null
        }
    }
}

class BlackboardWorldStateDeterminerTest {

    val eventListener = EventSavingAgenticEventListener()
    val mockPlatformServices = mockk<PlatformServices>()

    init {
        every { mockPlatformServices.eventListener } returns eventListener
        every { mockPlatformServices.llmOperations } returns mockk()
        every { mockPlatformServices.outputChannel } returns DevNullOutputChannel
    }

    private fun createBlackboardWorldStateDeterminer(blackboard: Blackboard): BlackboardWorldStateDeterminer {
        val mockAgentProcess = mockk<AgentProcess>()
        every { mockAgentProcess.history } returns emptyList()
        every { mockAgentProcess.infoString(any()) } returns ""
        every { mockAgentProcess.getValue(any(), any()) } answers {
            blackboard.getValue(
                firstArg(),
                secondArg(),
                DataDictionary.fromDomainTypes(
                    "test",
                    blackboard.objects.map { JvmType(it.javaClass) }
                ),
            )
        }
        every { mockAgentProcess.get(any()) } answers {
            blackboard.get(firstArg())
        }
        every { mockAgentProcess.getCondition(any()) } answers {
            blackboard.getCondition(firstArg())
        }
        every { mockAgentProcess.agent } returns SimpleTestAgent
        val bsb = BlackboardWorldStateDeterminer(
            processContext = ProcessContext(
                platformServices = mockPlatformServices,
                agentProcess = mockAgentProcess,
                processOptions = ProcessOptions(),
            ),
            logicalExpressionParser = LogicalExpressionParser.EMPTY,
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

    @Nested
    inner class LogicalExpressionParserTests {

        private fun createBlackboardWorldStateDeterminerWithParser(
            blackboard: Blackboard,
            parser: LogicalExpressionParser,
        ): BlackboardWorldStateDeterminer {
            val mockAgentProcess = mockk<AgentProcess>()
            every { mockAgentProcess.history } returns emptyList()
            every { mockAgentProcess.infoString(any()) } returns ""
            every { mockAgentProcess.getValue(any(), any()) } answers {
                blackboard.getValue(
                    firstArg(),
                    secondArg(),
                    DataDictionary.fromDomainTypes(
                        "test",
                        blackboard.objects.map { JvmType(it.javaClass) }
                    )
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
                    processOptions = ProcessOptions(),
                ),
                logicalExpressionParser = parser,
            )

            every { mockAgentProcess.getCondition(any()) } answers {
                when (val determination = bsb.determineCondition(firstArg())) {
                    ConditionDetermination.TRUE -> true
                    ConditionDetermination.FALSE -> false
                    ConditionDetermination.UNKNOWN -> null
                }
            }

            return bsb
        }

        @Test
        fun `AND expression evaluates to TRUE when both conditions are TRUE`() {
            val blackboard = InMemoryBlackboard()
            blackboard["input"] = UserInput("xyz")
            blackboard["person"] = PersonWithReverseTool("Rod")

            val parser = FakeLogicalExpressionParser()
            val bsb = createBlackboardWorldStateDeterminerWithParser(blackboard, parser)

            val result = bsb.determineCondition("expr:it:UserInput AND it:PersonWithReverseTool")
            assertEquals(ConditionDetermination.TRUE, result)
        }

        @Test
        fun `AND expression evaluates to FALSE when one condition is FALSE`() {
            val blackboard = InMemoryBlackboard()
            blackboard["input"] = UserInput("xyz")

            val parser = FakeLogicalExpressionParser()
            val bsb = createBlackboardWorldStateDeterminerWithParser(blackboard, parser)

            val result = bsb.determineCondition("expr:it:UserInput AND it:PersonWithReverseTool")
            assertEquals(ConditionDetermination.FALSE, result)
        }

        @Test
        fun `OR expression evaluates to TRUE when one condition is TRUE`() {
            val blackboard = InMemoryBlackboard()
            blackboard["input"] = UserInput("xyz")

            val parser = FakeLogicalExpressionParser()
            val bsb = createBlackboardWorldStateDeterminerWithParser(blackboard, parser)

            val result = bsb.determineCondition("expr:it:UserInput OR it:PersonWithReverseTool")
            assertEquals(ConditionDetermination.TRUE, result)
        }

        @Test
        fun `OR expression evaluates to FALSE when both conditions are FALSE`() {
            val blackboard = InMemoryBlackboard()

            val parser = FakeLogicalExpressionParser()
            val bsb = createBlackboardWorldStateDeterminerWithParser(blackboard, parser)

            val result = bsb.determineCondition("expr:it:UserInput OR it:PersonWithReverseTool")
            assertEquals(ConditionDetermination.FALSE, result)
        }

        @Test
        fun `parser returns null for non-expr prefix`() {
            val parser = FakeLogicalExpressionParser()
            val result = parser.parse("it:UserInput")
            assertEquals(null, result)
        }
    }

}

fun <K, V> Map<K, V>.containsAll(other: Map<K, V>): Boolean {
    return other.all { (key, value) ->
        this.containsKey(key) && this[key] == value
    }
}
