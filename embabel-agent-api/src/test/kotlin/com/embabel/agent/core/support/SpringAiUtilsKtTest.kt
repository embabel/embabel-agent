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
package com.embabel.agent.core.support

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.annotation.support.FunnyTool
import com.embabel.agent.api.annotation.support.PersonWithReverseTool
import com.embabel.agent.api.common.ToolObject
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.tool.ToolCallback
import kotlin.test.assertEquals

class SpringAiUtilsKtTest {

    /**
     * Test class using Embabel's @Tool.Method annotation
     */
    class EmbabelToolMethodExample {
        @LlmTool(description = "Adds two numbers")
        fun add(
            a: Int,
            b: Int,
        ): Int = a + b

        @LlmTool(description = "Multiplies two numbers")
        fun multiply(
            a: Int,
            b: Int,
        ): Int = a * b
    }

    /**
     * Test class using both Spring AI @Tool and Embabel @Tool.Method
     */
    class MixedToolExample {
        @org.springframework.ai.tool.annotation.Tool(description = "Spring tool")
        fun springTool(): String = "spring"

        @LlmTool(description = "Embabel tool")
        fun embabelTool(): String = "embabel"
    }

    @Test
    fun `safelyGetTools from empty collection`() {
        val result = safelyGetToolCallbacks(emptySet())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `safelyGetTools from single instance in set`() {
        val result = safelyGetToolCallbacks(
            setOf(
                ToolObject(PersonWithReverseTool("John Doe"))
            )
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `safelyGetTools from single instance in ArrayList`() {
        val result = safelyGetToolCallbacks(arrayListOf(ToolObject.from(PersonWithReverseTool("John Doe"))))
        assertEquals(1, result.size)
    }

    @Test
    fun `safelyGetTools from single instance and tool callback`() {
        val tc = mockk<ToolCallback>()
        every { tc.toolDefinition.name() } returns "test"
        val result = safelyGetToolCallbacks(setOf(tc, PersonWithReverseTool("John Doe")).map { ToolObject.from(it) })
        assertEquals(2, result.size)
        assertEquals("test", result[1].toolDefinition.name())
    }

    @Test
    fun `safelyGetTools from ToolObject with multiple instances`() {
        val result = safelyGetToolCallbacks(
            listOf(
                ToolObject(
                    objects = listOf(
                        PersonWithReverseTool("John"),
                        FunnyTool(),
                    )
                )
            )
        )
        assertEquals(2, result.size)
        assertTrue(result.any { it.toolDefinition.name() == "reverse" })
        assertTrue(result.any { it.toolDefinition.name() == "thing" })
    }

    @Test
    fun `safelyGetTools from ToolObject with multiple instances deduplicates by tool name`() {
        val result = safelyGetToolCallbacks(
            listOf(
                ToolObject(
                    objects = listOf(
                        PersonWithReverseTool("John"),
                        PersonWithReverseTool("Jane"),
                    )
                )
            )
        )
        assertEquals(1, result.size)
        assertEquals("reverse", result[0].toolDefinition.name())
    }

    @Test
    fun `safelyGetTools from ToolObject with multiple instances applies naming strategy`() {
        val result = safelyGetToolCallbacks(
            listOf(
                ToolObject(
                    objects = listOf(
                        PersonWithReverseTool("John"),
                        FunnyTool(),
                    ),
                    namingStrategy = { "prefixed_$it" },
                )
            )
        )
        assertEquals(2, result.size)
        assertTrue(result.any { it.toolDefinition.name() == "prefixed_reverse" })
        assertTrue(result.any { it.toolDefinition.name() == "prefixed_thing" })
    }

    @Test
    fun `safelyGetTools from ToolObject with multiple instances applies filter`() {
        val result = safelyGetToolCallbacks(
            listOf(
                ToolObject(
                    objects = listOf(
                        PersonWithReverseTool("John"),
                        FunnyTool(),
                    ),
                    filter = { it == "reverse" },
                )
            )
        )
        assertEquals(1, result.size)
        assertEquals("reverse", result[0].toolDefinition.name())
    }

    @Test
    fun `safelyGetTools from multiple ToolObjects each with multiple instances`() {
        val result = safelyGetToolCallbacks(
            listOf(
                ToolObject(
                    objects = listOf(
                        PersonWithReverseTool("John"),
                    )
                ),
                ToolObject(
                    objects = listOf(
                        FunnyTool(),
                    )
                ),
            )
        )
        assertEquals(2, result.size)
        assertTrue(result.any { it.toolDefinition.name() == "reverse" })
        assertTrue(result.any { it.toolDefinition.name() == "thing" })
    }

    @Test
    fun `safelyGetTools from ToolObject with ToolCallback and regular object`() {
        val tc = mockk<ToolCallback>()
        every { tc.toolDefinition.name() } returns "mockTool"
        val result = safelyGetToolCallbacks(
            listOf(
                ToolObject(
                    objects = listOf(
                        tc,
                        PersonWithReverseTool("John"),
                    )
                )
            )
        )
        assertEquals(2, result.size)
        assertTrue(result.any { it.toolDefinition.name() == "mockTool" })
        assertTrue(result.any { it.toolDefinition.name() == "reverse" })
    }

    @Test
    fun `safelyGetTools scans for Embabel Tool_Method annotations`() {
        val result = safelyGetToolCallbacks(
            listOf(ToolObject(EmbabelToolMethodExample()))
        )
        assertEquals(2, result.size)
        assertTrue(result.any { it.toolDefinition.name() == "add" })
        assertTrue(result.any { it.toolDefinition.name() == "multiply" })
    }

    @Test
    fun `safelyGetTools scans both Spring AI and Embabel annotations`() {
        val result = safelyGetToolCallbacks(
            listOf(ToolObject(MixedToolExample()))
        )
        assertEquals(2, result.size)
        assertTrue(result.any { it.toolDefinition.name() == "springTool" })
        assertTrue(result.any { it.toolDefinition.name() == "embabelTool" })
    }

    @Test
    fun `safelyGetTools applies naming strategy to Embabel tools`() {
        val result = safelyGetToolCallbacks(
            listOf(
                ToolObject(
                    objects = listOf(EmbabelToolMethodExample()),
                    namingStrategy = { "prefixed_$it" },
                )
            )
        )
        assertEquals(2, result.size)
        assertTrue(result.any { it.toolDefinition.name() == "prefixed_add" })
        assertTrue(result.any { it.toolDefinition.name() == "prefixed_multiply" })
    }

    @Test
    fun `safelyGetTools applies filter to Embabel tools`() {
        val result = safelyGetToolCallbacks(
            listOf(
                ToolObject(
                    objects = listOf(EmbabelToolMethodExample()),
                    filter = { it == "add" },
                )
            )
        )
        assertEquals(1, result.size)
        assertEquals("add", result[0].toolDefinition.name())
    }

}
