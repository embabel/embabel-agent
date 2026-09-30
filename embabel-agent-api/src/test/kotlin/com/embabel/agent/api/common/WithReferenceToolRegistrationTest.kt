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

import com.embabel.agent.api.annotation.support.Wumpus
import com.embabel.agent.api.reference.LlmReference
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import com.embabel.agent.core.support.LookupToolsA
import com.embabel.agent.core.support.LookupToolsB
import com.embabel.agent.core.support.captureWarnings
import com.embabel.agent.spi.support.unwrapAs
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.agent.test.unit.FakePromptRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Checks that [PromptRunner.withReference] exposes each reference tool once, with the prefix
 * applied one time. See embabel/embabel-agent#2093.
 */
class WithReferenceToolRegistrationTest {

    data class Answer(val text: String)

    @Test
    fun `withReference exposes each reference tool once with a single prefix`() {
        val context = FakeOperationContext.create()
        context.expectResponse(Answer("ok"))

        // Wumpus exposes two @LlmTool methods: toolWithoutArg and toolWithArg.
        val reference = LlmReference.of(
            name = "wref",
            description = "Weather reference",
            tool = Wumpus("w"),
        )

        context.promptRunner
            .withReference(reference)
            .createObject("Answer the question", Answer::class.java)

        val toolNames = context.llmInvocations.single()
            .interaction.tools
            .map { it.definition.name }
            .sorted()

        assertEquals(
            listOf("wref_toolWithArg", "wref_toolWithoutArg"),
            toolNames,
            "withReference must expose each tool once, with a single prefix",
        )
    }

    @Test
    fun `withReference on an unfolding reference exposes the wrapper tool once, not double-prefixed`() {
        val context = FakeOperationContext.create()
        context.expectResponse(Answer("ok"))

        // withUnfolding wraps the reference tools in one tool named after the tool prefix.
        val tool = Tool.of("search", "A search tool") { Tool.Result.text("ok") }
        val reference = LlmReference.of(
            name = "docs",
            description = "Docs",
            tools = listOf(tool),
        ).withUnfolding()

        context.promptRunner
            .withReference(reference)
            .createObject("Answer the question", Answer::class.java)

        val toolNames = context.llmInvocations.single()
            .interaction.tools
            .map { it.definition.name }

        assertEquals(
            listOf("docs"),
            toolNames,
            "the unfolding wrapper must be named 'docs', not double-prefixed to 'docs_docs'",
        )
    }

    @Test
    fun `withReference keeps the name of a tool that is already named after the reference`() {
        val context = FakeOperationContext.create()
        context.expectResponse(Answer("ok"))

        // Like DICE Memory: the reference is named "memory" and returns one tool named "memory".
        val tool = Tool.of("memory", "Recall facts") { Tool.Result.text("ok") }

        context.promptRunner
            .withReference(LlmReference.of(name = "memory", description = "Memory", tools = listOf(tool)))
            .createObject("Answer the question", Answer::class.java)

        assertEquals(
            listOf("memory"),
            context.llmInvocations.single().interaction.tools.map { it.definition.name },
            "a tool already named after the reference must not become memory_memory",
        )
    }

    @Test
    fun `the fake prompt runner warns like production when a tool name repeats`() {
        val context = FakeOperationContext.create()
        context.expectResponse(Answer("ok"))

        val warnings = captureWarnings(FakePromptRunner::class.java.name) {
            context.promptRunner
                .withToolObject(ToolObject(LookupToolsA()))
                .withTool(Tool.fromInstance(LookupToolsB()).single())
                .createObject("Answer the question", Answer::class.java)
        }

        assertEquals(1, warnings.size, "warnings were $warnings")
    }

    @Test
    fun `two unfolded references expose different inner tool names after they unfold`() {
        val context = FakeOperationContext.create()
        context.expectResponse(Answer("ok"))

        // Both references have an inner tool named "search". After unfolding, the injection
        // strategy adds the inner tools to the same tool list, so the names must differ.
        fun unfolded(name: String) = LlmReference.of(
            name = name,
            description = name,
            tools = listOf(Tool.of("search", "A search tool") { Tool.Result.text("ok") }),
        ).withUnfolding()

        context.promptRunner
            .withReferences(unfolded("docs"), unfolded("wiki"))
            .createObject("Answer the question", Answer::class.java)

        val innerNames = context.llmInvocations.single().interaction.tools
            .flatMap { it.unwrapAs<UnfoldingTool>()!!.innerTools }
            .map { it.definition.name }
            .sorted()

        assertEquals(listOf("docs_search", "wiki_search"), innerNames)
    }

    @Test
    fun `withReference keeps an unfolding tool inside a reference unwrappable after the rename`() {
        val context = FakeOperationContext.create()
        context.expectResponse(Answer("ok"))

        val inner = Tool.of("search", "A search tool") { Tool.Result.text("ok") }
        val unfolding = UnfoldingTool.of(name = "kb", description = "Knowledge base", innerTools = listOf(inner))

        context.promptRunner
            .withReference(LlmReference.of(name = "docs", description = "Docs", tools = listOf(unfolding)))
            .createObject("Answer the question", Answer::class.java)

        val resolved = context.llmInvocations.single().interaction.tools.single()

        // UnfoldingToolInjectionStrategy finds the unfolding tool with unwrapAs.
        assertNotNull(
            resolved.unwrapAs<UnfoldingTool>(),
            "the renamed tool must expose the unfolding tool through DelegatingTool",
        )
    }

    @Test
    fun `withReference passes the tool call context through the renamed tool`() {
        val context = FakeOperationContext.create()
        context.expectResponse(Answer("ok"))

        var received: ToolCallContext? = null
        val tool = object : Tool {
            override val definition = Tool.Definition(
                name = "whoami",
                description = "Returns the caller",
                inputSchema = Tool.InputSchema.empty(),
            )

            override fun call(input: String): Tool.Result = call(input, ToolCallContext.EMPTY)

            override fun call(input: String, context: ToolCallContext): Tool.Result {
                received = context
                return Tool.Result.text("ok")
            }
        }

        context.promptRunner
            .withReference(LlmReference.of(name = "auth", description = "Auth", tools = listOf(tool)))
            .createObject("Answer the question", Answer::class.java)

        val callContext = ToolCallContext.of("tenant" to "t1")
        context.llmInvocations.single().interaction.tools.single().call("{}", callContext)

        assertSame(callContext, received, "the renamed tool must pass the call context to the tool")
    }
}
