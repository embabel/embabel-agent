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
package com.embabel.agent.test.unit

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.common.nested.ObjectCreationExample
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Test data classes - defined at top level to avoid Kotlin inner class restrictions
data class TestUserIntent(val type: String, val description: String)
data class TestChannelEditPlan(val channelId: Int, val newName: String)
data class TestComplexOutput(val field1: String, val field2: Int)

/**
 * Tests for [FakePromptRunner] and the fluent API support for unit testing.
 */
class FakePromptRunnerTest {

    @Nested
    inner class WithInteractionIdTests {

        @Test
        fun `withInteractionId sets the interaction ID`() {
            val context = FakeOperationContext.create()
            val interactionId = InteractionId("test-interaction-123")

            val promptRunner = context.ai().withDefaultLlm().withInteractionId(interactionId)

            assertTrue(promptRunner is FakePromptRunner)
            assertEquals(interactionId, (promptRunner as FakePromptRunner).interactionId)
        }

        @Test
        fun `withId convenience method sets the interaction ID`() {
            val context = FakeOperationContext.create()

            val promptRunner = context.ai().withDefaultLlm().withId("classify-intent")

            assertTrue(promptRunner is FakePromptRunner)
            assertEquals(InteractionId("classify-intent"), (promptRunner as FakePromptRunner).interactionId)
        }

        @Test
        fun `withInteractionId is immutable and returns new instance`() {
            val context = FakeOperationContext.create()
            val original = context.ai().withDefaultLlm()
            val interactionId = InteractionId("new-id")

            val modified = original.withInteractionId(interactionId)

            assertTrue(original is FakePromptRunner)
            assertTrue(modified is FakePromptRunner)
            assertNull((original as FakePromptRunner).interactionId)
            assertEquals(interactionId, (modified as FakePromptRunner).interactionId)
        }

        @Test
        fun `withId can be chained with other methods`() {
            val context = FakeOperationContext.create()
            val llmOptions = LlmOptions.withModel("gpt-4")

            val promptRunner = context.ai()
                .withLlm(llmOptions)
                .withId("chained-operation")
                .withSystemPrompt("You are a helpful assistant")

            assertTrue(promptRunner is FakePromptRunner)
            val fakeRunner = promptRunner as FakePromptRunner
            assertEquals(InteractionId("chained-operation"), fakeRunner.interactionId)
            assertEquals(llmOptions, fakeRunner.llm)
            assertEquals(1, fakeRunner.promptContributors.size)
        }

        @Test
        fun `interaction ID is used in LLM invocation`() {
            val context = FakeOperationContext.create()
            context.expectResponse("classified result")

            val result = context.ai()
                .withDefaultLlm()
                .withId("classify-intent")
                .createObject("Classify this intent", String::class.java)

            assertEquals("classified result", result)
            assertEquals(1, context.llmInvocations.size)
            assertEquals(InteractionId("classify-intent"), context.llmInvocations[0].interaction.id)
        }
    }

