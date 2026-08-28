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
package com.embabel.agent.core

import com.embabel.agent.api.tool.Tool
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ToolNamingStrategyTest {

    @Nested
    inner class Legacy {

        @Test
        fun `keeps the existing tool name`() {
            val consumer = consumer("Agent.action")

            val name = ToolNamingStrategy.LEGACY.nameFor(consumer, "lookup")

            assertEquals("lookup", name)
        }
    }

    @Nested
    inner class FullHierarchy {

        @Test
        fun `uses the consumer hierarchy and sanitizes the published name`() {
            val consumer = consumer("com.example.Agent.action")

            val name = ToolNamingStrategy.FULL_HIERARCHY.nameFor(consumer, "lookup-tool")

            assertEquals("com_example_Agent_action_lookup_tool", name)
        }

        @Test
        fun `uses an owner hierarchy for generated tools`() {
            val name = ToolNamingStrategy.FULL_HIERARCHY.nameFor("Agent", "done")

            assertEquals("Agent_done", name)
        }
    }

    private fun consumer(hierarchyName: String): ToolConsumer = object : ToolConsumer {
        override val name = "interaction"
        override val tools = emptyList<Tool>()
        override val toolGroups = emptySet<ToolGroupRequirement>()

        override fun fullHierarchyName(): String = hierarchyName
    }
}
