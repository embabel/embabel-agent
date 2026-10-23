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

import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.agent.api.reference.LlmReference
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.core.support.safelyGetTools
import com.embabel.common.ai.model.LlmOptions
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WithReferenceToolRegistrationTest {

    private fun makeRunner() = OperationContextPromptRunner(
        context = mockk(relaxed = true),
        llm = LlmOptions(),
        toolGroups = emptySet(),
        toolObjects = emptyList(),
        promptContributors = emptyList(),
        contextualPromptContributors = emptyList(),
        generateExamples = false,
    )

    @Nested
    inner class SingleRegistration {

        @Test
        fun `withReference registers each tool once`() {
            val tool = Tool.of("search", "Search") { Tool.Result.text("ok") }
            val reference = LlmReference.of("docs", "Documentation", listOf(tool))

            val runner = makeRunner().withReference(reference) as OperationContextPromptRunner

            val tools = safelyGetTools(runner.toolObjects)
            assertEquals(1, tools.size, "Each tool must appear exactly once")
        }

        @Test
        fun `withReference applies reference naming strategy`() {
            val tool = Tool.of("search", "Search") { Tool.Result.text("ok") }
            val reference = LlmReference.of("docs", "Documentation", listOf(tool))

            val runner = makeRunner().withReference(reference) as OperationContextPromptRunner

            val tools = safelyGetTools(runner.toolObjects)
            assertEquals("docs_search", tools[0].definition.name)
        }

        @Test
        fun `withReference produces one ToolObject not two`() {
            val tool = Tool.of("search", "Search") { Tool.Result.text("ok") }
            val reference = LlmReference.of("docs", "Documentation", listOf(tool))

            val runner = makeRunner().withReference(reference) as OperationContextPromptRunner

            assertEquals(1, runner.toolObjects.size)
        }

        @Test
        fun `withReference on multiple tools registers all once`() {
            val t1 = Tool.of("search", "Search") { Tool.Result.text("ok") }
            val t2 = Tool.of("fetch", "Fetch") { Tool.Result.text("ok") }
            val reference = LlmReference.of("api", "API", listOf(t1, t2))

            val runner = makeRunner().withReference(reference) as OperationContextPromptRunner

            val tools = safelyGetTools(runner.toolObjects)
            assertEquals(2, tools.size, "Both tools must appear, each exactly once")
            val names = tools.map { it.definition.name }.toSet()
            assertEquals(setOf("api_search", "api_fetch"), names)
        }
    }

    @Nested
    inner class ContextAware {

        @Test
        fun `context-aware tool inside LlmReference receives ToolCallContext`() {
            var capturedContext: ToolCallContext? = null
            val tool = Tool.of("search", "Search") { _, ctx ->
                capturedContext = ctx
                Tool.Result.text("ok")
            }
            val reference = LlmReference.of("docs", "Documentation", listOf(tool))

            val runner = makeRunner().withReference(reference) as OperationContextPromptRunner

            val resolved = safelyGetTools(runner.toolObjects)
            val ctx = ToolCallContext.EMPTY
            resolved[0].call("{}", ctx)

            assertEquals(ctx, capturedContext, "ToolCallContext must propagate through RenamedTool")
        }
    }

    @Nested
    inner class Unfolding {

        @Test
        fun `withUnfolding registers single tool named after prefix, not double-prefixed`() {
            val tool = Tool.of("vectorSearch", "Search") { Tool.Result.text("ok") }
            val reference = LlmReference.of("docs", "Documentation", listOf(tool))
                .withUnfolding()

            val runner = makeRunner().withReference(reference) as OperationContextPromptRunner

            val tools = safelyGetTools(runner.toolObjects)
            assertEquals(1, tools.size)
            assertEquals("docs", tools[0].definition.name, "Must be 'docs', not 'docs_docs'")
        }
    }

    @Nested
    inner class DoublePrefix {

        @Test
        fun `withReference does not double-prefix when rawTools returns bare names`() {
            // withReference calls rawTools() + namingStrategy, never tools() directly.
            // tools() is computed from rawTools() + strategy, so tools() already has
            // the prefix. Calling withReference() on such a ref must produce a single
            // prefix, not "docs_docs_search".
            val bareTool = Tool.of("search", "Search") { Tool.Result.text("ok") }
            val reference = object : LlmReference {
                override val name = "docs"
                override val description = "Documentation"
                override fun notes() = ""
                override fun rawTools() = listOf(bareTool)
                // tools() = default = namingStrategy("search") = "docs_search"
            }

            val runner = makeRunner().withReference(reference) as OperationContextPromptRunner

            val fromToolObjects = safelyGetTools(runner.toolObjects)
            assertFalse(
                fromToolObjects.any { it.definition.name.startsWith("docs_docs_") },
                "withReference must not double-prefix: got ${fromToolObjects.map { it.definition.name }}"
            )
            assertEquals("docs_search", fromToolObjects[0].definition.name)
        }
    }

    @Nested
    inner class OtherToolsGuard {

        @Test
        fun `withReference does not populate otherTools`() {
            // If withReference() were ever changed to also call withTools(reference.tools()),
            // the second copy would land in otherTools and bypass name-based deduplication.
            // This test makes that regression visible immediately.
            val tool = Tool.of("search", "Search") { Tool.Result.text("ok") }
            val reference = LlmReference.of("docs", "Documentation", listOf(tool))

            val runner = makeRunner().withReference(reference) as OperationContextPromptRunner

            val field = OperationContextPromptRunner::class.java.getDeclaredField("otherTools")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val otherTools = field.get(runner) as List<*>
            assertEquals(0, otherTools.size,
                "withReference must not populate otherTools — tools there bypass dedup and would register each tool twice")
        }
    }

    @Nested
    inner class RawTools {

        @Test
        fun `rawTools returns bare names`() {
            val tool = Tool.of("search", "Search") { Tool.Result.text("ok") }
            val reference = LlmReference.of("docs", "Documentation", listOf(tool))
            val rawNames = reference.rawTools().map { it.definition.name }
            assertFalse(rawNames.any { it.startsWith("docs_") }, "rawTools must return unprefixed names")
            assertTrue(rawNames.contains("search"))
        }

        @Test
        fun `tools returns prefixed names`() {
            val tool = Tool.of("search", "Search") { Tool.Result.text("ok") }
            val reference = LlmReference.of("docs", "Documentation", listOf(tool))
            val names = reference.tools().map { it.definition.name }
            assertTrue(names.contains("docs_search"), "tools() must return names prefixed with toolPrefix: got $names")
        }

        @Test
        fun `rawTools and tools differ for a prefixed reference`() {
            val tool = Tool.of("search", "Search") { Tool.Result.text("ok") }
            val reference = LlmReference.of("docs", "Documentation", listOf(tool))
            assertFalse(
                reference.rawTools().map { it.definition.name } == reference.tools().map { it.definition.name },
                "rawTools() (bare) and tools() (prefixed) must return different names"
            )
        }
    }
}