    @Nested
    inner class CreatingApiTests {

        @Test
        fun `creating returns ObjectCreator for output class`() {
            val context = FakeOperationContext.create()
            context.expectResponse(TestUserIntent("command", "Change channel name"))

            val creator = context.ai().withDefaultLlm().creating(TestUserIntent::class.java)

            assertNotNull(creator)
        }

        @Test
        fun `creating with fromPrompt creates object`() {
            val context = FakeOperationContext.create()
            val expectedIntent = TestUserIntent("command", "Change channel name")
            context.expectResponse(expectedIntent)

            val result = context.ai()
                .withDefaultLlm()
                .creating(TestUserIntent::class.java)
                .fromPrompt("Classify the user's intent")

            assertEquals(expectedIntent, result)
            assertEquals(1, context.llmInvocations.size)
        }

        @Test
        fun `creating with withId and fromPrompt works correctly`() {
            val context = FakeOperationContext.create()
            val expectedIntent = TestUserIntent("command", "Edit request")
            context.expectResponse(expectedIntent)

            val result = context.ai()
                .withDefaultLlm()
                .withId("classify-intent")
                .creating(TestUserIntent::class.java)
                .fromPrompt("Classify the user's intent")

            assertEquals(expectedIntent, result)
            assertEquals(1, context.llmInvocations.size)
            assertEquals(InteractionId("classify-intent"), context.llmInvocations[0].interaction.id)
        }

        @Test
        fun `creating with withExample adds example to prompt`() {
            val context = FakeOperationContext.create()
            val expectedPlan = TestChannelEditPlan(1, "Lead Vox")
            context.expectResponse(expectedPlan)

            val result = context.ai()
                .withDefaultLlm()
                .withId("analyze-edit")
                .creating(TestChannelEditPlan::class.java)
                .withExample("Good example", TestChannelEditPlan(2, "Rhythm"))
                .fromPrompt("Analyze the edit request")

            assertEquals(expectedPlan, result)
            assertEquals(1, context.llmInvocations.size)

            // Verify that example was added as a prompt contributor
            val promptContributors = context.llmInvocations[0].interaction.promptContributors
            assertTrue(promptContributors.isNotEmpty())
        }

        @Test
        fun `creating with multiple withExample calls adds all examples`() {
            val context = FakeOperationContext.create()
            val expectedPlan = TestChannelEditPlan(1, "Lead Vox")
            context.expectResponse(expectedPlan)

            val result = context.ai()
                .withDefaultLlm()
                .creating(TestChannelEditPlan::class.java)
                .withExample("First example", TestChannelEditPlan(1, "Bass"))
                .withExample("Second example", TestChannelEditPlan(2, "Drums"))
                .fromPrompt("Analyze the request")

            assertEquals(expectedPlan, result)
        }

        @Test
        fun `creating with LLM selection works`() {
            val context = FakeOperationContext.create()
            val llmOptions = LlmOptions.withModel("claude-3-opus")
            val expectedIntent = TestUserIntent("query", "Information request")
            context.expectResponse(expectedIntent)

            val result = context.ai()
                .withLlm(llmOptions)
                .withId("classify-with-opus")
                .creating(TestUserIntent::class.java)
                .fromPrompt("Classify the intent")

            assertEquals(expectedIntent, result)
            assertEquals(llmOptions, context.llmInvocations[0].interaction.llm)
        }

        @Test
        fun `creating with property filter works`() {
            val context = FakeOperationContext.create()
            val expectedIntent = TestUserIntent("command", "filtered")
            context.expectResponse(expectedIntent)

            val result = context.ai()
                .withDefaultLlm()
                .creating(TestUserIntent::class.java)
                .withProperties("type")
                .fromPrompt("Create with filtered properties")

            assertEquals(expectedIntent, result)
        }

        @Test
        fun `creating with withoutProperties works`() {
            val context = FakeOperationContext.create()
            val expectedIntent = TestUserIntent("command", "description hidden")
            context.expectResponse(expectedIntent)

            val result = context.ai()
                .withDefaultLlm()
                .creating(TestUserIntent::class.java)
                .withoutProperties("description")
                .fromPrompt("Create without description")

            assertEquals(expectedIntent, result)
        }

        @Test
        fun `creating with ObjectCreationExample data class works`() {
            val context = FakeOperationContext.create()
            val expectedPlan = TestChannelEditPlan(1, "Lead Vox")
            context.expectResponse(expectedPlan)

            val example = ObjectCreationExample(
                description = "Rename channel example",
                value = TestChannelEditPlan(2, "Rhythm")
            )

            val result = context.ai()
                .withDefaultLlm()
                .creating(TestChannelEditPlan::class.java)
                .withExample(example)
                .fromPrompt("Analyze the edit request")

            assertEquals(expectedPlan, result)
            assertEquals(1, context.llmInvocations.size)

            // Verify that example was added as a prompt contributor
            val promptContributors = context.llmInvocations[0].interaction.promptContributors
            assertTrue(promptContributors.isNotEmpty())
        }

        @Test
        fun `creating with withExamples vararg adds all examples`() {
            val context = FakeOperationContext.create()
            val expectedPlan = TestChannelEditPlan(1, "Lead Vox")
            context.expectResponse(expectedPlan)

            val example1 = ObjectCreationExample("First example", TestChannelEditPlan(1, "Bass"))
            val example2 = ObjectCreationExample("Second example", TestChannelEditPlan(2, "Drums"))
            val example3 = ObjectCreationExample("Third example", TestChannelEditPlan(3, "Keys"))

            val result = context.ai()
                .withDefaultLlm()
                .creating(TestChannelEditPlan::class.java)
                .withExamples(example1, example2, example3)
                .fromPrompt("Analyze the request")

            assertEquals(expectedPlan, result)
            assertEquals(1, context.llmInvocations.size)

            // Verify all examples were added as prompt contributors
            val promptContributors = context.llmInvocations[0].interaction.promptContributors
            assertTrue(promptContributors.size >= 3, "Expected at least 3 prompt contributors for examples")
        }

        @Test
        fun `creating with withExamples list adds all examples`() {
            val context = FakeOperationContext.create()
            val expectedPlan = TestChannelEditPlan(1, "Lead Vox")
            context.expectResponse(expectedPlan)

            val examples = listOf(
                ObjectCreationExample("Rename to Bass", TestChannelEditPlan(1, "Bass")),
                ObjectCreationExample("Rename to Drums", TestChannelEditPlan(2, "Drums")),
                ObjectCreationExample("Rename to Keys", TestChannelEditPlan(3, "Keys")),
                ObjectCreationExample("Rename to Vocals", TestChannelEditPlan(4, "Vocals"))
            )

            val result = context.ai()
                .withDefaultLlm()
                .creating(TestChannelEditPlan::class.java)
                .withExamples(examples)
                .fromPrompt("Analyze the request")

            assertEquals(expectedPlan, result)
            assertEquals(1, context.llmInvocations.size)

            // Verify all examples were added as prompt contributors
            val promptContributors = context.llmInvocations[0].interaction.promptContributors
            assertTrue(promptContributors.size >= 4, "Expected at least 4 prompt contributors for examples")
        }

        @Test
        fun `creating with mixed withExample and withExamples works`() {
            val context = FakeOperationContext.create()
            val expectedPlan = TestChannelEditPlan(1, "Lead Vox")
            context.expectResponse(expectedPlan)

            val examples = listOf(
                ObjectCreationExample("List example 1", TestChannelEditPlan(1, "Bass")),
                ObjectCreationExample("List example 2", TestChannelEditPlan(2, "Drums"))
            )

            val result = context.ai()
                .withDefaultLlm()
                .creating(TestChannelEditPlan::class.java)
                .withExample("Single example", TestChannelEditPlan(5, "Solo"))
                .withExamples(examples)
                .fromPrompt("Analyze the request")

            assertEquals(expectedPlan, result)
            assertEquals(1, context.llmInvocations.size)

            // Verify all examples were added (1 single + 2 from list)
            val promptContributors = context.llmInvocations[0].interaction.promptContributors
            assertTrue(promptContributors.size >= 3, "Expected at least 3 prompt contributors")
        }

        @Test
        fun `ObjectCreationExample has correct properties`() {
            val example = ObjectCreationExample(
                description = "Test description",
                value = TestUserIntent("command", "Test command")
            )

            assertEquals("Test description", example.description)
            assertEquals(TestUserIntent("command", "Test command"), example.value)
        }

        @Test
        fun `creating with fromMessages works`() {
            val context = FakeOperationContext.create()
            val expectedIntent = TestUserIntent("query", "Search request")
            context.expectResponse(expectedIntent)

            val messages = listOf(
                com.embabel.chat.UserMessage("First message"),
                com.embabel.chat.UserMessage("Second message")
            )

            val result = context.ai()
                .withDefaultLlm()
                .creating(TestUserIntent::class.java)
                .fromMessages(messages)

            assertEquals(expectedIntent, result)
            assertEquals(1, context.llmInvocations.size)
        }

        @Test
        fun `creating with property filter predicate works`() {
            val context = FakeOperationContext.create()
            val expectedIntent = TestUserIntent("command", "filtered")
            context.expectResponse(expectedIntent)

            val result = context.ai()
                .withDefaultLlm()
                .creating(TestUserIntent::class.java)
                .withPropertyFilter { it.startsWith("t") } // Only "type" property
                .fromPrompt("Create with filtered properties")

            assertEquals(expectedIntent, result)
        }
    }

