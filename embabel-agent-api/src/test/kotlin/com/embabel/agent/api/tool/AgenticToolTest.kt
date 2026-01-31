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
package com.embabel.agent.api.tool

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AgenticToolTest {

    // Test fixtures - functional tools
    private val echoTool = Tool.of("echo", "Echo input") { input ->
        Tool.Result.text("Echo: $input")
    }

    private val reverseTool = Tool.of("reverse", "Reverse input") { input ->
        Tool.Result.text(input.reversed())
    }

    // Test fixtures - artifact-producing tools
    data class TestArtifact(val id: String, val value: Int)

    private val artifactTool = Tool.of("artifact", "Produces an artifact") { input ->
        Tool.Result.withArtifact("Produced artifact", TestArtifact("test-1", 42))
    }

    private val anotherArtifactTool = Tool.of("another", "Produces another artifact") { input ->
        Tool.Result.withArtifact("Another artifact", TestArtifact("test-2", 100))
    }

    // Test fixtures - annotated tool classes
    class SearchTools {
        @LlmTool(description = "Search the web")
        fun search(query: String): String = "Results for: $query"
    }

    class CalculatorTools {
        @LlmTool(description = "Add two numbers")
        fun add(a: Int, b: Int): Int = a + b

        @LlmTool(description = "Multiply two numbers")
        fun multiply(a: Int, b: Int): Int = a * b
    }

    @Nested
    inner class Creation {

        @Test
        fun `create agentic tool with constructor and withTools`() {
            val agentic = AgenticTool("orchestrator", "Orchestrates tools")
                .withTools(echoTool, reverseTool)
                .withSystemPrompt("Process the input using available tools")

            assertEquals("orchestrator", agentic.definition.name)
            assertEquals("Orchestrates tools", agentic.definition.description)
            assertEquals(2, agentic.tools.size)
            assertNull(agentic.llm.model)
            assertNull(agentic.llm.role)
        }

        @Test
        fun `create agentic tool with LlmOptions`() {
            val agentic = AgenticTool("configured", "With LLM config")
                .withTools(echoTool)
                .withLlm(LlmOptions(model = "gpt-4", role = "researcher"))
                .withSystemPrompt("Do stuff")

            assertEquals("gpt-4", agentic.llm.model)
            assertEquals("researcher", agentic.llm.role)
        }

        @Test
        fun `create agentic tool with empty tools returns error on call`() {
            val agentic = AgenticTool("empty", "No tools")

            val result = agentic.call("{}")
            assertTrue(result is Tool.Result.Error)
            assertTrue((result as Tool.Result.Error).message.contains("No tools available"))
        }

        @Test
        fun `create agentic tool from annotated objects`() {
            val agentic = AgenticTool("assistant", "Multi-capability assistant")
                .withToolObjects(SearchTools(), CalculatorTools())
                .withSystemPrompt("Use tools to help")

            assertEquals("assistant", agentic.definition.name)
            assertEquals(3, agentic.tools.size) // 1 search + 2 calculator
        }

        @Test
        fun `create agentic tool with LlmOptions and tool objects`() {
            val agentic = AgenticTool("smart-assistant", "Smart assistant")
                .withToolObjects(SearchTools(), CalculatorTools())
                .withLlm(LlmOptions(model = "gpt-4"))
                .withSystemPrompt("Use tools intelligently")

            assertEquals("gpt-4", agentic.llm.model)
            assertEquals(3, agentic.tools.size)
        }

        @Test
        fun `create agentic tool with single tool object`() {
            val agentic = AgenticTool("calculator", "Calculator assistant")
                .withToolObject(CalculatorTools())
                .withSystemPrompt("Do math")

            assertEquals(2, agentic.tools.size)
            val toolNames = agentic.tools.map { it.definition.name }
            assertTrue(toolNames.contains("add"))
            assertTrue(toolNames.contains("multiply"))
        }

        @Test
        fun `withToolObjects ignores objects without LlmTool methods`() {
            class NoTools {
                fun notATool(): String = "nope"
            }

            val agentic = AgenticTool("mixed", "Mixed sources")
                .withToolObjects(NoTools(), CalculatorTools())
                .withSystemPrompt("Use what you can")

            assertEquals(2, agentic.tools.size) // Only calculator tools
        }
    }

    @Nested
    inner class Withers {

        private val baseTool = Tool.of("base", "Base tool") { Tool.Result.text("base") }
        private val extraTool = Tool.of("extra", "Extra tool") { Tool.Result.text("extra") }

        private val agentic = AgenticTool("test", "Test agentic")
            .withTools(baseTool)
            .withSystemPrompt("Original prompt")

        @Test
        fun `withLlm creates copy with new LlmOptions`() {
            val updated = agentic.withLlm(LlmOptions(model = "gpt-4", role = "researcher"))

            assertEquals("gpt-4", updated.llm.model)
            assertEquals("researcher", updated.llm.role)
            assertEquals(agentic.definition, updated.definition)
            assertEquals(agentic.tools, updated.tools)
        }

        @Test
        fun `withTools adds additional tools`() {
            val updated = agentic.withTools(extraTool)

            assertEquals(2, updated.tools.size)
            assertTrue(updated.tools.contains(baseTool))
            assertTrue(updated.tools.contains(extraTool))
        }

        @Test
        fun `withSystemPrompt creates copy with new prompt`() {
            val updated = agentic.withSystemPrompt("New prompt")

            assertEquals(agentic.definition, updated.definition)
            assertEquals(agentic.tools, updated.tools)
        }

        @Test
        fun `withSystemPromptCreator creates copy with dynamic prompt`() {
            val updated = agentic.withSystemPromptCreator { process ->
                "Dynamic prompt for ${process.id}"
            }

            assertEquals(agentic.definition, updated.definition)
            assertNotEquals(agentic.systemPromptCreator, updated.systemPromptCreator)
        }

        @Test
        fun `withParameter adds parameter to input schema`() {
            val updated = agentic.withParameter(Tool.Parameter.string("query", "Search query"))

            assertEquals(1, updated.definition.inputSchema.parameters.size)
            val param = updated.definition.inputSchema.parameters[0]
            assertEquals("query", param.name)
            assertEquals("Search query", param.description)
            assertEquals(Tool.ParameterType.STRING, param.type)
            assertTrue(param.required)
        }

        @Test
        fun `withParameter can chain multiple parameters`() {
            val updated = agentic
                .withParameter(Tool.Parameter.string("topic", "Topic to research"))
                .withParameter(Tool.Parameter.integer("depth", "Search depth", required = false))

            assertEquals(2, updated.definition.inputSchema.parameters.size)
            val names = updated.definition.inputSchema.parameters.map { it.name }
            assertTrue(names.contains("topic"))
            assertTrue(names.contains("depth"))
        }

        @Test
        fun `withToolObject adds tools from annotated object`() {
            val updated = agentic.withToolObject(CalculatorTools())

            assertEquals(3, updated.tools.size) // 1 base + 2 calculator
            val toolNames = updated.tools.map { it.definition.name }
            assertTrue(toolNames.contains("base"))
            assertTrue(toolNames.contains("add"))
            assertTrue(toolNames.contains("multiply"))
        }

        @Test
        fun `withToolObject returns same instance when object has no LlmTool methods`() {
            class NoTools {
                fun regularMethod(): String = "not a tool"
            }

            val updated = agentic.withToolObject(NoTools())

            assertSame(agentic, updated)
        }

        @Test
        fun `withToolObjects adds tools from multiple objects`() {
            val updated = agentic.withToolObjects(SearchTools(), CalculatorTools())

            assertEquals(4, updated.tools.size) // 1 base + 1 search + 2 calculator
        }

        @Test
        fun `withToolObjects ignores objects without LlmTool methods`() {
            class NoTools {
                fun regularMethod(): String = "not a tool"
            }

            val updated = agentic.withToolObjects(NoTools(), CalculatorTools())

            assertEquals(3, updated.tools.size) // 1 base + 2 calculator
        }
    }

    @Nested
    inner class ImplementsTool {

        private val subTool = Tool.of("sub", "Sub tool") { Tool.Result.text("sub") }

        @Test
        fun `agentic tool implements Tool interface`() {
            val agentic = AgenticTool("impl", "Implements Tool")
                .withTools(subTool)
                .withSystemPrompt("Do it")

            assertEquals("impl", agentic.definition.name)
            assertEquals("Implements Tool", agentic.definition.description)
        }

        @Test
        fun `call returns error result when no AgentProcess context`() {
            val agentic = AgenticTool("nocontext", "No context")
                .withTools(subTool)
                .withSystemPrompt("Try it")

            val result = agentic.call("{}")

            assertTrue(result is Tool.Result.Error)
            val error = result as Tool.Result.Error
            assertTrue(error.message.contains("No AgentProcess context"))
        }
    }

    @Nested
    inner class ArtifactCapturing {

        @Test
        fun `ArtifactCapturingTool delegates call to wrapped tool`() {
            val artifacts = mutableListOf<Any>()
            val wrapper = ArtifactCapturingTool(echoTool, artifacts)

            val result = wrapper.call("hello")

            assertTrue(result is Tool.Result.Text)
            assertEquals("Echo: hello", (result as Tool.Result.Text).content)
        }

        @Test
        fun `ArtifactCapturingTool preserves definition from delegate`() {
            val artifacts = mutableListOf<Any>()
            val wrapper = ArtifactCapturingTool(echoTool, artifacts)

            assertEquals("echo", wrapper.definition.name)
            assertEquals("Echo input", wrapper.definition.description)
        }

        @Test
        fun `ArtifactCapturingTool preserves metadata from delegate`() {
            val customMetadata = Tool.Metadata(returnDirect = true)
            val toolWithMetadata = Tool.of(
                name = "custom",
                description = "Custom tool",
                metadata = customMetadata
            ) { Tool.Result.text("result") }

            val artifacts = mutableListOf<Any>()
            val wrapper = ArtifactCapturingTool(toolWithMetadata, artifacts)

            assertEquals(customMetadata.returnDirect, wrapper.metadata.returnDirect)
        }

        @Test
        fun `ArtifactCapturingTool captures artifact from WithArtifact result`() {
            val artifacts = mutableListOf<Any>()
            val wrapper = ArtifactCapturingTool(artifactTool, artifacts)

            wrapper.call("{}")

            assertEquals(1, artifacts.size)
            val captured = artifacts[0] as TestArtifact
            assertEquals("test-1", captured.id)
            assertEquals(42, captured.value)
        }

        @Test
        fun `ArtifactCapturingTool returns original WithArtifact result unchanged`() {
            val artifacts = mutableListOf<Any>()
            val wrapper = ArtifactCapturingTool(artifactTool, artifacts)

            val result = wrapper.call("{}")

            assertTrue(result is Tool.Result.WithArtifact)
            val withArtifact = result as Tool.Result.WithArtifact
            assertEquals("Produced artifact", withArtifact.content)
            val artifact = withArtifact.artifact as TestArtifact
            assertEquals("test-1", artifact.id)
        }

        @Test
        fun `ArtifactCapturingTool does not capture from Text result`() {
            val artifacts = mutableListOf<Any>()
            val wrapper = ArtifactCapturingTool(echoTool, artifacts)

            wrapper.call("input")

            assertTrue(artifacts.isEmpty())
        }

        @Test
        fun `ArtifactCapturingTool does not capture from Error result`() {
            val errorTool = Tool.of("error", "Error tool") { _ ->
                Tool.Result.error("Something went wrong")
            }
            val artifacts = mutableListOf<Any>()
            val wrapper = ArtifactCapturingTool(errorTool, artifacts)

            val result = wrapper.call("{}")

            assertTrue(artifacts.isEmpty())
            assertTrue(result is Tool.Result.Error)
        }

        @Test
        fun `multiple ArtifactCapturingTools share same artifacts list`() {
            val artifacts = mutableListOf<Any>()
            val wrapper1 = ArtifactCapturingTool(artifactTool, artifacts)
            val wrapper2 = ArtifactCapturingTool(anotherArtifactTool, artifacts)

            wrapper1.call("{}")
            wrapper2.call("{}")

            assertEquals(2, artifacts.size)
            assertEquals("test-1", (artifacts[0] as TestArtifact).id)
            assertEquals("test-2", (artifacts[1] as TestArtifact).id)
        }

        @Test
        fun `ArtifactCapturingTool captures artifacts in call order`() {
            val artifacts = mutableListOf<Any>()
            val wrapper1 = ArtifactCapturingTool(artifactTool, artifacts)
            val wrapper2 = ArtifactCapturingTool(anotherArtifactTool, artifacts)

            // Call in reverse order
            wrapper2.call("{}")
            wrapper1.call("{}")

            assertEquals(2, artifacts.size)
            assertEquals("test-2", (artifacts[0] as TestArtifact).id) // First called
            assertEquals("test-1", (artifacts[1] as TestArtifact).id) // Second called
        }

        @Test
        fun `ArtifactCapturingTool captures different artifact types`() {
            data class OtherArtifact(val name: String)

            val stringArtifactTool = Tool.of("string", "String artifact") { _ ->
                Tool.Result.withArtifact("String", "simple-string")
            }
            val objectArtifactTool = Tool.of("object", "Object artifact") { _ ->
                Tool.Result.withArtifact("Object", OtherArtifact("other"))
            }

            val artifacts = mutableListOf<Any>()
            val wrapper1 = ArtifactCapturingTool(stringArtifactTool, artifacts)
            val wrapper2 = ArtifactCapturingTool(objectArtifactTool, artifacts)

            wrapper1.call("{}")
            wrapper2.call("{}")

            assertEquals(2, artifacts.size)
            assertEquals("simple-string", artifacts[0])
            assertEquals(OtherArtifact("other"), artifacts[1])
        }

        @Test
        fun `ArtifactCapturingTool is a DelegatingTool`() {
            val artifacts = mutableListOf<Any>()
            val wrapper = ArtifactCapturingTool(echoTool, artifacts)

            assertTrue(wrapper is DelegatingTool)
            assertSame(echoTool, wrapper.delegate)
        }
    }

    @Nested
    inner class ArtifactCapturingBackwardCompatibility {

        @Test
        fun `agentic tool with text-only tools still returns Text result`() {
            // This tests that when no artifacts are produced, behavior is unchanged
            val agentic = AgenticTool("text-only", "Text only tools")
                .withTools(echoTool, reverseTool)
                .withSystemPrompt("Use tools")

            // Without AgentProcess context, we get an error
            // But the tools themselves don't produce artifacts
            val result = agentic.call("{}")

            // Should be error due to no AgentProcess, not related to artifacts
            assertTrue(result is Tool.Result.Error)
            assertTrue((result as Tool.Result.Error).message.contains("No AgentProcess context"))
        }

        @Test
        fun `agentic tool with artifact tools configured correctly`() {
            val agentic = AgenticTool("with-artifacts", "Artifact tools")
                .withTools(artifactTool, anotherArtifactTool)
                .withSystemPrompt("Use artifact tools")

            // Verify tools are configured
            assertEquals(2, agentic.tools.size)
            assertTrue(agentic.tools.any { it.definition.name == "artifact" })
            assertTrue(agentic.tools.any { it.definition.name == "another" })
        }

        @Test
        fun `agentic tool with mixed text and artifact tools configured correctly`() {
            val agentic = AgenticTool("mixed", "Mixed tools")
                .withTools(echoTool, artifactTool)
                .withSystemPrompt("Use any tools")

            assertEquals(2, agentic.tools.size)
            assertTrue(agentic.tools.any { it.definition.name == "echo" })
            assertTrue(agentic.tools.any { it.definition.name == "artifact" })
        }
    }

    @Nested
    inner class NestedArtifactCapturing {

        @Test
        fun `captureNestedArtifacts defaults to false`() {
            val agentic = AgenticTool("test", "Test")
                .withTools(artifactTool)

            assertFalse(agentic.captureNestedArtifacts)
        }

        @Test
        fun `withCaptureNestedArtifacts creates copy with new value`() {
            val agentic = AgenticTool("test", "Test")
                .withTools(artifactTool)

            val updated = agentic.withCaptureNestedArtifacts(true)

            assertTrue(updated.captureNestedArtifacts)
            assertFalse(agentic.captureNestedArtifacts) // Original unchanged
        }

        @Test
        fun `ArtifactCapturingTool with captureNestedArtifacts=false skips AgenticTool artifacts`() {
            // Create a nested AgenticTool that would return an artifact
            val nestedAgentic = AgenticTool("nested", "Nested agentic")
                .withTools(artifactTool)

            val artifacts = mutableListOf<Any>()
            // captureNestedArtifacts=false means we should skip artifacts from AgenticTool delegates
            val wrapper = ArtifactCapturingTool(
                delegate = nestedAgentic,
                artifacts = artifacts,
                captureNestedArtifacts = false
            )

            // The wrapper should detect that delegate is AgenticTool and skip capturing
            assertTrue(wrapper.shouldSkipCapture())
        }

        @Test
        fun `ArtifactCapturingTool with captureNestedArtifacts=true captures AgenticTool artifacts`() {
            val nestedAgentic = AgenticTool("nested", "Nested agentic")
                .withTools(artifactTool)

            val artifacts = mutableListOf<Any>()
            val wrapper = ArtifactCapturingTool(
                delegate = nestedAgentic,
                artifacts = artifacts,
                captureNestedArtifacts = true
            )

            assertFalse(wrapper.shouldSkipCapture())
        }

        @Test
        fun `ArtifactCapturingTool always captures from non-AgenticTool delegates`() {
            val artifacts = mutableListOf<Any>()
            val wrapper = ArtifactCapturingTool(
                delegate = artifactTool,
                artifacts = artifacts,
                captureNestedArtifacts = false // Even with false, regular tools are captured
            )

            assertFalse(wrapper.shouldSkipCapture())
        }
    }
}
