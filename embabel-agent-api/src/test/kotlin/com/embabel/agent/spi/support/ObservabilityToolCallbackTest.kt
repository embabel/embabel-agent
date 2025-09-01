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
package com.embabel.agent.spi.support

import io.micrometer.observation.ObservationRegistry
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

class ObservabilityToolCallbackTest {

    private lateinit var mockDelegate: ToolCallback
    private lateinit var mockObservationRegistry: ObservationRegistry
    private lateinit var mockToolDefinition: ToolDefinition

    @BeforeEach
    fun setUp() {
        mockDelegate = mockk(relaxed = true)
        mockObservationRegistry = mockk(relaxed = true)
        mockToolDefinition = mockk(relaxed = true)

        every { mockDelegate.toolDefinition } returns mockToolDefinition
        every { mockToolDefinition.name() } returns "test-tool"
    }

    @Test
    fun `should delegate getToolDefinition to underlying callback`() {
        val observabilityCallback = ObservabilityToolCallback(mockDelegate, mockObservationRegistry)
        val result = observabilityCallback.toolDefinition
        assertEquals(mockToolDefinition, result)
        verify { mockDelegate.toolDefinition }
    }

    @Test
    fun `should handle null observation registry`() {
        val observabilityCallback = ObservabilityToolCallback(mockDelegate, null)
        assertNotNull(observabilityCallback)
    }

    @Test
    fun `toString should return descriptive string with tool name`() {
        val observabilityCallback = ObservabilityToolCallback(mockDelegate, mockObservationRegistry)

        val result = observabilityCallback.toString()

        assertEquals("ObservabilityToolCallback(delegate=test-tool)", result)
    }

    @Test
    fun `should call delegate directly when observation registry is null`() {
        val observabilityCallback = ObservabilityToolCallback(mockDelegate, null)
        every { mockDelegate.call("test input") } returns "test output"

        val result = observabilityCallback.call("test input")

        assertEquals("test output", result)
        verify { mockDelegate.call("test input") }
    }

    @Test
    fun `should preserve delegate behavior exactly`() {
        val observabilityCallback = ObservabilityToolCallback(mockDelegate, null)
        every { mockDelegate.call("specific input") } returns "specific output"

        val result = observabilityCallback.call("specific input")

        assertEquals("specific output", result)
        verify(exactly = 1) { mockDelegate.call("specific input") }
    }

    @Test
    fun `should handle exceptions from delegate when no observation registry`() {
        val observabilityCallback = ObservabilityToolCallback(mockDelegate, null)
        val testException = RuntimeException("Test error")
        every { mockDelegate.call("error input") } throws testException

        val exception = assertThrows(RuntimeException::class.java) {
            observabilityCallback.call("error input")
        }

        assertEquals("Test error", exception.message)
        verify { mockDelegate.call("error input") }
    }

    @Test
    fun `should handle empty input and output strings`() {
        val observabilityCallback = ObservabilityToolCallback(mockDelegate, null)
        every { mockDelegate.call("") } returns ""

        val result = observabilityCallback.call("")

        assertEquals("", result)
        verify { mockDelegate.call("") }
    }

    @Test
    fun `should handle long input and output strings`() {
        val observabilityCallback = ObservabilityToolCallback(mockDelegate, null)
        val longInput = "x".repeat(10000)
        val longOutput = "y".repeat(10000)
        every { mockDelegate.call(longInput) } returns longOutput

        val result = observabilityCallback.call(longInput)

        assertEquals(longOutput, result)
        verify { mockDelegate.call(longInput) }
    }

    @Test
    fun `should not access tool definition unnecessarily`() {
        // Test that creating the callback doesn't immediately access tool definition
        clearMocks(mockDelegate)

        val observabilityCallback = ObservabilityToolCallback(mockDelegate, null)

        // Verify tool definition was not accessed during construction
        verify(exactly = 0) { mockDelegate.toolDefinition }

        // Now access it explicitly
        observabilityCallback.toolDefinition
        verify(exactly = 1) { mockDelegate.toolDefinition }
    }

    @Test
    fun `should handle multiple calls correctly`() {
        val observabilityCallback = ObservabilityToolCallback(mockDelegate, null)
        every { mockDelegate.call("call1") } returns "result1"
        every { mockDelegate.call("call2") } returns "result2"

        val result1 = observabilityCallback.call("call1")
        val result2 = observabilityCallback.call("call2")

        assertEquals("result1", result1)
        assertEquals("result2", result2)
        verify(exactly = 1) { mockDelegate.call("call1") }
        verify(exactly = 1) { mockDelegate.call("call2") }
    }
}
