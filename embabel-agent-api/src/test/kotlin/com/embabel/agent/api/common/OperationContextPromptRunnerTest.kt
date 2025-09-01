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
package com.embabel.agent.api.common

import com.embabel.agent.api.annotation.support.Wumpus
import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Operation
import com.embabel.agent.experimental.primitive.Determination
import com.embabel.agent.rag.RagRequest
import com.embabel.agent.rag.RagResponse
import com.embabel.agent.rag.RagService
import com.embabel.agent.rag.tools.RagOptions
import com.embabel.agent.rag.tools.RagServiceTools
import com.embabel.agent.spi.PlatformServices
import com.embabel.agent.support.Dog
import com.embabel.agent.testing.unit.FakeOperationContext
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.StringTransformer
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
    inner class Core {

        @Test
        fun `test change LlmOptions`() {
            val llm = LlmOptions.withModel("my-model").withTemperature(1.0)
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withLlm(llm)
            assertEquals(llm, ocpr.llm, "LlmOptions not set correctly")
        }

        @Test
        fun `test toolObject instance`() {
            val llm = LlmOptions.withModel("my-model").withTemperature(1.0)
            val wumpus = Wumpus("wumpuses-have-tools")
            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withLlm(llm)
                .withToolObject(wumpus)
            assertEquals(1, ocpr.toolObjects.size, "Must have one tool object")
            assertEquals(wumpus, ocpr.toolObjects[0].obj, "Tool object instance not set correctly")
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
            assertEquals(wumpus, ocpr.toolObjects[0].obj, "Tool object instance not set correctly")
            assertEquals(
                namingStrategy,
                ocpr.toolObjects[0].namingStrategy,
                "Tool object naming strategy not set correctly"
            )
        }

        @Test
        @Disabled("test not implemented yet")
        fun `test contextual prompt contributors`() {
        }

        @Test
        fun `test withReference`() {
            val mockReference = mockk<LlmReference>()
            every { mockReference.name } returns "TestAPI"
            every { mockReference.contribution() } returns "Test API documentation"

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withReference(mockReference)

            assertEquals(1, ocpr.toolObjects.size, "Must have one tool object for reference")
            assertEquals(mockReference, ocpr.toolObjects[0].obj, "Reference not set correctly as tool object")
            assertEquals(1, ocpr.promptContributors.size, "Must have one prompt contributor for reference")
            assertEquals(mockReference, ocpr.promptContributors[0], "Reference not set correctly as prompt contributor")

            // Test that a naming strategy is set (actual behavior may vary)
            assertNotNull(ocpr.toolObjects[0].namingStrategy, "Naming strategy should not be null")
        }

        @Test
        fun `test withReference with special characters in name`() {
            val mockReference = mockk<LlmReference>()
            every { mockReference.name } returns "Test-API@v2!"
            every { mockReference.contribution() } returns "Test API v2 documentation"

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
            every { mockReference1.contribution() } returns "API 1 documentation"

            val mockReference2 = mockk<LlmReference>()
            every { mockReference2.name } returns "API2"
            every { mockReference2.contribution() } returns "API 2 documentation"

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withReferences(listOf(mockReference1, mockReference2))

            assertEquals(2, ocpr.toolObjects.size, "Must have two tool objects for references")
            assertEquals(2, ocpr.promptContributors.size, "Must have two prompt contributors for references")

            assertTrue(ocpr.toolObjects.any { it.obj == mockReference1 }, "Reference 1 not found in tool objects")
            assertTrue(ocpr.toolObjects.any { it.obj == mockReference2 }, "Reference 2 not found in tool objects")
            assertTrue(ocpr.promptContributors.contains(mockReference1), "Reference 1 not found in prompt contributors")
            assertTrue(ocpr.promptContributors.contains(mockReference2), "Reference 2 not found in prompt contributors")
        }

        @Test
        fun `test withReferences with varargs`() {
            val mockReference1 = mockk<LlmReference>()
            every { mockReference1.name } returns "API1"
            every { mockReference1.contribution() } returns "API 1 documentation"

            val mockReference2 = mockk<LlmReference>()
            every { mockReference2.name } returns "API2"
            every { mockReference2.contribution() } returns "API 2 documentation"

            val ocpr = createOperationContextPromptRunnerWithDefaults(mockk<OperationContext>())
                .withReferences(mockReference1, mockReference2)

            assertEquals(2, ocpr.toolObjects.size, "Must have two tool objects for references")
            assertEquals(2, ocpr.promptContributors.size, "Must have two prompt contributors for references")
        }

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

        @Test
        fun `test combining withReference and withSystemPrompt`() {
            val mockReference = mockk<LlmReference>()
            every { mockReference.name } returns "TestAPI"
            every { mockReference.contribution() } returns "Test API documentation"

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
            assertTrue(ocpr.promptContributors.contains(mockReference), "Reference not found in prompt contributors")
            assertTrue(
                ocpr.promptContributors.any { it.contribution() == systemPrompt },
                "System prompt not found in prompt contributors"
            )
        }

        @Nested
        inner class WithRagToolsTests {

            @Test
            fun `test withRagTools with default options`() {
                val mockContext = mockk<OperationContext>(relaxed = true)
                val mockPlatformServices = mockk<PlatformServices>(relaxed = true)
                val mockAgentPlatform = mockk<AgentPlatform>(relaxed = true)
                val mockRagService = mockk<RagService>(relaxed = true)

                every { mockContext.agentPlatform() } returns mockAgentPlatform
                every { mockAgentPlatform.platformServices } returns mockPlatformServices
                every { mockPlatformServices.ragService } returns mockRagService

                val ragOptions = RagOptions()
                val ocpr = createOperationContextPromptRunnerWithDefaults(mockContext)
                    .withRagTools(ragOptions)

                assertEquals(1, ocpr.toolObjects.size, "Must have one tool object for RAG service")
                assertTrue(
                    ocpr.toolObjects.any { it.obj is RagServiceTools },
                    "RAG service tools not found in tool objects"
                )

                val ragTools = ocpr.toolObjects.first { it.obj is RagServiceTools }.obj as RagServiceTools
                assertEquals(mockRagService, ragTools.ragService, "RAG service not set correctly")
                assertEquals(ragOptions, ragTools.options, "RAG options not set correctly")
            }

            @Test
            fun `test withRagTools with custom options`() {
                val mockContext = mockk<OperationContext>(relaxed = true)
                val mockPlatformServices = mockk<PlatformServices>(relaxed = true)
                val mockAgentPlatform = mockk<AgentPlatform>(relaxed = true)
                val mockRagService = mockk<RagService>(relaxed = true)

                every { mockContext.agentPlatform() } returns mockAgentPlatform
                every { mockAgentPlatform.platformServices } returns mockPlatformServices
                every { mockPlatformServices.ragService } returns mockRagService

                val customRagOptions = RagOptions(
                    similarityThreshold = 0.9,
                    topK = 5,
                    labels = setOf("test-label")
                )

                val ocpr = createOperationContextPromptRunnerWithDefaults(mockContext)
                    .withRagTools(customRagOptions)

                assertEquals(1, ocpr.toolObjects.size, "Must have one tool object for RAG service")

                val ragTools = ocpr.toolObjects.first { it.obj is RagServiceTools }.obj as RagServiceTools
                assertEquals(customRagOptions, ragTools.options, "Custom RAG options not set correctly")
                assertEquals(
                    0.9,
                    ragTools.options.similarityThreshold.toDouble(),
                    0.001,
                    "Similarity threshold not set correctly"
                )
                assertEquals(5, ragTools.options.topK, "TopK not set correctly")
                assertEquals(setOf("test-label"), ragTools.options.labels, "Labels not set correctly")
            }

            @Test
            fun `test withRagTools throws error when added twice`() {
                val mockContext = mockk<OperationContext>(relaxed = true)
                val mockPlatformServices = mockk<PlatformServices>(relaxed = true)
                val mockAgentPlatform = mockk<AgentPlatform>(relaxed = true)
                val mockRagService = mockk<RagService>(relaxed = true)

                every { mockContext.agentPlatform() } returns mockAgentPlatform
                every { mockAgentPlatform.platformServices } returns mockPlatformServices
                every { mockPlatformServices.ragService } returns mockRagService

                val ragOptions1 = RagOptions()
                val ragOptions2 = RagOptions(topK = 10)

                val ocprWithOneRag = createOperationContextPromptRunnerWithDefaults(mockContext)
                    .withRagTools(ragOptions1)

                assertEquals(1, ocprWithOneRag.toolObjects.size, "Must have one tool object after first withRagTools")

                // Adding RAG tools twice should throw an error
                val exception = assertThrows(IllegalStateException::class.java) {
                    ocprWithOneRag.withRagTools(ragOptions2)
                }

                assertEquals("Cannot add Rag Tools twice", exception.message)
            }

            @Test
            fun `test withRagTools can be combined with other tools`() {
                val mockContext = mockk<OperationContext>(relaxed = true)
                val mockPlatformServices = mockk<PlatformServices>(relaxed = true)
                val mockAgentPlatform = mockk<AgentPlatform>(relaxed = true)
                val mockRagService = mockk<RagService>(relaxed = true)
                val wumpus = Wumpus("test-wumpus")

                every { mockContext.agentPlatform() } returns mockAgentPlatform
                every { mockAgentPlatform.platformServices } returns mockPlatformServices
                every { mockPlatformServices.ragService } returns mockRagService

                val ragOptions = RagOptions()
                val ocpr = createOperationContextPromptRunnerWithDefaults(mockContext)
                    .withRagTools(ragOptions)
                    .withToolObject(wumpus)
                    .withToolGroup("math")

                assertEquals(2, ocpr.toolObjects.size, "Must have two tool objects (RAG + Wumpus)")
                assertEquals(1, ocpr.toolGroups.size, "Must have one tool group")

                assertTrue(ocpr.toolObjects.any { it.obj is RagServiceTools }, "RAG service tools not found")
                assertTrue(ocpr.toolObjects.any { it.obj == wumpus }, "Wumpus not found in tool objects")
                assertTrue(ocpr.toolGroups.any { it.role == "math" }, "Math tool group not found")
            }

            @Test
            fun `test withRagTools order independence with other methods`() {
                val mockContext = mockk<OperationContext>(relaxed = true)
                val mockPlatformServices = mockk<PlatformServices>(relaxed = true)
                val mockAgentPlatform = mockk<AgentPlatform>(relaxed = true)
                val mockRagService = mockk<RagService>(relaxed = true)
                val systemPrompt = "You are a helpful RAG-enabled assistant."

                every { mockContext.agentPlatform() } returns mockAgentPlatform
                every { mockAgentPlatform.platformServices } returns mockPlatformServices
                every { mockPlatformServices.ragService } returns mockRagService

                val ragOptions = RagOptions()

                // Test RAG tools first, then system prompt
                val ocpr1 = createOperationContextPromptRunnerWithDefaults(mockContext)
                    .withRagTools(ragOptions)
                    .withSystemPrompt(systemPrompt)

                // Test system prompt first, then RAG tools
                val ocpr2 = createOperationContextPromptRunnerWithDefaults(mockContext)
                    .withSystemPrompt(systemPrompt)
                    .withRagTools(ragOptions)

                // Both should have the same end result
                assertEquals(ocpr1.toolObjects.size, ocpr2.toolObjects.size, "Tool object counts should be equal")
                assertEquals(
                    ocpr1.promptContributors.size,
                    ocpr2.promptContributors.size,
                    "Prompt contributor counts should be equal"
                )

                assertTrue(ocpr1.toolObjects.any { it.obj is RagServiceTools }, "OCPR1: RAG service tools not found")
                assertTrue(ocpr2.toolObjects.any { it.obj is RagServiceTools }, "OCPR2: RAG service tools not found")
                assertTrue(
                    ocpr1.promptContributors.any { it.contribution() == systemPrompt },
                    "OCPR1: System prompt not found"
                )
                assertTrue(
                    ocpr2.promptContributors.any { it.contribution() == systemPrompt },
                    "OCPR2: System prompt not found"
                )
            }

            @Test
            fun `test withRagTools preserves immutability`() {
                val mockContext = mockk<OperationContext>(relaxed = true)
                val mockPlatformServices = mockk<PlatformServices>(relaxed = true)
                val mockAgentPlatform = mockk<AgentPlatform>(relaxed = true)
                val mockRagService = mockk<RagService>(relaxed = true)

                every { mockContext.agentPlatform() } returns mockAgentPlatform
                every { mockAgentPlatform.platformServices } returns mockPlatformServices
                every { mockPlatformServices.ragService } returns mockRagService

                val ragOptions = RagOptions()
                val originalOcpr = createOperationContextPromptRunnerWithDefaults(mockContext)
                val newOcpr = originalOcpr.withRagTools(ragOptions)

                // Original should be unchanged
                assertEquals(0, originalOcpr.toolObjects.size, "Original OCPR should have no tool objects")

                // New should have the RAG tools
                assertEquals(1, newOcpr.toolObjects.size, "New OCPR should have RAG tool object")
                assertTrue(
                    newOcpr.toolObjects.any { it.obj is RagServiceTools },
                    "New OCPR should have RAG service tools"
                )

                // They should be different instances
                assertNotSame(originalOcpr, newOcpr, "Should create new instance")
            }

            @Test
            fun `test search invokes underlying RagService with correct arguments`() {
                val mockContext = mockk<OperationContext>(relaxed = true)
                val mockPlatformServices = mockk<PlatformServices>(relaxed = true)
                val mockAgentPlatform = mockk<AgentPlatform>(relaxed = true)
                val mockRagService = mockk<RagService>(relaxed = true)

                every { mockContext.agentPlatform() } returns mockAgentPlatform
                every { mockAgentPlatform.platformServices } returns mockPlatformServices
                every { mockPlatformServices.ragService } returns mockRagService

                val mockRagResponse = RagResponse(RagRequest("test"), "test-service", emptyList())
                every { mockRagService.search(any()) } returns mockRagResponse

                val customRagOptions = RagOptions(
                    similarityThreshold = 0.85,
                    topK = 12,
                    labels = setOf("custom-label", "another-label")
                )

                val ocpr = createOperationContextPromptRunnerWithDefaults(mockContext)
                    .withRagTools(customRagOptions)

                val ragTools = ocpr.toolObjects.first { it.obj is RagServiceTools }.obj as RagServiceTools

                // Call the search method
                val query = "test search query"
                ragTools.search(query)

                // Verify that the underlying RagService was called with the correct request
                verify {
                    mockRagService.search(match<RagRequest> { request ->
                        request.query == query &&
                                request.similarityThreshold == 0.85 &&
                                request.topK == 12 &&
                                request.labels == setOf("custom-label", "another-label")
                    })
                }
            }

        }

        @Test
        @Disabled("test not implemented yet")
        fun `test withPromptElements`() {
        }

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

    // Create a simple mock implementation for testing
    private class TestLlmReference(
        override val name: String,
        private val promptContribution: String,
    ) : LlmReference {
        override val description: String = "Test reference: $name"
        override fun contribution(): String = promptContribution
    }

}
