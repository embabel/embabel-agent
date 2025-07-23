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
package com.embabel.agent.mcpserver

import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.mcpserver.support.McpPromptFactory
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class McpPromptFactoryTest {

    @Test
    fun noArgs() {
        class EmptyChild

        val factory = McpPromptFactory()
        val spec = factory.syncPromptSpecificationForType(
            NamedAndDescribed("EmptyChild", "A child with no arguments"),
            EmptyChild::class.java,
        )
        assertTrue(
            spec.prompt.arguments.isEmpty(),
            "Expected no arguments for EmptyChild, but got: ${spec.prompt.arguments}"
        )
    }

    @Test
    fun oneArg() {
        val factory = McpPromptFactory()
        val goal = NamedAndDescribed("oneArg", "A class with one argument")
        val spec = factory.syncPromptSpecificationForType(
            goal,
            UserInput::class.java,
        )
        assertEquals(
            spec.prompt.arguments.size,
            1,
            "Expected 1 argument for UserInput, but got: ${spec.prompt.arguments}"
        )
        assertEquals(
            setOf("content"), spec.prompt.arguments.map { it.name }.toSet(),
            "Expected argument 'content' for UserInput, but got: ${spec.prompt.arguments.map { it.name }}",
        )
        val p = spec.promptHandler.apply(mockk(), McpSchema.GetPromptRequest("oneArg", mapOf("content" to "test")))
        assertEquals(1, p.messages.size)
        val m = p.messages.first()
        assertEquals(McpSchema.Role.USER, m.role)
        val tc = m.content as McpSchema.TextContent
        assertTrue(
            tc.text.contains(goal.name),
            "Expected message content to contain goal name, but got: ${m.content}"
        )
        assertTrue(
            tc.text.contains(goal.description),
            "Expected message content to contain goal description, but got: ${m.content}"
        )
        assertTrue(
            tc.text.contains("test"),
            "Expected message content to contain 'test', but got: ${m.content}"
        )
    }

}


data class NamedAndDescribed(override val name: String, override val description: String) : Named, Described
