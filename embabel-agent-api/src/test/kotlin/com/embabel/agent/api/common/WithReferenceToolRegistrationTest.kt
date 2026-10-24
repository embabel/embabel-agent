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

    /**
     * Backward-compatibility: implementations written before [LlmReference.rawTools] was
     * introduced override [LlmReference.tools] only (returning bare tool names).
     * [LlmReference.rawTools] must bridge to [LlmReference.tools] so they still work with
     * [withReference] and MCP export without silent tool loss.
     */
    @Nested
    inner class ToolsOnlyOverrideCompatibility {

        @Test
        fun `withReference picks up tools from an impl that overrides tools() but not rawTools()`() {
            val tool = Tool.of("search", "Search") { Tool.Result.text("ok") }
            // Simulates a pre-rawTools() implementation: overrides tools() to return bare tool names.
            // rawTools() was not part of the API at that time, so this impl does not override it.
            val reference = object : LlmReference {
                override val name = "docs"
                override val description = "Documentation"
                override fun notes() = ""
                override fun tools() = listOf(tool)
            }

            val runner = makeRunner().withReference(reference) as OperationContextPromptRunner

            val tools = safelyGetTools(runner.toolObjects)
            assertEquals(1, tools.size,
                "withReference must register tools even when only tools() is overridden — rawTools() bridges to tools()")
            assertEquals("docs_search", tools[0].definition.name,
                "namingStrategy must be applied exactly once to the bare name returned by the tools() override")
        }

        @Test
        fun `withReference does not double-prefix when tools()-only impl returns bare names`() {
            val tool = Tool.of("search", "Search") { Tool.Result.text("ok") }
            val reference = object : LlmReference {
                override val name = "docs"
                override val description = "Documentation"
                override fun notes() = ""
                override fun tools() = listOf(tool)
            }

            val runner = makeRunner().withReference(reference) as OperationContextPromptRunner

            val names = safelyGetTools(runner.toolObjects).map { it.definition.name }
            assertFalse(names.any { it.startsWith("docs_docs_") },
                "namingStrategy must be applied once, not twice: got $names")
        }

        @Test
        fun `rawTools cycle guard returns emptyList when neither tools() nor rawTools() is overridden`() {
            // When neither method is overridden the bridge would loop:
            //   rawTools() → bridge → tools() → rawTools() → bridge → ...
            // inToolsBridge detects the re-entry and breaks the cycle.
            val reference = object : LlmReference {
                override val name = "empty"
                override val description = "No tools defined"
                override fun notes() = ""
            }

            val raw = reference.rawTools()  // must not throw StackOverflowError

            assertTrue(raw.isEmpty(),
                "A reference with no tools() or rawTools() override must produce an empty list, not a stack overflow")
        }

        @Test
        fun `inToolsBridge ThreadLocal is cleared after rawTools() returns normally`() {
            val reference = object : LlmReference {
                override val name = "docs"
                override val description = "test"
                override fun notes() = ""
                override fun tools() = listOf(Tool.of("t", "t") { Tool.Result.text("ok") })
            }

            reference.rawTools()

            assertTrue(LlmReference.inToolsBridge.get().isEmpty(),
                "inToolsBridge must be empty after rawTools() returns — reference not removed would leak into the next call on this thread")
        }

        @Test
        fun `inToolsBridge ThreadLocal is cleared even when tools() throws`() {
            val reference = object : LlmReference {
                override val name = "docs"
                override val description = "test"
                override fun notes() = ""
                override fun tools(): List<Tool> = throw RuntimeException("tools() failed")
            }

            try { reference.rawTools() } catch (_: RuntimeException) {}

            assertTrue(LlmReference.inToolsBridge.get().isEmpty(),
                "inToolsBridge must be empty after an exception in tools() — finally block must run regardless")
        }

        @Test
        fun `rawTools bridge does not suppress child tools when parent is a composite legacy reference`() {
            // Regression: the old Boolean guard blocked ALL references on the thread once the
            // parent entered the bridge. The identity-keyed set blocks only the re-entrant
            // reference, so a child with its own tools()-only override is unaffected.
            val childTool = Tool.of("child_op", "Child op") { Tool.Result.text("ok") }
            val child = object : LlmReference {
                override val name = "child"
                override val description = "child ref"
                override fun notes() = ""
                override fun tools() = listOf(childTool)  // legacy tools()-only override
            }
            val parent = object : LlmReference {
                override val name = "parent"
                override val description = "parent ref"
                override fun notes() = ""
                override fun tools() = child.rawTools()   // composite: collects from child
            }

            val tools = parent.rawTools()

            assertEquals(1, tools.size,
                "Child tools must not be suppressed by the parent's bridge guard: got ${tools.map { it.definition.name }}")
            assertEquals("child_op", tools[0].definition.name)
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
