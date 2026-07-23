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
package com.embabel.agent.api.tool.progressive

import com.embabel.agent.api.tool.Tool
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the unfolding tool shortcut dispatch — when an LLM passes
 * inner tool arguments directly to the outer unfolding tool.
 */
class UnfoldingShortcutTest {

    private fun innerTool(name: String, handler: (String) -> String = { "called $name with: $it" }): Tool =
        Tool.of(name, "Test tool $name", Tool.InputSchema.of(
            Tool.Parameter.string("value", "A test value"),
        )) { input -> Tool.Result.text(handler(input)) }

    @Nested
    inner class TryShortcutDispatch {

        @Test
        fun `returns null for empty input`() {
            val tools = listOf(innerTool("create_task"))
            assertNull(tryShortcutDispatch("", tools))
            assertNull(tryShortcutDispatch("{}", tools))
        }

        @Test
        fun `returns null for invalid JSON`() {
            val tools = listOf(innerTool("create_task"))
            assertNull(tryShortcutDispatch("not json", tools))
        }

        @Test
        fun `returns null for JSON array`() {
            val tools = listOf(innerTool("create_task"))
            assertNull(tryShortcutDispatch("[1,2,3]", tools))
        }

        @Test
        fun `returns null when no field matches inner tool name`() {
            val tools = listOf(innerTool("create_task"))
            assertNull(tryShortcutDispatch("""{"unknown_tool": {"value": "test"}}""", tools))
        }

        @Test
        fun `dispatches to inner tool when field matches`() {
            var receivedInput: String? = null
            val tools = listOf(Tool.of("create_task", "Create a task", Tool.InputSchema.of(
                Tool.Parameter.string("name", "Task name"),
            )) { input ->
                receivedInput = input
                Tool.Result.text("Task created")
            })

            val result = tryShortcutDispatch(
                """{"create_task": {"name": "review-auth", "priority": "high"}}""",
                tools,
            )

            assertNotNull(result)
            assertTrue((result as Tool.Result.Text).content.contains("Task created"))
            assertNotNull(receivedInput)
            assertTrue(receivedInput!!.contains("review-auth"))
        }

        @Test
        fun `dispatches to first matching inner tool`() {
            val callLog = mutableListOf<String>()
            val tools = listOf(
                Tool.of("list_tasks", "List tasks") { callLog.add("list"); Tool.Result.text("listed") },
                Tool.of("create_task", "Create a task") { callLog.add("create"); Tool.Result.text("created") },
                Tool.of("delete_task", "Delete a task") { callLog.add("delete"); Tool.Result.text("deleted") },
            )

            val result = tryShortcutDispatch(
                """{"create_task": {"name": "foo"}}""",
                tools,
            )

            assertNotNull(result)
            assertEquals(listOf("create"), callLog)
        }

        @Test
        fun `passes nested object arguments as JSON string`() {
            var receivedInput: String? = null
            val tools = listOf(Tool.of("create_task", "Create") { input ->
                receivedInput = input
                Tool.Result.text("ok")
            })

            tryShortcutDispatch(
                """{"create_task": {"name": "foo", "tags": ["a", "b"]}}""",
                tools,
            )

            assertNotNull(receivedInput)
            assertTrue(receivedInput!!.contains("\"name\""))
            assertTrue(receivedInput!!.contains("\"tags\""))
        }

        @Test
        fun `handles scalar nested value`() {
            var receivedInput: String? = null
            val tools = listOf(Tool.of("activate", "Activate") { input ->
                receivedInput = input
                Tool.Result.text("activated")
            })

            val result = tryShortcutDispatch(
                """{"activate": "ecstasy"}""",
                tools,
            )

            assertNotNull(result)
            assertEquals("\"ecstasy\"", receivedInput)
        }

        @Test
        fun `returns null for empty inner tools list`() {
            assertNull(tryShortcutDispatch("""{"anything": {}}""", emptyList()))
        }
    }

