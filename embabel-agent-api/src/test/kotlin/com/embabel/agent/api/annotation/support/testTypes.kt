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

import com.embabel.agent.api.annotation.*
import com.embabel.agent.api.common.*
import com.embabel.agent.api.dsl.*
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.support.Dog
import com.embabel.common.ai.model.LlmOptions
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.ai.tool.annotation.Tool

data class PersonWithReverseTool(val name: String) {

    @Tool
    fun reverse() = name.reversed()

}

@AgentCapabilities
class NoMethods

@AgentCapabilities
class OneGoalOnly {

    val thing1 = Goal.createInstance(
        name = "thing1",
        description = "Thanks to Dr Seuss",
        type = PersonWithReverseTool::class.java,
    ).withValue(30.0)
}

@AgentCapabilities
class OneGoalOnlyWithRichMetadata {

    val thing1 = Goal.createInstance(
        name = "thing1",
        description = "This is a goal with rich metadata",
        type = PersonWithReverseTool::class.java,
        tags = setOf("foo", "bar"),
        examples = setOf("make me happy"),
    ).withValue(30.0)
}

@AgentCapabilities
class TwoGoalsOnly {

    val thing1 = Goal.createInstance(
        description = "Thanks to Dr Seuss",
        type = PersonWithReverseTool::class.java,
    )
    val thing2 = Goal.createInstance(
        description = "Thanks again to Dr Seuss",
        type = PersonWithReverseTool::class.java,
    )
}

@AgentCapabilities
class ActionGoal {

    @Action
    @AchievesGoal(description = "Creating a person")
    fun toPerson(userInput: UserInput): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

interface InterfaceWithNoDeser {
    val content: String
}

@AgentCapabilities
class InvalidActionNoDeserializationInInterfaceGoal {

    @Action
    @AchievesGoal(description = "Creating a weird thing")
    fun createWeirdThing(userInput: UserInput): InterfaceWithNoDeser {
        TODO()
    }

}

@JsonDeserialize(`as` = MyInterfaceWithDeser::class)
interface InterfaceWithDeser {
    val content: String
}

data class MyInterfaceWithDeser(
    override val content: String,
) : InterfaceWithDeser

@AgentCapabilities
class ValidActionWithDeserializationInInterfaceGoal {

    @Action
    @AchievesGoal(description = "Creating a weird thing")
    fun createWeirdThing(userInput: UserInput): InterfaceWithDeser {
        TODO()
    }

}

@AgentCapabilities
class TwoActionGoals {

    @Action
    @AchievesGoal(description = "Creating a person")
    fun toPerson(userInput: UserInput): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

    @Action
    @AchievesGoal(description = "Creating a frog")
    fun toFrog(person: PersonWithReverseTool): Frog {
        return Frog(person.name)
    }

}

@AgentCapabilities
class TwoActuallyNonConflictingActionGoalsWithSameOutput {

    @Action
    @AchievesGoal(description = "Creating a person")
    fun toPerson(userInput: UserInput): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

    @Action
    @AchievesGoal(description = "Also to person")
    fun alsoToPerson(person: PersonWithReverseTool): PersonWithReverseTool {
        return person
    }

}

@AgentCapabilities
class TwoConflictingActionGoals {

    @Action
    @AchievesGoal(description = "Creating a person")
    fun toPerson(userInput: UserInput): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

    @Action
    @AchievesGoal(description = "Also to person")
    fun alsoToPerson(userInput: UserInput): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

@AgentCapabilities
class NoConditions {

    // A goal makes it legal
    val g = Goal.createInstance(
        name = "thing1",
        description = "Thanks to Dr Seuss",
        type = PersonWithReverseTool::class.java,
    ).withValue(30.0)

}

@AgentCapabilities
class OneOperationContextConditionOnly {

    @Condition(cost = .5)
    fun condition1(operationContext: OperationContext): Boolean {
        return true
    }

}

@AgentCapabilities
class OneOperationContextAiOnly {

    @Condition(cost = .5)
    fun condition1(ai: Ai): Boolean {
        return true
    }

}

@AgentCapabilities
class ConditionFromBlackboard {

    @Condition
    fun condition1(person: PersonWithReverseTool): Boolean {
        return person.name == "Rod"
    }

}

@AgentCapabilities
class CustomNameConditionFromBlackboard {

    @Condition(name = "condition1")
    fun `this is a weird name no one will see`(person: PersonWithReverseTool): Boolean {
        return person.name == "Rod"
    }

}

@AgentCapabilities
class ConditionsFromBlackboard {

