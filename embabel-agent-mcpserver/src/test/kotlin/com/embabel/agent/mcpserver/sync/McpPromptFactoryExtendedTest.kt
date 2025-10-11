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
package com.embabel.agent.mcpserver.sync

import com.embabel.agent.domain.io.UserInput
import com.embabel.common.core.types.NamedAndDescribed
import com.embabel.common.core.types.Timestamped
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

// Test interfaces at top level
interface AuditableInterface {
    val createdBy: String
    val createdAt: java.time.Instant
}

interface Interface1 {
    val field1: String
}

interface Interface2 {
    val field2: String
}

class McpPromptFactoryExtendedTest {

    @Test
    fun `McpPromptFactory should handle various argument types correctly`() {
        data class ComplexTestClass(
            @JsonPropertyDescription("User's full name")
            val name: String,
            @JsonPropertyDescription("User's age in years")
            val age: Int,
            val email: String,
            val isActive: Boolean,
            val score: Double
        )

        val factory = McpPromptFactory()
        val goal = NamedAndDescribed("complexTest", "A complex test goal")

        val spec = factory.syncPromptSpecificationForType(
            goal,
            ComplexTestClass::class.java
        )

        assertEquals(5, spec.prompt.arguments.size)

        val nameArg = spec.prompt.arguments.find { it.name == "name" }
        assertNotNull(nameArg)
        assertEquals("User's full name: String", nameArg!!.description)
        assertTrue(nameArg.required)

        val ageArg = spec.prompt.arguments.find { it.name == "age" }
        assertNotNull(ageArg)
        assertEquals("User's age in years: Int", ageArg!!.description)

        val emailArg = spec.prompt.arguments.find { it.name == "email" }
        assertNotNull(emailArg)
        assertEquals("email: String", emailArg!!.description)

        val isActiveArg = spec.prompt.arguments.find { it.name == "isActive" }
        assertNotNull(isActiveArg)
        assertEquals("isActive: Boolean", isActiveArg!!.description)

        val scoreArg = spec.prompt.arguments.find { it.name == "score" }
        assertNotNull(scoreArg)
        assertEquals("score: Double", scoreArg!!.description)
    }

    @Test
    fun `McpPromptFactory should create unique prompt names with type prefix`() {
        val factory = McpPromptFactory()
        val goal1 = NamedAndDescribed("goal1", "First goal")
        val goal2 = NamedAndDescribed("goal2", "Second goal")

        val spec1 = factory.syncPromptSpecificationForType(goal1, UserInput::class.java)
        val spec2 = factory.syncPromptSpecificationForType(goal2, UserInput::class.java)

        assertEquals("UserInput_goal1", spec1.prompt.name())
        assertEquals("UserInput_goal2", spec2.prompt.name())
        assertNotEquals(spec1.prompt.name(), spec2.prompt.name())
    }

    @Test
    fun `McpPromptFactory should handle custom excluded interfaces`() {

        data class AuditableTestClass(
            val name: String,
            val value: Int,
            override val createdBy: String,
            override val createdAt: java.time.Instant
        ) : AuditableInterface

        val factory = McpPromptFactory(excludedInterfaces = setOf(AuditableInterface::class.java))
        val goal = NamedAndDescribed("auditTest", "Test with auditable interface")

        val spec = factory.syncPromptSpecificationForType(
            goal,
            AuditableTestClass::class.java
        )

        val argumentNames = spec.prompt.arguments.map { it.name }
        assertEquals(2, argumentNames.size)
        assertTrue(argumentNames.contains("name"))
        assertTrue(argumentNames.contains("value"))
        assertFalse(argumentNames.contains("createdBy"))
        assertFalse(argumentNames.contains("createdAt"))
    }

