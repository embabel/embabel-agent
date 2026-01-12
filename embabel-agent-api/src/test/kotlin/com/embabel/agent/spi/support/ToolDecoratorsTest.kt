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
package com.embabel.agent.spi.support

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ToolGroupMetadata
import com.embabel.common.util.StringTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for Tool decorator classes in ToolDecorators.kt.
 */
class ToolDecoratorsTest {

    @Nested
    inner class ExceptionSuppressingToolTest {

        @Test
        fun `delegates call to underlying tool on success`() {
            val delegateTool = createMockTool("test-tool") { Tool.Result.text("success result") }
            val suppressingTool = ExceptionSuppressingTool(delegateTool)

            val result = suppressingTool.call("{}")

            assertEquals("success result", (result as Tool.Result.Text).content)
        }

        @Test
        fun `returns warning message when delegate throws exception`() {
            val delegateTool = createMockTool("failing-tool") {
                throw RuntimeException("Something went wrong")
            }
            val suppressingTool = ExceptionSuppressingTool(delegateTool)

            val result = suppressingTool.call("{}")

            assertTrue(result is Tool.Result.Text)
            val content = (result as Tool.Result.Text).content
            assertTrue(content.contains("WARNING"))
            assertTrue(content.contains("failing-tool"))
            assertTrue(content.contains("Something went wrong"))
        }

        @Test
        fun `handles exception with no message`() {
            val delegateTool = createMockTool("failing-tool") {
                throw RuntimeException()
            }
            val suppressingTool = ExceptionSuppressingTool(delegateTool)

            val result = suppressingTool.call("{}")

            assertTrue(result is Tool.Result.Text)
            val content = (result as Tool.Result.Text).content
            assertTrue(content.contains("No message"))
        }

        @Test
        fun `preserves tool definition from delegate`() {
            val delegateTool = createMockTool("preserved-name") { Tool.Result.text("{}") }
            val suppressingTool = ExceptionSuppressingTool(delegateTool)

            assertEquals("preserved-name", suppressingTool.definition.name)
            assertEquals(delegateTool.definition.description, suppressingTool.definition.description)
        }

        @Test
        fun `preserves tool metadata from delegate`() {
            val delegateTool = createMockTool("meta-tool") { Tool.Result.text("{}") }
            val suppressingTool = ExceptionSuppressingTool(delegateTool)

            assertEquals(delegateTool.metadata, suppressingTool.metadata)
        }
    }

    @Nested
    inner class OutputTransformingToolTest {

        @Test
        fun `transforms output using provided transformer`() {
            val delegateTool = createMockTool("transform-tool") {
                Tool.Result.text("hello world")
            }
            val transformer = StringTransformer { it.uppercase() }
            val transformingTool = OutputTransformingTool(delegateTool, transformer)

            val result = transformingTool.call("{}")

            assertEquals("HELLO WORLD", (result as Tool.Result.Text).content)
        }

        @Test
        fun `transforms WithArtifact result content`() {
            val delegateTool = createMockTool("artifact-tool") {
                Tool.Result.WithArtifact(content = "artifact content", artifact = "data")
            }
            val transformer = StringTransformer { it.reversed() }
            val transformingTool = OutputTransformingTool(delegateTool, transformer)

            val result = transformingTool.call("{}")

            // Result is transformed to Text - "artifact content" reversed
            assertEquals("tnetnoc tcafitra", (result as Tool.Result.Text).content)
        }

        @Test
        fun `transforms Error result message`() {
            val delegateTool = createMockTool("error-tool") {
                Tool.Result.Error("error message")
            }
            val transformer = StringTransformer { "TRANSFORMED: $it" }
            val transformingTool = OutputTransformingTool(delegateTool, transformer)

            val result = transformingTool.call("{}")

            assertEquals("TRANSFORMED: error message", (result as Tool.Result.Text).content)
        }

        @Test
        fun `preserves tool definition from delegate`() {
            val delegateTool = createMockTool("preserved-name") { Tool.Result.text("{}") }
            val transformer = StringTransformer { it }
            val transformingTool = OutputTransformingTool(delegateTool, transformer)

            assertEquals("preserved-name", transformingTool.definition.name)
        }

        @Test
        fun `identity transformer returns same content`() {
            val delegateTool = createMockTool("identity-tool") {
                Tool.Result.text("unchanged content")
            }
            val transformer = StringTransformer { it }
            val transformingTool = OutputTransformingTool(delegateTool, transformer)

            val result = transformingTool.call("{}")

            assertEquals("unchanged content", (result as Tool.Result.Text).content)
        }
    }