    @Condition
    fun condition1(
        person: PersonWithReverseTool,
        frog: Frog,
    ): Boolean {
        return person.name == "Rod"
    }

}

@Agent(description = "one transformer action only")
class OneTransformerActionOnly {

    @Action(cost = 500.0)
    fun toPerson(userInput: UserInput): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

@AgentCapabilities
class OneTransformerActionWithNullableParameter {

    @Action(cost = 500.0)
    fun toPerson(
        userInput: UserInput,
        person: SnakeMeal?,
    ): PersonWithReverseTool {
        var content = userInput.content
        if (person != null) {
            content += " and tasty!"
        }
        return PersonWithReverseTool(content)
    }

}

internal data class InternalInput(val content: String)
internal data class InternalOutput(val content: String)

@Agent(description = "Package visible domain classes")
class InternalDomainClasses {

    @Action(cost = 500.0)
    internal fun oo(internalInput: InternalInput): InternalOutput {
        return InternalOutput(internalInput.content)
    }

}

@AgentCapabilities
class OneTransformerActionTakingPayloadOnly {

    @Action(cost = 500.0)
    fun toPerson(
        userInput: UserInput,
        payload: TransformationActionContext<UserInput, PersonWithReverseTool>,
    ): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

@AgentCapabilities
class OneTransformerActionTakingOperationPayload {

    @Action(cost = 500.0)
    fun toPerson(
        userInput: UserInput,
        payload: ActionContext,
    ): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

@AgentCapabilities
class OneTransformerActionReferencingConditionByName {

    @Action(pre = ["condition1"])
    fun toPerson(userInput: UserInput): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

@AgentCapabilities
class OneTransformerActionWithCustomToolGroupOnly {

    @Action(cost = 500.0, toolGroups = ["magic"])
    fun toPerson(userInput: UserInput): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

@Agent(description = "thing")
class OneTransformerActionTakingInterfaceWithCustomToolGroupOnly {

    @AchievesGoal(description = "Creating a frog")
    @Action(cost = 500.0, toolGroups = ["magic"])
    fun toPerson(person: PersonWithReverseTool): Frog {
        return Frog(person.name)
    }

}

@Agent(description = "thing")
class OneTransformerActionTakingInterfaceWithExpectationCustomToolGroupOnly {

    @AchievesGoal(description = "Creating a frog")
    @Action(cost = 500.0, toolGroups = ["magic"])
    fun toPerson(
        person: PersonWithReverseTool,
        context: OperationContext,
    ): Frog {
        val pr = context.promptRunner()
        assertEquals(setOf(ToolGroupRequirement("magic")), pr.toolGroups.toSet())
//        assertFalse(pr.toolCallbacks.isEmpty(), "ToolCallbacks should be expanded")
        return Frog(person.name)
    }

}

@Agent(description = "thing")
class OneTransformerActionTakingInterfaceWithExpectationCustomToolGroupRequirementOnly {

    @AchievesGoal(description = "Creating a frog")
    @Action(cost = 500.0, toolGroups = ["frogs"], toolGroupRequirements = [ToolGroup("magic")])
    fun toPerson(
        person: PersonWithReverseTool,
        context: OperationContext,
    ): Frog {
        val pr = context.promptRunner()
        assertEquals(setOf(ToolGroupRequirement("magic"), ToolGroupRequirement("frogs")), pr.toolGroups.toSet())
//        assertFalse(pr.toolCallbacks.isEmpty(), "ToolCallbacks should be expanded")
        return Frog(person.name)
    }

}

data class Task(
    val what: String,
)

@Agent(
    name = "myAgentWithCustomName",
    provider = "magic",
    version = "1.1.1",
    description = "one transformer action only",
)
class AgentWithCustomName {

    @Action(cost = 500.0)
    fun toPerson(
        userInput: UserInput,
        task: Task,
    ): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

@Agent(
    description = "one transformer action only",
    opaque = true,
)
class OpaqueAgent {

    @Action(cost = 500.0)
    fun toPerson(
        userInput: UserInput,
        task: Task,
    ): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}


@Agent(
    description = "one transformer action only",
)
class AgentWithOneTransformerActionWith2ArgsOnly {

    @Action(cost = 500.0)
    fun toPerson(
        userInput: UserInput,
        task: Task,
    ): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

@Agent(
    description = "one transformer action only with ai",
)
class AgentWithOneTransformerActionWith2ArgsOnlyAndAiParameter {

