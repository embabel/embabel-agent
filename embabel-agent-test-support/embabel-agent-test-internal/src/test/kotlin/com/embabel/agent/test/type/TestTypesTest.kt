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
package com.embabel.agent.test.type

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.core.JvmType
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.domain.Frog
import com.embabel.agent.test.dsl.SnakeMeal
import com.embabel.plan.WorldState
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Unit tests for the concrete helper behavior exposed by [testTypes.kt].
 */
class TestTypesTest {

    @Test
    fun `PersonWithReverseTool reverses its name`() {
        // Arrange
        val person = PersonWithReverseTool("Embabel")

        // Act
        val reversed = person.reverse()

        // Assert
        assertEquals("lebabmE", reversed)
    }

    @Test
    fun `Animal and Dog preserve name and implement organism hierarchy`() {
        // Arrange
        val animal = Animal("Generic")
        val dog = Dog("Fido")

        // Act

        // Assert
        assertEquals("Generic", animal.name)
        assertEquals("Fido", dog.name)
        assertIs<Organism>(animal)
        assertIs<Animal>(dog)
    }

    @Test
    fun `NoMethods is instantiable`() {
        // Arrange

        // Act
        val component = NoMethods()

        // Assert
        assertNotNull(component)
    }

    @Test
    fun `goal fixtures expose expected metadata`() {
        // Arrange
        val oneGoalOnly = OneGoalOnly()
        val richMetadata = OneGoalOnlyWithRichMetadata()
        val twoGoalsOnly = TwoGoalsOnly()
        val noConditions = NoConditions()
        val worldState = mockk<WorldState>()

        // Act

        // Assert
        assertEquals("thing1", oneGoalOnly.thing1.name)
        assertEquals("Thanks to Dr Seuss", oneGoalOnly.thing1.description)
        assertEquals(30.0, oneGoalOnly.thing1.value(worldState))
        assertEquals(PersonWithReverseTool::class.java, (oneGoalOnly.thing1.outputType as JvmType).clazz)

        assertEquals(setOf("foo", "bar"), richMetadata.thing1.tags)
        assertEquals(setOf("make me happy"), richMetadata.thing1.examples)
        assertEquals(30.0, richMetadata.thing1.value(worldState))

        assertEquals("Thanks to Dr Seuss", twoGoalsOnly.thing1.description)
        assertEquals("Thanks again to Dr Seuss", twoGoalsOnly.thing2.description)
        assertEquals("Create PersonWithReverseTool", twoGoalsOnly.thing1.name)
        assertEquals("Create PersonWithReverseTool", twoGoalsOnly.thing2.name)

        assertEquals("thing1", noConditions.g.name)
        assertEquals(30.0, noConditions.g.value(worldState))
    }

    @Test
    fun `ActionGoal creates person from user input`() {
        // Arrange
        val actionGoal = ActionGoal()

        // Act
        val person = actionGoal.toPerson(UserInput("Hamish"))

        // Assert
        assertEquals("Hamish", person.name)
    }

    @Test
    fun `deserializable interface fixture preserves content`() {
        // Arrange
        val value = MyInterfaceWithDeser("hello")

        // Act

        // Assert
        assertEquals("hello", value.content)
        assertIs<InterfaceWithDeser>(value)
    }

    @Test
    fun `TwoActionGoals transform person into frog`() {
        // Arrange
        val actions = TwoActionGoals()
        val input = UserInput("Kermit")

        // Act
        val person = actions.toPerson(input)
        val frog = actions.toFrog(person)

        // Assert
        assertEquals("Kermit", person.name)
        assertEquals(Frog("Kermit"), frog)
    }

    @Test
    fun `TwoConflictingActionGoals expose both person-producing methods`() {
        // Arrange
        val actions = TwoConflictingActionGoals()
        val input = UserInput("Rod")

        // Act
        val fromUserInput = actions.toPerson(input)
        val alsoFromUserInput = actions.alsoToPerson(input)

        // Assert
        assertEquals("Rod", fromUserInput.name)
        assertEquals("Rod", alsoFromUserInput.name)
    }