    @Nested
    inner class ExpectResponseTests {

        @Test
        fun `expectResponse provides the expected response`() {
            val context = FakeOperationContext.create()
            context.expectResponse("expected text")

            val result = context.ai().withDefaultLlm().createObject("Generate text", String::class.java)

            assertEquals("expected text", result)
        }

        @Test
        fun `multiple expectResponse calls provide responses in order`() {
            val context = FakeOperationContext.create()
            context.expectResponse("first response")
            context.expectResponse("second response")

            val first = context.ai().withDefaultLlm().createObject("First prompt", String::class.java)
            val second = context.ai().withDefaultLlm().createObject("Second prompt", String::class.java)

            assertEquals("first response", first)
            assertEquals("second response", second)
        }

        @Test
        fun `expectResponse with null returns null for createObjectIfPossible`() {
            val context = FakeOperationContext.create()
            context.expectResponse(null)

            val result = context.ai().withDefaultLlm().createObjectIfPossible("May not work", String::class.java)

            assertNull(result)
        }

        @Test
        fun `expectResponse throws when type mismatch`() {
            val context = FakeOperationContext.create()
            context.expectResponse("string instead of int")

            assertThrows<IllegalStateException> {
                context.ai().withDefaultLlm().createObject("Get number", Int::class.javaObjectType)
            }
        }

        @Test
        fun `throws when not enough responses provided`() {
            val context = FakeOperationContext.create()
            context.expectResponse("only one")

            context.ai().withDefaultLlm().createObject("First", String::class.java)

            assertThrows<IllegalStateException> {
                context.ai().withDefaultLlm().createObject("Second", String::class.java)
            }
        }
    }

