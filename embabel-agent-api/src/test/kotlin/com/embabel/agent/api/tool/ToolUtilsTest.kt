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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolUtilsTest {

    @Nested
    inner class FormatToolTreeTests {

        @Test
        fun `returns message for empty tool list`() {
            val result = ToolUtils.formatToolTree("MyAgent", emptyList())

            assertThat(result).isEqualTo("MyAgent has no tools")
        }

        @Test
        fun `formats single tool`() {
            val tool = simpleTool("get_weather")

            val result = ToolUtils.formatToolTree("MyAgent", listOf(tool))

            assertThat(result).isEqualTo(
                """
                MyAgent
                └── get_weather
                """.trimIndent()
            )
        }

        @Test
        fun `formats multiple tools`() {
            val tools = listOf(
                simpleTool("get_weather"),
                simpleTool("send_email"),
                simpleTool("read_file")
            )

            val result = ToolUtils.formatToolTree("MyAgent", tools)

            assertThat(result).isEqualTo(
                """
                MyAgent
                ├── get_weather
                ├── send_email
                └── read_file
                """.trimIndent()
            )
        }

        @Test
        fun `formats MatryoshkaTool with inner tools`() {
            val innerTools = listOf(
                simpleTool("query_database"),
                simpleTool("insert_record")
            )
            val matryoshka = MatryoshkaTool.of(
                name = "database_ops",
                description = "Database operations",
                innerTools = innerTools
            )

            val result = ToolUtils.formatToolTree("MyAgent", listOf(matryoshka))

            assertThat(result).isEqualTo(
                """
                MyAgent
                └── database_ops (2 inner tools)
                    ├── query_database
                    └── insert_record
                """.trimIndent()
            )
        }

        @Test
        fun `formats mixed regular and MatryoshkaTools`() {
            val dbInnerTools = listOf(
                simpleTool("query"),
                simpleTool("insert")
            )
            val dbTool = MatryoshkaTool.of(
                name = "database",
                description = "Database ops",
                innerTools = dbInnerTools
            )

            val tools = listOf(
                simpleTool("get_weather"),
                dbTool,
                simpleTool("send_email")
            )

            val result = ToolUtils.formatToolTree("MyAgent", tools)

            assertThat(result).isEqualTo(
                """
                MyAgent
                ├── get_weather
                ├── database (2 inner tools)
                │   ├── query
                │   └── insert
                └── send_email
                """.trimIndent()
            )
        }

        @Test
        fun `formats MatryoshkaTool with no inner tools`() {
            val matryoshka = MatryoshkaTool.of(
                name = "empty_group",
                description = "Empty group",
                innerTools = emptyList()
            )

            val result = ToolUtils.formatToolTree("MyAgent", listOf(matryoshka))

            assertThat(result).isEqualTo(
                """
                MyAgent
                └── empty_group (0 inner tools)
                """.trimIndent()
            )
        }

        @Test
        fun `formats nested MatryoshkaTools recursively`() {
            val innerMost = listOf(
                simpleTool("deep_tool_1"),
                simpleTool("deep_tool_2")
            )
            val nestedMatryoshka = MatryoshkaTool.of(
                name = "nested_group",
                description = "Nested group",
                innerTools = innerMost
            )
            val outerMatryoshka = MatryoshkaTool.of(
                name = "outer_group",
                description = "Outer group",
                innerTools = listOf(simpleTool("sibling"), nestedMatryoshka)
            )

            val result = ToolUtils.formatToolTree("MyAgent", listOf(outerMatryoshka))

            assertThat(result).isEqualTo(
                """
                MyAgent
                └── outer_group (2 inner tools)
                    ├── sibling
                    └── nested_group (2 inner tools)
                        ├── deep_tool_1
                        └── deep_tool_2
                """.trimIndent()
            )
        }

        private fun simpleTool(name: String): Tool {
            return Tool.of(
                name = name,
                description = "Test tool $name"
            ) { Tool.Result.text("ok") }
        }
    }
}