    @Test
    fun `operation and blackboard conditions return expected booleans`() {
        // Arrange
        val operationContextCondition = OneOperationContextConditionOnly()
        val combinedConditions = ConditionsFromBlackboard()

        // Act
        val operationConditionResult = operationContextCondition.condition1(mockk<OperationContext>())
        val rodResult = combinedConditions.condition1(PersonWithReverseTool("Rod"), Frog("Kermit"))
        val janeResult = combinedConditions.condition1(PersonWithReverseTool("Jane"), Frog("Kermit"))

        // Assert
        assertTrue(operationConditionResult)
        assertTrue(rodResult)
        assertFalse(janeResult)
    }

    @Test
    fun `ConditionFromBlackboard matches only Rod`() {
        // Arrange
        val condition = ConditionFromBlackboard()

        // Act

        // Assert
        assertTrue(condition.condition1(PersonWithReverseTool("Rod")))
        assertEquals(false, condition.condition1(PersonWithReverseTool("Jane")))
    }

    @Test
    fun `CustomNameConditionFromBlackboard matches only Rod`() {
        // Arrange
        val condition = CustomNameConditionFromBlackboard()

        // Act

        // Assert
        assertTrue(condition.`this is a weird name no one will see`(PersonWithReverseTool("Rod")))
        assertEquals(false, condition.`this is a weird name no one will see`(PersonWithReverseTool("Jane")))
    }

    @Test
    fun `direct transformer fixtures preserve user input content`() {
        // Arrange
        val oneTransformer = OneTransformerActionOnly()
        val payloadOnly = OneTransformerActionTakingPayloadOnly()
        val operationPayload = OneTransformerActionTakingOperationPayload()
        val referencedCondition = OneTransformerActionReferencingConditionByName()
        val userInput = UserInput("Hamish")

        // Act
        val direct = oneTransformer.toPerson(userInput)
        val withPayload = payloadOnly.toPerson(userInput, mockk<TransformationActionContext<UserInput, PersonWithReverseTool>>())
        val withActionContext = operationPayload.toPerson(userInput, mockk<ActionContext>())
        val withReferencedCondition = referencedCondition.toPerson(userInput)

        // Assert
        assertEquals("Hamish", direct.name)
        assertEquals("Hamish", withPayload.name)
        assertEquals("Hamish", withActionContext.name)
        assertEquals("Hamish", withReferencedCondition.name)
    }

    @Test
    fun `OneTransformerActionWithNullableParameter leaves content unchanged when SnakeMeal is absent`() {
        // Arrange
        val action = OneTransformerActionWithNullableParameter()

        // Act
        val person = action.toPerson(UserInput("Hamish"), null)

        // Assert
        assertEquals("Hamish", person.name)
    }

    @Test
    fun `OneTransformerActionWithNullableParameter appends tasty when SnakeMeal is present`() {
        // Arrange
        val action = OneTransformerActionWithNullableParameter()
        val snakeMeal = SnakeMeal(listOf(Frog("Kermit")))

        // Act
        val person = action.toPerson(UserInput("Hamish"), snakeMeal)

        // Assert
        assertEquals("Hamish and tasty!", person.name)
    }

    @Test
    fun `InternalDomainClasses maps internal input to internal output`() {
        // Arrange
        val agent = InternalDomainClasses()
        val input = InternalInput("secret")

        // Act
        val output = agent.oo(input)

        // Assert
        assertEquals("secret", output.content)
    }