    @Nested
    inner class LlmInvocationTracking {

        @Test
        fun `llmInvocations tracks all calls`() {
            val context = FakeOperationContext.create()
            context.expectResponse("response1")
            context.expectResponse("response2")

            context.ai().withDefaultLlm().createObject("Prompt 1", String::class.java)
            context.ai().withDefaultLlm().createObject("Prompt 2", String::class.java)

            assertEquals(2, context.llmInvocations.size)
        }

        @Test
        fun `llmInvocations records the correct method type`() {
            val context = FakeOperationContext.create()
            context.expectResponse("created")
            context.expectResponse("maybe created")

            context.ai().withDefaultLlm().createObject("Create", String::class.java)
            context.ai().withDefaultLlm().createObjectIfPossible("Maybe create", String::class.java)

            assertEquals(Method.CREATE_OBJECT, context.llmInvocations[0].method)
            assertEquals(Method.CREATE_OBJECT_IF_POSSIBLE, context.llmInvocations[1].method)
        }

        @Test
        fun `llmInvocations captures messages`() {
            val context = FakeOperationContext.create()
            context.expectResponse("result")

            context.ai().withDefaultLlm().createObject("Test prompt", String::class.java)

            assertEquals(1, context.llmInvocations.size)
            val messages = context.llmInvocations[0].messages
            assertEquals(1, messages.size)
            assertTrue(messages[0].content.contains("Test prompt"))
        }

        @Test
        fun `prompt convenience property returns message content`() {
            val context = FakeOperationContext.create()
            context.expectResponse("result")

            context.ai().withDefaultLlm().createObject("Test prompt content", String::class.java)

            assertEquals(1, context.llmInvocations.size)
            val prompt = context.llmInvocations[0].prompt
            assertTrue(prompt.contains("Test prompt content"))
        }

        @Test
        fun `llmInvocations captures LLM options`() {
            val context = FakeOperationContext.create()
            val llmOptions = LlmOptions.withModel("test-model")
            context.expectResponse("result")

            context.ai()
                .withLlm(llmOptions)
                .createObject("Prompt", String::class.java)

            assertEquals(llmOptions, context.llmInvocations[0].interaction.llm)
        }
    }

    @Nested
    inner class FluentApiChainingTests {

        @Test
        fun `full fluent chain with all features works`() {
            val context = FakeOperationContext.create()
            val llmOptions = LlmOptions.withModel("advanced-model")
            val expectedOutput = TestComplexOutput("value", 42)
            context.expectResponse(expectedOutput)

            val result = context.ai()
                .withLlm(llmOptions)
                .withId("complex-operation")
                .withSystemPrompt("You are an expert")
                .creating(TestComplexOutput::class.java)
                .withExample("Example case", TestComplexOutput("example", 1))
                .fromPrompt("Create a complex output")

            assertEquals(expectedOutput, result)

            // Verify all configuration was applied
            val invocation = context.llmInvocations[0]
            assertEquals(llmOptions, invocation.interaction.llm)
            assertEquals(InteractionId("complex-operation"), invocation.interaction.id)
            assertTrue(invocation.interaction.promptContributors.size >= 2) // system prompt + example
        }

        @Test
        fun `can reuse PromptRunner for multiple operations`() {
            val context = FakeOperationContext.create()
            context.expectResponse("first")
            context.expectResponse("second")

            val baseRunner = context.ai()
                .withLlm(LlmOptions.withModel("shared-model"))
                .withSystemPrompt("Shared context")

            val first = baseRunner.withId("op-1").createObject("First op", String::class.java)
            val second = baseRunner.withId("op-2").createObject("Second op", String::class.java)

            assertEquals("first", first)
            assertEquals("second", second)

            // Both should share the LLM and system prompt
            assertEquals(
                context.llmInvocations[0].interaction.llm,
                context.llmInvocations[1].interaction.llm
            )
        }
    }

    @Nested
    inner class ToolObjectTests {

        @Test
        fun `withToolObject adds tool to interaction`() {
            val context = FakeOperationContext.create()
            val toolObject = TestToolObject()
            context.expectResponse("result")

            context.ai()
                .withDefaultLlm()
                .withToolObject(toolObject)
                .createObject("Use tool", String::class.java)

            val invocation = context.llmInvocations[0]
            assertTrue(invocation.interaction.tools.isNotEmpty())
        }
    }
}

// Tool object class defined outside the test class
class TestToolObject {
    @com.embabel.agent.api.annotation.LlmTool(description = "Test tool")
    fun testTool(): String = "tool result"
}
