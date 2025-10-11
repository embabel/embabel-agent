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
package com.embabel.agent.mcpserver.async

import com.embabel.agent.mcpserver.async.McpAsyncPromptFactory
import com.embabel.agent.domain.io.UserInput
import com.embabel.common.core.types.NamedAndDescribed
import com.embabel.common.core.types.Timestamped
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

// Test interfaces at top level
interface CustomInterface {
    val customField: String
}

class McpAsyncPromptFactoryTest {

    @Test
    fun `McpAsyncPromptFactory should create async prompt specifications`() {
        val factory = McpAsyncPromptFactory()
        val goal = NamedAndDescribed("testGoal", "A test goal for async processing")

        val spec = factory.asyncPromptSpecificationForType(
            goal,
            UserInput::class.java
        )

        assertNotNull(spec)
        assertEquals("UserInput_testGoal", spec.prompt.name())
        assertEquals("A test goal for async processing", spec.prompt.description())
        assertFalse(spec.prompt.arguments.isEmpty())
    }

    @Test
    fun `McpAsyncPromptFactory should handle custom name and description`() {
        val factory = McpAsyncPromptFactory()
        val goal = NamedAndDescribed("originalName", "Original description")

        val spec = factory.asyncPromptSpecificationForType(
            goal,
            UserInput::class.java,
            name = "customName",
            description = "Custom description"
        )

        assertEquals("UserInput_customName", spec.prompt.name())
        assertEquals("Custom description", spec.prompt.description())
    }

    @Test
    fun `McpAsyncPromptFactory should exclude timestamp fields by default`() {
        data class TestClassWithTimestamp(
            val name: String,
            val age: Int,
            override val timestamp: java.time.Instant
        ) : Timestamped

        val factory = McpAsyncPromptFactory()
        val goal = NamedAndDescribed("timestampTest", "Test with timestamp")

        val spec = factory.asyncPromptSpecificationForType(
            goal,
            TestClassWithTimestamp::class.java
        )

        val argumentNames = spec.prompt.arguments.map { it.name }
        assertFalse(argumentNames.contains("timestamp"))
        assertTrue(argumentNames.contains("name"))
        assertTrue(argumentNames.contains("age"))
    }

    @Test
    fun `McpAsyncPromptFactory should handle custom excluded interfaces`() {

        data class TestClassWithCustomInterface(
            val name: String,
            val age: Int,
            override val customField: String
        ) : CustomInterface

        val factory = McpAsyncPromptFactory(
            excludedInterfaces = setOf(CustomInterface::class.java)
        )
        val goal = NamedAndDescribed("customTest", "Test with custom interface")

        val spec = factory.asyncPromptSpecificationForType(
            goal,
            TestClassWithCustomInterface::class.java
        )

        val argumentNames = spec.prompt.arguments.map { it.name }
        assertFalse(argumentNames.contains("customField"))
        assertTrue(argumentNames.contains("name"))
        assertTrue(argumentNames.contains("age"))
    }

    @Test
    fun `McpAsyncPromptFactory should handle empty classes`() {
        class EmptyTestClass

        val factory = McpAsyncPromptFactory()
        val goal = NamedAndDescribed("emptyTest", "Test with empty class")

        val spec = factory.asyncPromptSpecificationForType(
            goal,
            EmptyTestClass::class.java
        )

        assertTrue(spec.prompt.arguments.isEmpty())
    }

    @Test
    fun `async prompt handler should process requests correctly`() {
        val factory = McpAsyncPromptFactory()
        val goal = NamedAndDescribed("handlerTest", "Test prompt handler")

        val spec = factory.asyncPromptSpecificationForType(
            goal,
            UserInput::class.java
        )

        val request = McpSchema.GetPromptRequest("handlerTest", mapOf("content" to "test content"))
        val asyncServerExchange = mockk<io.modelcontextprotocol.server.McpAsyncServerExchange>()

        val resultMono = spec.promptHandler.apply(asyncServerExchange, request)

        assertNotNull(resultMono)
        // Test that the Mono can be subscribed to (basic validity check)
        val result = resultMono.block()
        assertNotNull(result)
        assertEquals("handlerTest-result", result!!.description)
        assertEquals(1, result.messages.size)

        val message = result.messages.first()
        assertEquals(McpSchema.Role.USER, message.role)
        assertTrue((message.content as McpSchema.TextContent).text.contains("handlerTest"))
        assertTrue((message.content as McpSchema.TextContent).text.contains("test content"))
    }

    @Test
    fun `async prompt should handle multiple arguments correctly`() {
        data class MultiArgClass(
            val name: String,
            val age: Int,
            val email: String
        )

        val factory = McpAsyncPromptFactory()
        val goal = NamedAndDescribed("multiArgTest", "Test with multiple arguments")

        val spec = factory.asyncPromptSpecificationForType(
            goal,
            MultiArgClass::class.java
        )

        val request = McpSchema.GetPromptRequest(
            "multiArgTest",
            mapOf(
                "name" to "John",
                "age" to "30",
                "email" to "john@example.com"
            )
        )
        val asyncServerExchange = mockk<io.modelcontextprotocol.server.McpAsyncServerExchange>()

        val result = spec.promptHandler.apply(asyncServerExchange, request).block()

        assertNotNull(result)
        val messageContent = (result!!.messages.first().content as McpSchema.TextContent).text
        assertTrue(messageContent.contains("name=John"))
        assertTrue(messageContent.contains("age=30"))
        assertTrue(messageContent.contains("email=john@example.com"))
    }

    @Test
    fun `async prompt should handle empty arguments map`() {
        val factory = McpAsyncPromptFactory()
        val goal = NamedAndDescribed("emptyArgsTest", "Test with empty arguments")

        val spec = factory.asyncPromptSpecificationForType(
            goal,
            UserInput::class.java
        )

        val request = McpSchema.GetPromptRequest("emptyArgsTest", emptyMap())
        val asyncServerExchange = mockk<io.modelcontextprotocol.server.McpAsyncServerExchange>()

        val result = spec.promptHandler.apply(asyncServerExchange, request).block()

        assertNotNull(result)
        val messageContent = (result!!.messages.first().content as McpSchema.TextContent).text
        assertTrue(messageContent.contains("emptyArgsTest"))
        assertTrue(messageContent.contains("Test with empty arguments"))
    }
}