    @Action(cost = 500.0)
    fun toPerson(
        userInput: UserInput,
        task: Task,
        ai: Ai,
    ): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

@Agent(
    description = "one transformer action only with OperationContext",
)
class AgentWithOneTransformerActionWith2ArgsOnlyAndOperationContextParameter {

    @Action(cost = 500.0)
    fun toPerson(
        userInput: UserInput,
        task: Task,
        context: OperationContext,
    ): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

@AgentCapabilities
class OneTransformerActionWith2ArgsAndCustomInputBindings {

    @Action
    fun toPerson(
        @RequireNameMatch userInput: UserInput,
        @RequireNameMatch task: Task,
    ): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

@AgentCapabilities
class OneTransformerActionWith2ArgsAndCustomOutputBinding {

    @Action(outputBinding = "person")
    fun toPerson(
        userInput: UserInput,
        task: Task,
    ): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

}

@AgentCapabilities
class OnePromptActionOnly(
) {

    val llm = LlmOptions.withModel("magical").withTemperature(.7)

    @Action(cost = 500.0)
    fun toPersonWithPrompt(
        userInput: UserInput,
        context: OperationContext,
    ): PersonWithReverseTool {
        return context.ai().withLlm(llm).createObject("Generated prompt for ${userInput.content}")
    }

}

@AgentCapabilities
class AwaitableOne(
) {

    @Action(cost = 500.0)
    fun waitForPersonConfirmation(userInput: UserInput): PersonWithReverseTool {
        return waitFor(
            ConfirmationRequest(
                payload = PersonWithReverseTool(userInput.content),
                message = "Is this dude the right person?",
            )
        )
    }

}

@AgentCapabilities
class Combined {

    val planner = Goal.createInstance(
        description = "Create a person",
        type = PersonWithReverseTool::class.java,
    ).withValue(30.0)

    // Can reuse this or inject
    val magicalLlm = LlmOptions.withModel("magical").withTemperature(1.7)

    @Condition(cost = .5)
    fun condition1(processContext: ProcessContext): Boolean {
        return true
    }

    @Action
    fun toPerson(userInput: UserInput): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

    @Action(cost = 500.0)
    fun toPersonWithPrompt(
        userInput: UserInput,
        context: OperationContext,
    ): PersonWithReverseTool {
        return context.ai().withLlm(magicalLlm).createObject("Generated prompt for ${userInput.content}")
    }

    @Tool
    fun weatherService(location: String) =
        "The weather in $location is ${listOf("sunny", "raining", "foggy").random()}"


}

@AgentCapabilities
class OnePromptActionWithToolOnly(
) {

    @Action(cost = 500.0)
    fun toPersonWithPrompt(
        userInput: UserInput,
        operationContext: OperationContext,
    ): PersonWithReverseTool {

        return operationContext.ai().withDefaultLlm() createObject
                "Generated prompt for ${userInput.content}"
    }

    @Tool
    fun thing(): String {
        return "foobar"
    }

}

@AgentCapabilities
class FromPersonUsesDomainObjectTools {

    @Action
    fun fromPerson(
        person: PersonWithReverseTool,
        context: OperationContext,
    ): UserInput {
        return context.ai().withDefaultLlm().createObject("Create a UserInput")
    }
}

@AgentCapabilities
class FromPersonUsesDomainObjectToolsViaActionContext {

    @Action
    fun fromPerson(
        person: PersonWithReverseTool,
        context: ActionContext,
    ): UserInput {
        return context.promptRunner().createObject("Create a UserInput")
    }
}

@AgentCapabilities
class FromPersonUsesDomainObjectToolsViaExecutingOperationContext {

    @Action
    fun fromPerson(
        person: PersonWithReverseTool,
        context: ExecutingOperationContext,
    ): UserInput {
        return context.promptRunner().createObject("Create a UserInput")
    }
}

@AgentCapabilities
class FromPersonUsesObjectToolsViaUsing {

    @Action
    fun fromPerson(
        person: PersonWithReverseTool,
        context: ActionContext,
    ): UserInput {
        return context.promptRunner(toolObjects = listOf(ToolObject(FunnyTool()))).createObject("Create a UserInput")
    }
}

@AgentCapabilities
class FromPersonUsesObjectToolsViaUsingWithRenaming {

    @Action
    fun fromPerson(
        person: PersonWithReverseTool,
        context: OperationContext,
    ): UserInput {
        return context.promptRunner()
            .withToolObject(
                ToolObject(
                    FunnyTool(),
                    namingStrategy = { "_$it" },
                )
            ).createObject("Create a UserInput")
    }
}

@AgentCapabilities
class FromPersonUsesObjectToolsViaUsingWithFilter {