    @Test
    fun `agent fixtures with extra arguments still map from user input`() {
        // Arrange
        val customNameAgent = AgentWithCustomName()
        val twoArgsAgent = AgentWithOneTransformerActionWith2ArgsOnly()
        val customInputBindings = OneTransformerActionWith2ArgsAndCustomInputBindings()
        val customOutputBinding = OneTransformerActionWith2ArgsAndCustomOutputBinding()
        val userInput = UserInput("Hamish")
        val task = Task("important")

        // Act
        val customNamePerson = customNameAgent.toPerson(userInput, task)
        val twoArgsPerson = twoArgsAgent.toPerson(userInput, task)
        val customInputPerson = customInputBindings.toPerson(userInput, task)
        val customOutputPerson = customOutputBinding.toPerson(userInput, task)

        // Assert
        assertEquals("Hamish", customNamePerson.name)
        assertEquals("Hamish", twoArgsPerson.name)
        assertEquals("Hamish", customInputPerson.name)
        assertEquals("Hamish", customOutputPerson.name)
    }

    @Test
    fun `TwoActuallyNonConflictingActionGoalsWithSameOutput returns same person instance`() {
        // Arrange
        val action = TwoActuallyNonConflictingActionGoalsWithSameOutput()
        val person = PersonWithReverseTool("Rod")

        // Act
        val result = action.alsoToPerson(person)

        // Assert
        assertSame(person, result)
    }

    @Test
    fun `FunnyTool and OneTransformerActionWith2Tools expose expected tool outputs`() {
        // Arrange
        val funnyTool = FunnyTool()
        val actionWithTools = OneTransformerActionWith2Tools()

        // Act
        val person = actionWithTools.toPerson(UserInput("Hamish"), Task("thing"))

        // Assert
        assertEquals("foobar", funnyTool.thing())
        assertEquals("foo", actionWithTools.toolWithoutArg())
        assertEquals("bar", actionWithTools.toolWithArg("London"))
        assertEquals("Hamish", person.name)
    }

    @Test
    fun `Wumpus tools expose expected strings`() {
        // Arrange
        val wumpus = Wumpus("Wumpy")

        // Act

        // Assert
        assertEquals("The wumpus's name is Wumpy", wumpus.toolWithoutArg())
        assertEquals("cave", wumpus.toolWithArg("cave"))
    }

    @Test
    fun `ToolMethodsOnDomainObject maps domain objects to expected outputs`() {
        // Arrange
        val actions = ToolMethodsOnDomainObject()

        // Act
        val person = actions.toPerson(Wumpus("Wumpy"))
        val frog = actions.toFrog(NoTools(1))

        // Assert
        assertEquals("Wumpy", person.name)
        assertEquals(Frog("Kermit"), frog)
    }

    @Test
    fun `ToolMethodsOnDomainObjects uses Wumpus name for frog`() {
        // Arrange
        val actions = ToolMethodsOnDomainObjects()

        // Act
        val frog = actions.toFrog(Wumpus("Wumpy"), PersonWithReverseTool("Hamish"))

        // Assert
        assertEquals(Frog("Wumpy"), frog)
    }

    @Test
    fun `DefineFlowTest and LocalAgentTest done methods return expected values`() {
        // Arrange
        val defineFlowTest = DefineFlowTest()
        val localAgentTest = LocalAgentTest()
        val snakeMeal = SnakeMeal(listOf(Frog("Kermit")))

        // Act
        val person = defineFlowTest.done(Frog("Kermit"))
        val sameSnakeMeal = localAgentTest.done(snakeMeal)

        // Assert
        assertEquals("Kermit", person.name)
        assertSame(snakeMeal, sameSnakeMeal)
    }

    @Test
    fun `UsesFrogOrDogSomeOf returns frog variant and converts frog to person`() {
        // Arrange
        val agent = UsesFrogOrDogSomeOf()

        // Act
        val someOf = agent.frogOrDog()
        val person = agent.toPerson(Frog("Kermit"))

        // Assert
        assertEquals(Frog("Kermit"), someOf.frog)
        assertNull(someOf.dog)
        assertEquals("Kermit", person.name)
    }
}