    @Nested
    inner class MetadataEnrichedToolTest {

        @Test
        fun `delegates call to underlying tool on success`() {
            val delegateTool = createMockTool("meta-tool") { Tool.Result.text("success") }
            val enrichedTool = MetadataEnrichedTool(delegateTool, null)

            val result = enrichedTool.call("{}")

            assertEquals("success", (result as Tool.Result.Text).content)
        }

        @Test
        fun `rethrows exception from delegate`() {
            val delegateTool = createMockTool("failing-tool") {
                throw IllegalArgumentException("Bad input")
            }
            val enrichedTool = MetadataEnrichedTool(delegateTool, null)

            val exception = assertThrows<IllegalArgumentException> {
                enrichedTool.call("{}")
            }
            assertEquals("Bad input", exception.message)
        }

        @Test
        fun `stores tool group metadata`() {
            val delegateTool = createMockTool("meta-tool") { Tool.Result.text("{}") }
            val metadata = ToolGroupMetadata(
                description = "Test group",
                role = "test-role",
                name = "test-group",
                provider = "test-provider",
                permissions = emptySet(),
            )
            val enrichedTool = MetadataEnrichedTool(delegateTool, metadata)

            assertEquals(metadata, enrichedTool.toolGroupMetadata)
        }

        @Test
        fun `preserves tool definition from delegate`() {
            val delegateTool = createMockTool("preserved-name") { Tool.Result.text("{}") }
            val enrichedTool = MetadataEnrichedTool(delegateTool, null)

            assertEquals("preserved-name", enrichedTool.definition.name)
        }

        @Test
        fun `toString includes delegate name and metadata`() {
            val delegateTool = createMockTool("my-tool") { Tool.Result.text("{}") }
            val metadata = ToolGroupMetadata(
                description = "Test group",
                role = "test-role",
                name = "test-group",
                provider = "test-provider",
                permissions = emptySet(),
            )
            val enrichedTool = MetadataEnrichedTool(delegateTool, metadata)

            val str = enrichedTool.toString()

            assertTrue(str.contains("my-tool"))
            assertTrue(str.contains("MetadataEnrichedTool"))
        }
    }

    @Nested
    inner class ObservabilityToolTest {

        @Test
        fun `delegates call when no observation registry`() {
            val delegateTool = createMockTool("obs-tool") { Tool.Result.text("observed") }
            val observabilityTool = ObservabilityTool(delegateTool, null)

            val result = observabilityTool.call("{}")

            assertEquals("observed", (result as Tool.Result.Text).content)
        }

        @Test
        fun `preserves tool definition from delegate`() {
            val delegateTool = createMockTool("preserved-name") { Tool.Result.text("{}") }
            val observabilityTool = ObservabilityTool(delegateTool, null)

            assertEquals("preserved-name", observabilityTool.definition.name)
        }

        @Test
        fun `toString includes delegate name`() {
            val delegateTool = createMockTool("obs-tool") { Tool.Result.text("{}") }
            val observabilityTool = ObservabilityTool(delegateTool, null)

            val str = observabilityTool.toString()

            assertTrue(str.contains("obs-tool"))
            assertTrue(str.contains("ObservabilityTool"))
        }
    }

    @Nested
    inner class ToolResultContentExtensionTest {

        @Test
        fun `Text result returns content`() {
            val result = Tool.Result.text("text content")
            val tool = createMockTool("test") { result }
            val suppressing = ExceptionSuppressingTool(tool)

            // ExceptionSuppressingTool uses the content extension internally
            val output = suppressing.call("{}")
            assertEquals("text content", (output as Tool.Result.Text).content)
        }

        @Test
        fun `WithArtifact result returns content`() {
            val result = Tool.Result.WithArtifact("artifact content", "data")
            val tool = createMockTool("test") { result }
            val transformer = StringTransformer { it.uppercase() }
            val transforming = OutputTransformingTool(tool, transformer)

            val output = transforming.call("{}")
            assertEquals("ARTIFACT CONTENT", (output as Tool.Result.Text).content)
        }

        @Test
        fun `Error result returns message`() {
            val result = Tool.Result.Error("error message")
            val tool = createMockTool("test") { result }
            val transformer = StringTransformer { "PREFIX: $it" }
            val transforming = OutputTransformingTool(tool, transformer)

            val output = transforming.call("{}")
            assertEquals("PREFIX: error message", (output as Tool.Result.Text).content)
        }
    }

    private fun createMockTool(name: String, onCall: (String) -> Tool.Result): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = name,
            description = "Mock tool $name",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result = onCall(input)
    }
}