    @Nested
    inner class SimpleUnfoldingToolShortcut {

        @Test
        fun `normal two-step invocation still works`() {
            val inner = innerTool("create_task")
            val tool = UnfoldingTool.of("tasks", "Task management", listOf(inner))

            val result = tool.call("{}")
            assertTrue((result as Tool.Result.Text).content.contains("Tools now available"))
            assertTrue(result.content.contains("create_task"))
        }

        @Test
        fun `shortcut dispatch works when LLM passes inner tool args`() {
            var called = false
            val inner = Tool.of("create_task", "Create a task", Tool.InputSchema.of(
                Tool.Parameter.string("name", "Task name"),
            )) { input ->
                called = true
                Tool.Result.text("Created task from: $input")
            }
            val tool = UnfoldingTool.of("tasks", "Task management", listOf(inner))

            val result = tool.call("""{"create_task": {"name": "review-auth"}}""")

            assertTrue(called, "Inner tool should have been called via shortcut")
            assertTrue((result as Tool.Result.Text).content.contains("Created task"))
            assertTrue(result.content.contains("review-auth"))
        }

        @Test
        fun `unrecognized payload falls through to normal unfold`() {
            val inner = innerTool("create_task")
            val tool = UnfoldingTool.of("tasks", "Task management", listOf(inner))

            val result = tool.call("""{"random_field": "value"}""")
            assertTrue((result as Tool.Result.Text).content.contains("Tools now available"))
        }

        @Test
        fun `shortcut with multiple inner tools selects correct one`() {
            val callLog = mutableListOf<String>()
            val tools = listOf(
                Tool.of("list", "List") { callLog.add("list"); Tool.Result.text("listed") },
                Tool.of("create", "Create") { callLog.add("create"); Tool.Result.text("created") },
                Tool.of("delete", "Delete") { callLog.add("delete"); Tool.Result.text("deleted") },
            )
            val outer = UnfoldingTool.of("mgmt", "Management", tools)

            outer.call("""{"delete": {"name": "foo"}}""")
            assertEquals(listOf("delete"), callLog)
        }
    }

    @Nested
    inner class RealWorldPatterns {

        @Test
        fun `tasks pattern - LLM passes create_task args to tasks outer tool`() {
            var taskName: String? = null
            val createTask = Tool.of(
                "create_task", "Create a workspace task",
                Tool.InputSchema.of(
                    Tool.Parameter.string("name", "Task name"),
                    Tool.Parameter.string("description", "Task description"),
                    Tool.Parameter.string("priority", "Priority level"),
                ),
            ) { input ->
                val parsed = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(input)
                taskName = parsed.get("name")?.asText()
                Tool.Result.text("Task '$taskName' created")
            }
            val listTasks = Tool.of("list_tasks", "List tasks") { Tool.Result.text("No tasks") }
            val deleteTasks = Tool.of("delete_task", "Delete a task") { Tool.Result.text("Deleted") }

            val tasks = UnfoldingTool.of("tasks", "Manage workspace tasks", listOf(listTasks, createTask, deleteTasks))

            // This is what gpt-4.1-mini actually sends:
            val result = tasks.call("""{"create_task":{"name":"review-auth","description":"Review authentication security","priority":"high"}}""")

            assertEquals("review-auth", taskName, "Inner create_task tool should have been called")
            assertTrue((result as Tool.Result.Text).content.contains("review-auth"))
        }

        @Test
        fun `personality pattern - LLM passes activate args`() {
            var activated: String? = null
            val activate = Tool.of("activate", "Activate a skill") { input ->
                activated = input
                Tool.Result.text("Skill activated")
            }
            val listResources = Tool.of("listResources", "List resources") { Tool.Result.text("[]") }

            val skill = UnfoldingTool.of("ecstasy", "Ecstasy language skill", listOf(activate, listResources))

            val result = skill.call("""{"activate": "ecstasy"}""")

            assertNotNull(activated)
            assertTrue((result as Tool.Result.Text).content.contains("activated"))
        }
    }
}
