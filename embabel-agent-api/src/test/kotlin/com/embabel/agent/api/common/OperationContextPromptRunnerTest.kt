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
package com.embabel.agent.api.common

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.annotation.LlmTool.Param
import com.embabel.agent.api.annotation.support.Wumpus
import com.embabel.agent.api.common.nested.support.PromptRunnerObjectCreator
import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Operation
import com.embabel.agent.experimental.primitive.Determination
import com.embabel.agent.support.Dog
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.StringTransformer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private val Tool.Result.content: String
    get() = when (this) {
        is Tool.Result.Text -> content
        is Tool.Result.WithArtifact -> content
        is Tool.Result.Error -> message
    }

class OperationContextPromptRunnerTest {

    private fun createOperationContextPromptRunnerWithDefaults(context: OperationContext): OperationContextPromptRunner {
        return OperationContextPromptRunner(
            context = context,
            llm = LlmOptions(),
            toolGroups = emptySet(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = false,
        )
    }

    @Nested
    inner class LlmOptionsTest {

        @Test
        fun `test change LlmOptions`() {
            val llm = LlmOptions.withModel("my-model").withTemperature(1.0)
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withLlm(llm)
            assertEquals(llm, ocpr.llm, "LlmOptions not set correctly")
        }
    }

    @Nested
    inner class ToolObject {

        @Test
        fun `test toolObject instance`() {
            val llm = LlmOptions.withModel("my-model").withTemperature(1.0)
            val wumpus = Wumpus("wumpuses-have-tools")
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withLlm(llm)
                .withToolObject(wumpus)
            assertEquals(1, ocpr.toolObjects.size, "Must have one tool object")
            assertEquals(wumpus, ocpr.toolObjects[0].objects[0], "Tool object instance not set correctly")
            assertEquals(
                StringTransformer.IDENTITY,
                ocpr.toolObjects[0].namingStrategy,
                "Tool object naming strategy not set correctly"
            )
        }

        @Test
        fun `test ToolObject instance with custom naming strategy`() {
            val llm = LlmOptions.withModel("my-model").withTemperature(1.0)
            val wumpus = Wumpus("wumpuses-have-tools")
            val namingStrategy = StringTransformer { it.replace("_", " ") }
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withLlm(llm)
                .withToolObject(ToolObject(wumpus).withNamingStrategy(namingStrategy))
            assertEquals(1, ocpr.toolObjects.size, "Must have one tool object")
            assertEquals(wumpus, ocpr.toolObjects[0].objects[0], "Tool object instance not set correctly")
            assertEquals(
                namingStrategy,
                ocpr.toolObjects[0].namingStrategy,
                "Tool object naming strategy not set correctly"
            )
        }

    }

    @Nested
    inner class PromptElements {

        @Test
        @Disabled("test not implemented yet")
        fun `test contextual prompt contributors`() {
        }

        @Test
        @Disabled("test not implemented yet")
        fun `test withPromptElements`() {
        }
    }

    @Nested
    inner class SystemPrompt {

        @Test
        fun `test withSystemPrompt`() {
            val systemPrompt = "You are a helpful assistant specialized in testing."
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withSystemPrompt(systemPrompt)

            assertEquals(1, ocpr.promptContributors.size, "Must have one prompt contributor for system prompt")
            assertEquals(systemPrompt, ocpr.promptContributors[0].contribution(), "System prompt not set correctly")
        }

        @Test
        fun `test withSystemPrompt with empty string`() {
            val systemPrompt = ""
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withSystemPrompt(systemPrompt)

            assertEquals(1, ocpr.promptContributors.size, "Must have one prompt contributor for system prompt")
            assertEquals(
                systemPrompt,
                ocpr.promptContributors[0].contribution(),
                "Empty system prompt not handled correctly"
            )
        }

        @Test
        fun `test withSystemPrompt with multiline content`() {
            val systemPrompt = """
                You are a helpful assistant.
                You should always:
                1. Be polite
                2. Be accurate
                3. Be concise
            """.trimIndent()

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withSystemPrompt(systemPrompt)

            assertEquals(1, ocpr.promptContributors.size, "Must have one prompt contributor for system prompt")
            assertEquals(
                systemPrompt,
                ocpr.promptContributors[0].contribution(),
                "Multiline system prompt not set correctly"
            )
        }

        @Test
        fun `test chaining multiple withSystemPrompt calls`() {
            val systemPrompt1 = "First system prompt"
            val systemPrompt2 = "Second system prompt"

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withSystemPrompt(systemPrompt1)
                .withSystemPrompt(systemPrompt2)

            assertEquals(2, ocpr.promptContributors.size, "Must have two prompt contributors")
            assertTrue(
                ocpr.promptContributors.any { it.contribution() == systemPrompt1 },
                "First system prompt not found"
            )
            assertTrue(
                ocpr.promptContributors.any { it.contribution() == systemPrompt2 },
                "Second system prompt not found"
            )
        }

    }

    @Nested
    inner class ToolGroups {

        @Test
        fun `test withToolGroups with set of strings`() {
            val toolGroups = setOf("math", "file", "web")
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withToolGroups(toolGroups)

            assertEquals(3, ocpr.toolGroups.size, "Must have three tool groups")
            assertTrue(ocpr.toolGroups.any { it.role == "math" }, "Math tool group not found")
            assertTrue(ocpr.toolGroups.any { it.role == "file" }, "File tool group not found")
            assertTrue(ocpr.toolGroups.any { it.role == "web" }, "Web tool group not found")
        }

        @Test
        fun `test withToolGroups with empty set`() {
            val toolGroups = emptySet<String>()
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withToolGroups(toolGroups)

            assertEquals(0, ocpr.toolGroups.size, "Must have no tool groups")
        }

        @Test
        fun `test withTools with varargs`() {
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withTools("math", "file", "web")

            assertEquals(3, ocpr.toolGroups.size, "Must have three tool groups")
            assertTrue(ocpr.toolGroups.any { it.role == "math" }, "Math tool group not found")
            assertTrue(ocpr.toolGroups.any { it.role == "file" }, "File tool group not found")
            assertTrue(ocpr.toolGroups.any { it.role == "web" }, "Web tool group not found")
        }

        @Test
        fun `test withTools with no arguments`() {
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withTools()

            assertEquals(0, ocpr.toolGroups.size, "Must have no tool groups when no args provided")
        }

        @Test
        fun `test withToolGroup single string`() {
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withToolGroup("math")

            assertEquals(1, ocpr.toolGroups.size, "Must have one tool group")
            assertEquals("math", ocpr.toolGroups.first().role, "Tool group name not set correctly")
        }
    }


    @Nested

    inner class Conditions {
        @Test
        fun `test evaluateCondition`() {
            val mockOperationContext = mockk<OperationContext>()
            every { mockOperationContext.processContext } returns mockk()
            every { mockOperationContext.processContext.agentProcess } returns mockk()
            every {
                mockOperationContext.processContext.createObject(
                    any(),
                    any(),
                    Determination::class.java,
                    any(),
                    null
                )
            } answers {
                val messages = firstArg<List<Message>>()
                val prompt = (messages[0] as UserMessage).content
                assertTrue(prompt.contains("Evaluate this condition"), "Prompt didn't contain evaluate: $prompt")
                Determination(
                    result = true,
                    confidence = 0.8,
                    explanation = "Mocked explanation"
                )
            }
            every { mockOperationContext.operation } returns mockk<Operation>(relaxed = true)

            val runner = OperationContextPromptRunner(
                context = mockOperationContext,
                llm = LlmOptions(),
                toolGroups = emptySet(),
                toolObjects = emptyList(),
                promptContributors = emptyList(),
                contextualPromptContributors = emptyList(),
                generateExamples = false,
            )

            val result = runner.evaluateCondition("condition", "context", 0.5)
            assertTrue(result)
        }
    }

    @Nested
    inner class ObjectCreator {

        @Test
        fun `test no examples`() {
            val duke = Dog("Duke")
            val context = FakeOperationContext()
            val pr = spyk(createOperationContextPromptRunnerWithDefaults(context))
            val um = slot<List<Message>>()
            every { pr.createObject(capture(um), Dog::class.java) } returns duke
            val result = pr
                .creating(Dog::class.java)
                .fromPrompt("foo bar")
            assertEquals(duke, result, "Dog instance not returned correctly")
            assertEquals(1, um.captured.size, "Must be one message")
            assertEquals(um.captured[0].content, "foo bar", "Example not included in prompt")
        }

        @Test
        fun `test no examples preserves interaction id`() {
            val duke = Dog("Duke")
            val context = FakeOperationContext()
            val pr = spyk(createOperationContextPromptRunnerWithDefaults(context).withId("iid"))
            val um = slot<List<Message>>()
            every { pr.createObject(capture(um), Dog::class.java) } returns duke
            val result = pr
                .creating(Dog::class.java)
                .fromPrompt("foo bar")
            assertEquals(duke, result, "Dog instance not returned correctly")
            assertEquals(1, um.captured.size, "Must be one message")
            assertEquals(um.captured[0].content, "foo bar", "Example not included in prompt")
        }

        @Test
        fun `test example`() {
            val context = FakeOperationContext()
            val pr = spyk(createOperationContextPromptRunnerWithDefaults(context))
            val pr1 = (pr
                .creating(Dog::class.java)
                .withExample("Good dog", Dog("Duke")) as PromptRunnerObjectCreator).promptRunner
            assertEquals(1, pr1.promptContributors.size, "Must be one contributor")
            val eg = pr1.promptContributors[0].contribution()
            assertTrue(eg.contains("Duke"), "Should include example dog name")
            assertTrue(eg.contains("Good dog"), "Should include example description")
            assertTrue(eg.contains("{"), "Should include JSON")
        }
    }


    @Nested
    inner class WithReferences {

        @Test
        fun `test withReference`() {
            val mockReference = mockk<LlmReference>()
            every { mockReference.name } returns "TestAPI"
            every { mockReference.description } returns "Test API"
            every { mockReference.toolPrefix() } returns "testapi"
            every { mockReference.notes() } returns "Test API documentation"
            every { mockReference.contribution() } returns "Reference: TestAPI\nDescription: Test API\nTool prefix: testapi\nNotes: Test API documentation"
            every { mockReference.toolObject() } returns ToolObject(mockReference)
            every { mockReference.tools() } returns emptyList()

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withReference(mockReference)

            assertEquals(1, ocpr.toolObjects.size, "Must have one tool object for reference")
            assertEquals(mockReference, ocpr.toolObjects[0].objects[0], "Reference not set correctly as tool object")
            assertEquals(1, ocpr.promptContributors.size, "Must have one prompt contributor for reference")
            assertEquals(
                mockReference,
                ocpr.promptContributors[0],
                "Reference not set correctly as prompt contributor"
            )

            // Test that a naming strategy is set (actual behavior may vary)
            assertNotNull(ocpr.toolObjects[0].namingStrategy, "Naming strategy should not be null")
        }

        @Test
        fun `test withReference with special characters in name`() {
            val mockReference = mockk<LlmReference>()
            every { mockReference.name } returns "Test-API@v2!"
            every { mockReference.description } returns "Test API v2"
            every { mockReference.toolPrefix() } returns "test-api_v2_"
            every { mockReference.notes() } returns "Test API v2 documentation"
            every { mockReference.contribution() } returns "Reference: Test-API@v2!\nDescription: Test API v2\nTool prefix: test-api_v2_\nNotes: Test API v2 documentation"
            every { mockReference.toolObject() } returns ToolObject(mockReference)
            every { mockReference.tools() } returns emptyList()

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withReference(mockReference)

            assertEquals(1, ocpr.toolObjects.size, "Must have one tool object for reference")

            // Test that a naming strategy is set for special characters
            assertNotNull(
                ocpr.toolObjects[0].namingStrategy,
                "Naming strategy should not be null even with special characters"
            )
        }


        @Test
        fun `test withReferences with multiple references`() {
            val mockReference1 = mockk<LlmReference>()
            every { mockReference1.name } returns "API1"
            every { mockReference1.description } returns "API 1"
            every { mockReference1.toolPrefix() } returns "api1"
            every { mockReference1.notes() } returns "API 1 documentation"
            every { mockReference1.contribution() } returns "Reference: API1\nDescription: API 1\nTool prefix: api1\nNotes: API 1 documentation"
            every { mockReference1.toolObject() } returns ToolObject(mockReference1)
            every { mockReference1.tools() } returns emptyList()

            val mockReference2 = mockk<LlmReference>()
            every { mockReference2.name } returns "API2"
            every { mockReference2.description } returns "API 2"
            every { mockReference2.toolPrefix() } returns "api2"
            every { mockReference2.notes() } returns "API 2 documentation"
            every { mockReference2.contribution() } returns "Reference: API2\nDescription: API 2\nTool prefix: api2\nNotes: API 2 documentation"
            every { mockReference2.toolObject() } returns ToolObject(mockReference2)
            every { mockReference2.tools() } returns emptyList()

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withReferences(listOf(mockReference1, mockReference2))

            assertEquals(2, ocpr.toolObjects.size, "Must have two tool objects for references")
            assertEquals(2, ocpr.promptContributors.size, "Must have two prompt contributors for references")

            assertTrue(
                ocpr.toolObjects.any { it.objects[0] == mockReference1 },
                "Reference 1 not found in tool objects"
            )
            assertTrue(
                ocpr.toolObjects.any { it.objects[0] == mockReference2 },
                "Reference 2 not found in tool objects"
            )
            assertTrue(
                ocpr.promptContributors.contains(mockReference1),
                "Reference 1 not found in prompt contributors"
            )
            assertTrue(
                ocpr.promptContributors.contains(mockReference2),
                "Reference 2 not found in prompt contributors"
            )
        }

        @Test
        fun `test withReferences with varargs`() {
            val mockReference1 = mockk<LlmReference>()
            every { mockReference1.name } returns "API1"
            every { mockReference1.description } returns "API 1"
            every { mockReference1.toolPrefix() } returns "api1"
            every { mockReference1.notes() } returns "API 1 documentation"
            every { mockReference1.contribution() } returns "Reference: API1\nDescription: API 1\nTool prefix: api1\nNotes: API 1 documentation"
            every { mockReference1.toolObject() } returns ToolObject(mockReference1)
            every { mockReference1.tools() } returns emptyList()

            val mockReference2 = mockk<LlmReference>()
            every { mockReference2.name } returns "API2"
            every { mockReference2.description } returns "API 2"
            every { mockReference2.toolPrefix() } returns "api2"
            every { mockReference2.notes() } returns "API 2 documentation"
            every { mockReference2.contribution() } returns "Reference: API2\nDescription: API 2\nTool prefix: api2\nNotes: API 2 documentation"
            every { mockReference2.toolObject() } returns ToolObject(mockReference2)
            every { mockReference2.tools() } returns emptyList()

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withReferences(mockReference1, mockReference2)

            assertEquals(2, ocpr.toolObjects.size, "Must have two tool objects for references")
            assertEquals(2, ocpr.promptContributors.size, "Must have two prompt contributors for references")
        }

        @Test
        fun `test combining withReference and withSystemPrompt`() {
            val mockReference = mockk<LlmReference>()
            every { mockReference.name } returns "TestAPI"
            every { mockReference.description } returns "Test API"
            every { mockReference.toolPrefix() } returns "testapi"
            every { mockReference.notes() } returns "Test API documentation"
            every { mockReference.contribution() } returns "Reference: TestAPI\nDescription: Test API\nTool prefix: testapi\nNotes: Test API documentation"
            every { mockReference.toolObject() } returns ToolObject(mockReference)
            every { mockReference.tools() } returns emptyList()

            val systemPrompt = "You are a helpful assistant."

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withReference(mockReference)
                .withSystemPrompt(systemPrompt)

            assertEquals(1, ocpr.toolObjects.size, "Must have one tool object for reference")
            assertEquals(
                2,
                ocpr.promptContributors.size,
                "Must have two prompt contributors (reference + system prompt)"
            )
            assertTrue(
                ocpr.promptContributors.contains(mockReference),
                "Reference not found in prompt contributors"
            )
            assertTrue(
                ocpr.promptContributors.any { it.contribution() == systemPrompt },
                "System prompt not found in prompt contributors"
            )
        }

        @Test
        fun `test withReference picks up tools from LlmReference tools method`() {
            val referenceTool = Tool.of(
                name = "reference_tool",
                description = "A tool from the reference",
            ) { _ ->
                Tool.Result.text("reference result")
            }

            val mockReference = mockk<LlmReference>()
            every { mockReference.name } returns "ToolsAPI"
            every { mockReference.description } returns "API with tools"
            every { mockReference.toolPrefix() } returns "toolsapi"
            every { mockReference.notes() } returns "API documentation"
            every { mockReference.contribution() } returns "Reference: ToolsAPI"
            every { mockReference.toolObject() } returns ToolObject(mockReference)
            every { mockReference.tools() } returns listOf(referenceTool)

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withReference(mockReference) as OperationContextPromptRunner

            // Verify tools from the reference are added to otherToolCallbacks
            val field = OperationContextPromptRunner::class.java.getDeclaredField("otherTools")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tools = field.get(ocpr) as List<Tool>

            assertEquals(1, tools.size, "Must have one tool callback from reference")
            assertEquals(
                "reference_tool",
                tools[0].definition.name,
                "Tool from reference not added correctly"
            )
            assertEquals("A tool from the reference", tools[0].definition.description)
        }

        @Test
        fun `test withReference picks up multiple tools from LlmReference`() {
            val tool1 = Tool.of("ref_tool1", "First reference tool") { _ -> Tool.Result.text("1") }
            val tool2 = Tool.of("ref_tool2", "Second reference tool") { _ -> Tool.Result.text("2") }

            val mockReference = mockk<LlmReference>()
            every { mockReference.name } returns "MultiToolAPI"
            every { mockReference.description } returns "API with multiple tools"
            every { mockReference.toolPrefix() } returns "multitoolapi"
            every { mockReference.notes() } returns "API documentation"
            every { mockReference.contribution() } returns "Reference: MultiToolAPI"
            every { mockReference.toolObject() } returns ToolObject(mockReference)
            every { mockReference.tools() } returns listOf(tool1, tool2)

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withReference(mockReference) as OperationContextPromptRunner

            val field = OperationContextPromptRunner::class.java.getDeclaredField("otherTools")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tools = field.get(ocpr) as List<Tool>

            assertEquals(2, tools.size, "Must have two tool callbacks from reference")
            val names = tools.map { it.definition.name }
            assertTrue(names.contains("ref_tool1"), "First tool not found")
            assertTrue(names.contains("ref_tool2"), "Second tool not found")
        }

        @Test
        fun `test withReferences aggregates tools from multiple references`() {
            val tool1 = Tool.of("api1_tool", "Tool from API1") { _ -> Tool.Result.text("1") }
            val tool2 = Tool.of("api2_tool", "Tool from API2") { _ -> Tool.Result.text("2") }

            val mockReference1 = mockk<LlmReference>()
            every { mockReference1.name } returns "API1"
            every { mockReference1.description } returns "API 1"
            every { mockReference1.toolPrefix() } returns "api1"
            every { mockReference1.notes() } returns "API 1 docs"
            every { mockReference1.contribution() } returns "Reference: API1"
            every { mockReference1.toolObject() } returns ToolObject(mockReference1)
            every { mockReference1.tools() } returns listOf(tool1)

            val mockReference2 = mockk<LlmReference>()
            every { mockReference2.name } returns "API2"
            every { mockReference2.description } returns "API 2"
            every { mockReference2.toolPrefix() } returns "api2"
            every { mockReference2.notes() } returns "API 2 docs"
            every { mockReference2.contribution() } returns "Reference: API2"
            every { mockReference2.toolObject() } returns ToolObject(mockReference2)
            every { mockReference2.tools() } returns listOf(tool2)

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withReferences(mockReference1, mockReference2) as OperationContextPromptRunner

            val field = OperationContextPromptRunner::class.java.getDeclaredField("otherTools")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tools = field.get(ocpr) as List<Tool>

            assertEquals(2, tools.size, "Must have tool callbacks from both references")
            val names = tools.map { it.definition.name }
            assertTrue(names.contains("api1_tool"), "Tool from API1 not found")
            assertTrue(names.contains("api2_tool"), "Tool from API2 not found")
        }
    }

    @Nested
    inner class WithToolTests {

        @Test
        fun `test withTool adds tool as Spring ToolCallback`() {
            val tool = Tool.of(
                name = "test_tool",
                description = "A test tool",
            ) { _ ->
                Tool.Result.text("result")
            }

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withTool(tool) as OperationContextPromptRunner

            // The tool should be added to otherToolCallbacks (accessed via reflection or by checking behavior)
            // We verify it was converted to a Spring ToolCallback by checking the tool definition
            val field = OperationContextPromptRunner::class.java.getDeclaredField("otherTools")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tools = field.get(ocpr) as List<Tool>

            assertEquals(1, tools.size, "Must have one tool callback")
            assertEquals("test_tool", tools[0].definition.name, "Tool name not converted correctly")
            assertEquals(
                "A test tool",
                tools[0].definition.description,
                "Tool description not converted correctly"
            )
        }

        @Test
        fun `test withTool converts Tool to Spring ToolCallback that is executable`() {
            var toolWasExecuted = false
            val tool = Tool.of(
                name = "executable_tool",
                description = "An executable tool",
            ) { _ ->
                toolWasExecuted = true
                Tool.Result.text("executed!")
            }

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withTool(tool) as OperationContextPromptRunner

            val field = OperationContextPromptRunner::class.java.getDeclaredField("otherTools")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tools = field.get(ocpr) as List<Tool>

            // Execute the tool callback
            val result = tools[0].call("{}")

            assertTrue(toolWasExecuted, "Tool should have been executed")
            assertEquals("executed!", result.content, "Tool result not returned correctly")
        }

        @Test
        fun `test withTools list adds multiple tools`() {
            val tool1 = Tool.of("tool1", "First tool") { _ -> Tool.Result.text("1") }
            val tool2 = Tool.of("tool2", "Second tool") { _ -> Tool.Result.text("2") }

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withTools(listOf(tool1, tool2)) as OperationContextPromptRunner

            val field = OperationContextPromptRunner::class.java.getDeclaredField("otherTools")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tools = field.get(ocpr) as List<Tool>

            assertEquals(2, tools.size, "Must have two tool callbacks")
            assertEquals("tool1", tools[0].definition.name)
            assertEquals("tool2", tools[1].definition.name)
        }

        @Test
        fun `test withFunctionTools varargs adds multiple tools`() {
            val tool1 = Tool.of("vararg_tool1", "First") { _ -> Tool.Result.text("1") }
            val tool2 = Tool.of("vararg_tool2", "Second") { _ -> Tool.Result.text("2") }
            val tool3 = Tool.of("vararg_tool3", "Third") { _ -> Tool.Result.text("3") }

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withFunctionTools(tool1, tool2, tool3) as OperationContextPromptRunner

            val field = OperationContextPromptRunner::class.java.getDeclaredField("otherTools")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tools = field.get(ocpr) as List<Tool>

            assertEquals(3, tools.size, "Must have three tool callbacks")
            val names = tools.map { it.definition.name }
            assertTrue(names.contains("vararg_tool1"))
            assertTrue(names.contains("vararg_tool2"))
            assertTrue(names.contains("vararg_tool3"))
        }

        @Test
        fun `test withTool with annotated method tools`() {
            class MyTools {
                @LlmTool(description = "Add two numbers")
                fun add(
                    @Param(description = "First number") a: Int,
                    @Param(description = "Second number") b: Int,
                ): Int = a + b
            }

            val sourceTools = Tool.fromInstance(MyTools())
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withTools(sourceTools) as OperationContextPromptRunner

            val field = OperationContextPromptRunner::class.java.getDeclaredField("otherTools")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tools = field.get(ocpr) as List<Tool>

            assertEquals(1, tools.size, "Must have one tool callback from annotated method")
            assertEquals("add", tools[0].definition.name)
            assertTrue(tools[0].definition.description!!.contains("Add two numbers"))

            // Verify it executes correctly
            val result = tools[0].call("""{"a": 5, "b": 3}""")
            assertEquals("8", result.content, "Tool should return sum as string")
        }

    }

}


// Create a simple mock implementation for testing
private class TestLlmReference(
    override val name: String,
    private val promptContribution: String,
) : LlmReference {
    override val description: String = "Test reference: $name"
    override fun notes(): String = promptContribution
}