    @Test
    fun `prompt handler should generate correct content structure`() {
        val factory = McpPromptFactory()
        val goal = NamedAndDescribed("contentTest", "Test content generation")

        val spec = factory.syncPromptSpecificationForType(
            goal,
            UserInput::class.java
        )

        val mockExchange = mockk<io.modelcontextprotocol.server.McpSyncServerExchange>()
        val request = McpSchema.GetPromptRequest(
            "contentTest",
            mapOf("content" to "Test input content")
        )

        val result = spec.promptHandler.apply(mockExchange, request)

        assertNotNull(result)
        assertEquals("contentTest-result", result.description)
        assertEquals(1, result.messages.size)

        val message = result.messages.first()
        assertEquals(McpSchema.Role.USER, message.role)
        assertTrue(message.content is McpSchema.TextContent)

        val textContent = message.content as McpSchema.TextContent
        assertTrue(textContent.text.contains("contentTest"))
        assertTrue(textContent.text.contains("Test content generation"))
        assertTrue(textContent.text.contains("content=Test input content"))
    }

    @Test
    fun `prompt handler should handle empty arguments gracefully`() {
        val factory = McpPromptFactory()
        val goal = NamedAndDescribed("emptyTest", "Test with empty arguments")

        val spec = factory.syncPromptSpecificationForType(
            goal,
            UserInput::class.java
        )

        val mockExchange = mockk<io.modelcontextprotocol.server.McpSyncServerExchange>()
        val request = McpSchema.GetPromptRequest("emptyTest", emptyMap())

        val result = spec.promptHandler.apply(mockExchange, request)

        assertNotNull(result)
        assertEquals("emptyTest-result", result.description)
        assertEquals(1, result.messages.size)

        val textContent = result.messages.first().content as McpSchema.TextContent
        assertTrue(textContent.text.contains("emptyTest"))
        assertTrue(textContent.text.contains("Test with empty arguments"))
        // Should handle empty arguments without throwing exception
        assertFalse(textContent.text.contains("="))
    }

    @Test
    fun `prompt handler should handle null argument values`() {
        val factory = McpPromptFactory()
        val goal = NamedAndDescribed("nullTest", "Test with null arguments")

        val spec = factory.syncPromptSpecificationForType(
            goal,
            UserInput::class.java
        )

        val mockExchange = mockk<io.modelcontextprotocol.server.McpSyncServerExchange>()
        val request = McpSchema.GetPromptRequest(
            "nullTest",
            mapOf("content" to null)
        )

        val result = spec.promptHandler.apply(mockExchange, request)

        assertNotNull(result)
        val textContent = result.messages.first().content as McpSchema.TextContent
        assertTrue(textContent.text.contains("content=null"))
    }

    @Test
    fun `factory should handle multiple excluded interfaces`() {

        data class MultiInterfaceClass(
            val name: String,
            override val field1: String,
            override val field2: String,
            override val timestamp: java.time.Instant
        ) : Interface1, Interface2, Timestamped

        val factory = McpPromptFactory(
            excludedInterfaces = setOf(
                Interface1::class.java,
                Interface2::class.java,
                Timestamped::class.java
            )
        )
        val goal = NamedAndDescribed("multiExcludeTest", "Test multiple exclusions")

        val spec = factory.syncPromptSpecificationForType(
            goal,
            MultiInterfaceClass::class.java
        )

        val argumentNames = spec.prompt.arguments.map { it.name }
        assertEquals(1, argumentNames.size)
        assertTrue(argumentNames.contains("name"))
        assertFalse(argumentNames.contains("field1"))
        assertFalse(argumentNames.contains("field2"))
        assertFalse(argumentNames.contains("timestamp"))
    }

    @Test
    fun `factory should preserve original goal properties when using custom name and description`() {
        val factory = McpPromptFactory()
        val goal = NamedAndDescribed("originalName", "Original description")

        val spec = factory.syncPromptSpecificationForType(
            goal,
            UserInput::class.java,
            name = "customName",
            description = "Custom description"
        )

        assertEquals("UserInput_customName", spec.prompt.name())
        assertEquals("Custom description", spec.prompt.description())

        // Test that the prompt content still references the custom name and description
        val mockExchange = mockk<io.modelcontextprotocol.server.McpSyncServerExchange>()
        val request = McpSchema.GetPromptRequest("customName", mapOf("content" to "test"))

        val result = spec.promptHandler.apply(mockExchange, request)
        val textContent = result.messages.first().content as McpSchema.TextContent

        assertTrue(textContent.text.contains("customName"))
        assertTrue(textContent.text.contains("Custom description"))
    }
}
