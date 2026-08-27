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
package com.embabel.agent.spi.loop.support

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.api.tool.callback.AfterToolCallContext
import com.embabel.agent.api.tool.callback.BeforeToolCallContext
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.agent.spi.loop.MockTool
import com.embabel.agent.spi.loop.ToolInjectionContext
import com.embabel.agent.spi.loop.ToolInjectionResult
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.chat.ToolCall
import com.embabel.chat.UserMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class ToolExecutionSupportTest {

    @Test
    fun `executeTool returns content and publishes callbacks`() {
        val callbacks = mutableListOf<String>()
        val inspector = object : ToolCallInspector {
            override fun beforeToolCall(context: BeforeToolCallContext) {
                callbacks += "before:${context.toolCall.name}"
            }

            override fun afterToolCall(context: AfterToolCallContext) {
                callbacks += "after:${context.resultAsString}"
            }
        }
        val tool = MockTool("lookup", "Lookup") { Tool.Result.text("found") }

        val executed = executeTool(
            tool = tool,
            toolCall = ToolCall("call-1", "lookup", "{}"),
            toolCallContext = ToolCallContext.EMPTY,
            toolCallInspectors = listOf(inspector),
        )

        assertTrue(executed.result is Tool.Result.Text)
        assertEquals("found", executed.content)
        assertEquals(listOf("before:lookup", "after:found"), callbacks)
    }

    @Test
    fun `applyToolInjection shares decoration deduplication removal and JSON context semantics`() {
        val existing = MockTool("existing", "Existing") { Tool.Result.text("existing") }
        val removed = MockTool("removed", "Removed") { Tool.Result.text("removed") }
        val added = MockTool("added", "Added") { Tool.Result.text("added") }
        val decorated = MockTool("added", "Decorated") { Tool.Result.text("decorated") }
        val available = mutableListOf<Tool>(existing, removed)
        val injectedTools = mutableListOf<Tool>()
        val removedTools = mutableListOf<Tool>()
        var evaluatedContext: ToolInjectionContext? = null
        val strategy = object : ToolInjectionStrategy {
            override fun evaluate(context: ToolInjectionContext): ToolInjectionResult {
                evaluatedContext = context
                return ToolInjectionResult(
                    toolsToAdd = listOf(existing, added),
                    toolsToRemove = listOf(removed),
                )
            }
        }

        applyToolInjection(
            toolCall = ToolCall("call-1", "source", "{\"input\":true}"),
            resultContent = "{\"answer\":42}",
            conversationHistory = listOf(UserMessage("question")),
            availableTools = available,
            iteration = 3,
            injectionStrategy = strategy,
            objectMapper = jacksonObjectMapper(),
            toolDecorator = { if (it.definition.name == "added") decorated else it },
            injectedTools = injectedTools,
            removedTools = removedTools,
        )

        assertEquals(listOf("existing", "added"), available.map { it.definition.name })
        assertTrue(decorated === available.last())
        assertEquals(listOf(decorated), injectedTools)
        assertEquals(listOf(removed), removedTools)
        assertEquals(3, evaluatedContext?.iterationCount)
        assertEquals("source", evaluatedContext?.lastToolCall?.toolName)
        assertTrue(evaluatedContext?.lastToolCall?.resultObject is Map<*, *>)
    }
}