    @Action
    fun fromPerson(
        person: PersonWithReverseTool,
        context: OperationContext,
    ): UserInput {
        return context.ai().withDefaultLlm()
            .withToolObject(
                ToolObject(FunnyTool()).withNamingStrategy { "_$it" }.withFilter { false },
            ).createObject("Create a UserInput")
    }
}

@AgentCapabilities
class FromPersonUsesObjectToolsViaContext {

    @Action
    fun fromPerson(
        person: PersonWithReverseTool,
        context: ActionContext,
    ): UserInput {
        return context.promptRunner(toolObjects = listOf(ToolObject(FunnyTool()))).createObject("Create a UserInput")
    }
}

@AgentCapabilities
class FromPersonUsesObjectToolsViaAi {

    @Action
    fun fromPerson(
        person: PersonWithReverseTool,
        ai: Ai,
    ): UserInput {
        return ai.withDefaultLlm()
            .withToolObjects(ToolObject(FunnyTool()))
            .createObject("Create a UserInput")
    }
}

@AgentCapabilities
class FromPersonUsesObjectToolsViaContextWithRenaming {

    @Action
    fun fromPerson(
        person: PersonWithReverseTool,
        context: ActionContext,
    ): UserInput {
        return context.promptRunner().withToolObject(
            ToolObject(
                FunnyTool(),
                namingStrategy = { "_$it" }),
        ).createObject("Create a UserInput")
    }
}

class FunnyTool {
    @Tool
    fun thing(): String {
        return "foobar"
    }
}

@AgentCapabilities
class OneTransformerActionWith2Tools {

    @Action
    fun toPerson(
        @RequireNameMatch userInput: UserInput,
        @RequireNameMatch task: Task,
    ): PersonWithReverseTool {
        return PersonWithReverseTool(userInput.content)
    }

    @Tool
    fun toolWithoutArg(): String = "foo"

    @Tool
    fun toolWithArg(location: String) = "bar"

}

@AgentCapabilities
class ToolMethodsOnDomainObject {

    @Action
    fun toPerson(
        wumpty: Wumpus,
    ): PersonWithReverseTool {
        return PersonWithReverseTool(wumpty.name)
    }

    @Action
    fun toFrog(
        noTools: NoTools,
    ): Frog {
        return Frog("Kermit")
    }

}

class Wumpus(val name: String) {

    @Tool
    fun toolWithoutArg(): String = "The wumpus's name is $name"

    @Tool
    fun toolWithArg(location: String) = location
}

@AgentCapabilities
class ToolMethodsOnDomainObjects {

    @Action
    fun toFrog(
        wumpty: Wumpus,
        person: PersonWithReverseTool,
    ): Frog {
        return Frog(wumpty.name)
    }

}

data class NoTools(val x: Int)


@Agent(description = "define flow")
class DefineFlowTest {

    @Action
    fun toFrog(
        userInput: UserInput,
        context: TransformationActionContext<UserInput, PersonWithReverseTool>,
    ): Frog {
        return chain<UserInput, PersonWithReverseTool, Frog>(
            { PersonWithReverseTool(it.input.content) },
            { Frog(it.input.name) },
        ).asSubProcess(context)
    }

    @AchievesGoal(description = "Creating a person")
    @Action
    fun done(frog: Frog): PersonWithReverseTool {
        return PersonWithReverseTool(frog.name)
    }
}

@Agent(description = "local agent")
class LocalAgentTest {

    @Action
    fun toDeadPerson(
        userInput: UserInput,
        context: TransformationActionContext<UserInput, SnakeMeal>,
    ): SnakeMeal {
        return runAgent<UserInput, SnakeMeal>(evenMoreEvilWizard(), context)
    }

    @AchievesGoal(description = "Eating a person")
    @Action
    fun done(person: SnakeMeal): SnakeMeal {
        return person
    }
}

data class FrogOrDog(
    val frog: Frog? = null,
    val dog: Dog? = null,
) : SomeOf

@Agent(description = "thing")
class UsesFrogOrDogSomeOf {

    @Action
    fun frogOrDog(): FrogOrDog {
        return FrogOrDog(frog = Frog("Kermit"))
    }

    @AchievesGoal(description = "Creating a prince from a frog")
    @Action
    fun toPerson(frog: Frog): PersonWithReverseTool {
        return PersonWithReverseTool(frog.name)
    }

}
