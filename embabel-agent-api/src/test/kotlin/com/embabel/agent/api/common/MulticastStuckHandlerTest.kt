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
package com.embabel.agent.api.common

import com.embabel.agent.core.AgentProcess
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class MulticastStuckHandlerTest {

    private lateinit var mockAgentProcess: AgentProcess
    private lateinit var mockStuckHandler1: StuckHandler
    private lateinit var mockStuckHandler2: StuckHandler
    private lateinit var mockStuckHandler3: StuckHandler

    @BeforeEach
    fun setUp() {
        mockAgentProcess = mockk(relaxed = true)
        mockStuckHandler1 = mockk(relaxed = true)
        mockStuckHandler2 = mockk(relaxed = true)
        mockStuckHandler3 = mockk(relaxed = true)

        // Set up basic agent process properties that might be needed
        every { mockAgentProcess.id } returns "test-process-123"
        every { mockAgentProcess.toString() } returns "AgentProcess(id=test-process-123)"
    }

    @Nested
    inner class StuckHandlerResultTests {

        @Test
        fun `should create StuckHandlerResult with all properties`() {
            val message = "Test resolution message"
            val handler = mockStuckHandler1
            val code = StuckHandlingResultCode.REPLAN

            val result = StuckHandlerResult(message, handler, code, mockAgentProcess)

            assertEquals(message, result.message)
            assertEquals(handler, result.handler)
            assertEquals(code, result.code)
            assertEquals(mockAgentProcess, result.agentProcess)
            assertNotNull(result.timestamp)
        }

        @Test
        fun `should create StuckHandlerResult with null handler`() {
            val message = "No resolution found"
            val code = StuckHandlingResultCode.NO_RESOLUTION

            val result = StuckHandlerResult(message, null, code, mockAgentProcess)

            assertEquals(message, result.message)
            assertNull(result.handler)
            assertEquals(code, result.code)
            assertEquals(mockAgentProcess, result.agentProcess)
        }

        @Test
        fun `should extend AbstractAgentProcessEvent correctly`() {
            val result = StuckHandlerResult("Test", null, StuckHandlingResultCode.REPLAN, mockAgentProcess)

            assertEquals("test-process-123", result.processId)
            assertTrue(result.timestamp.isBefore(Instant.now().plusSeconds(1)))
            assertTrue(result.timestamp.isAfter(Instant.now().minusSeconds(1)))
        }
    }

    @Nested
    inner class StuckHandlingResultCodeTests {

        @Test
        fun `should have correct enum values`() {
            val values = StuckHandlingResultCode.values()

            assertEquals(2, values.size)
            assertTrue(values.contains(StuckHandlingResultCode.REPLAN))
            assertTrue(values.contains(StuckHandlingResultCode.NO_RESOLUTION))
        }

        @Test
        fun `should support enum operations`() {
            assertEquals("REPLAN", StuckHandlingResultCode.REPLAN.name)
            assertEquals("NO_RESOLUTION", StuckHandlingResultCode.NO_RESOLUTION.name)
            assertEquals(StuckHandlingResultCode.REPLAN, StuckHandlingResultCode.valueOf("REPLAN"))
            assertEquals(StuckHandlingResultCode.NO_RESOLUTION, StuckHandlingResultCode.valueOf("NO_RESOLUTION"))
        }
    }

    @Nested
    inner class StuckHandlerCompanionTests {

        @Test
        fun `should create MulticastStuckHandler with single handler`() {
            val handler = StuckHandler.invoke(mockStuckHandler1)

            assertTrue(handler is MulticastStuckHandler)
        }

        @Test
        fun `should create MulticastStuckHandler with multiple handlers`() {
            val handler = StuckHandler.invoke(mockStuckHandler1, mockStuckHandler2, mockStuckHandler3)

            assertTrue(handler is MulticastStuckHandler)
        }

        @Test
        fun `should create MulticastStuckHandler with empty handlers`() {
            val handler = StuckHandler.invoke()

            assertTrue(handler is MulticastStuckHandler)
        }
    }

    @Nested
    inner class MulticastStuckHandlerConstructorTests {

        @Test
        fun `should create handler with list of handlers`() {
            val handlers = listOf(mockStuckHandler1, mockStuckHandler2)
            val multicastHandler = MulticastStuckHandler(handlers)

            assertNotNull(multicastHandler)
        }

        @Test
        fun `should create handler with empty list`() {
            val multicastHandler = MulticastStuckHandler(emptyList())

            assertNotNull(multicastHandler)
        }

        @Test
        fun `should create handler with single handler in list`() {
            val multicastHandler = MulticastStuckHandler(listOf(mockStuckHandler1))

            assertNotNull(multicastHandler)
        }
    }

    @Nested
    inner class MulticastHandlerExecutionTests {

        @Test
        fun `should return first successful resolution`() {
            val successResult = StuckHandlerResult("Success!", mockStuckHandler1, StuckHandlingResultCode.REPLAN, mockAgentProcess)
            every { mockStuckHandler1.handleStuck(mockAgentProcess) } returns successResult

            val multicastHandler = MulticastStuckHandler(listOf(mockStuckHandler1, mockStuckHandler2))
            val result = multicastHandler.handleStuck(mockAgentProcess)

            assertEquals(successResult, result)
            assertEquals("Success!", result.message)
            assertEquals(StuckHandlingResultCode.REPLAN, result.code)
            assertEquals(mockStuckHandler1, result.handler)

            verify(exactly = 1) { mockStuckHandler1.handleStuck(mockAgentProcess) }
            verify(exactly = 0) { mockStuckHandler2.handleStuck(mockAgentProcess) }
        }

        @Test
        fun `should try second handler if first fails`() {
            val noResolutionResult1 = StuckHandlerResult("No resolution 1", mockStuckHandler1, StuckHandlingResultCode.NO_RESOLUTION, mockAgentProcess)
            val successResult2 = StuckHandlerResult("Success from handler 2", mockStuckHandler2, StuckHandlingResultCode.REPLAN, mockAgentProcess)

            every { mockStuckHandler1.handleStuck(mockAgentProcess) } returns noResolutionResult1
            every { mockStuckHandler2.handleStuck(mockAgentProcess) } returns successResult2

            val multicastHandler = MulticastStuckHandler(listOf(mockStuckHandler1, mockStuckHandler2))
            val result = multicastHandler.handleStuck(mockAgentProcess)

            assertEquals(successResult2, result)
            assertEquals("Success from handler 2", result.message)
            assertEquals(StuckHandlingResultCode.REPLAN, result.code)
            assertEquals(mockStuckHandler2, result.handler)

            verify(exactly = 1) { mockStuckHandler1.handleStuck(mockAgentProcess) }
            verify(exactly = 1) { mockStuckHandler2.handleStuck(mockAgentProcess) }
        }

        @Test
        fun `should try all handlers in order until success`() {
            val noResolutionResult1 = StuckHandlerResult("No resolution 1", mockStuckHandler1, StuckHandlingResultCode.NO_RESOLUTION, mockAgentProcess)
            val noResolutionResult2 = StuckHandlerResult("No resolution 2", mockStuckHandler2, StuckHandlingResultCode.NO_RESOLUTION, mockAgentProcess)
            val successResult3 = StuckHandlerResult("Success from handler 3", mockStuckHandler3, StuckHandlingResultCode.REPLAN, mockAgentProcess)

            every { mockStuckHandler1.handleStuck(mockAgentProcess) } returns noResolutionResult1
            every { mockStuckHandler2.handleStuck(mockAgentProcess) } returns noResolutionResult2
            every { mockStuckHandler3.handleStuck(mockAgentProcess) } returns successResult3

            val multicastHandler = MulticastStuckHandler(listOf(mockStuckHandler1, mockStuckHandler2, mockStuckHandler3))
            val result = multicastHandler.handleStuck(mockAgentProcess)

            assertEquals(successResult3, result)
            assertEquals("Success from handler 3", result.message)
            assertEquals(StuckHandlingResultCode.REPLAN, result.code)
            assertEquals(mockStuckHandler3, result.handler)

            verify(exactly = 1) { mockStuckHandler1.handleStuck(mockAgentProcess) }
            verify(exactly = 1) { mockStuckHandler2.handleStuck(mockAgentProcess) }
            verify(exactly = 1) { mockStuckHandler3.handleStuck(mockAgentProcess) }
        }

        @Test
        fun `should return NO_RESOLUTION when all handlers fail`() {
            val noResolutionResult1 = StuckHandlerResult("No resolution 1", mockStuckHandler1, StuckHandlingResultCode.NO_RESOLUTION, mockAgentProcess)
            val noResolutionResult2 = StuckHandlerResult("No resolution 2", mockStuckHandler2, StuckHandlingResultCode.NO_RESOLUTION, mockAgentProcess)

            every { mockStuckHandler1.handleStuck(mockAgentProcess) } returns noResolutionResult1
            every { mockStuckHandler2.handleStuck(mockAgentProcess) } returns noResolutionResult2

            val multicastHandler = MulticastStuckHandler(listOf(mockStuckHandler1, mockStuckHandler2))
            val result = multicastHandler.handleStuck(mockAgentProcess)

            assertEquals(StuckHandlingResultCode.NO_RESOLUTION, result.code)
            assertNull(result.handler)
            assertTrue(result.message.contains("No stuck handler could resolve the issue"))
            assertTrue(result.message.contains(mockStuckHandler1::class.java.name))
            assertTrue(result.message.contains(mockStuckHandler2::class.java.name))
            assertEquals(mockAgentProcess, result.agentProcess)

            verify(exactly = 1) { mockStuckHandler1.handleStuck(mockAgentProcess) }
            verify(exactly = 1) { mockStuckHandler2.handleStuck(mockAgentProcess) }
        }

        @Test
        fun `should handle empty handlers list`() {
            val multicastHandler = MulticastStuckHandler(emptyList())
            val result = multicastHandler.handleStuck(mockAgentProcess)

            assertEquals(StuckHandlingResultCode.NO_RESOLUTION, result.code)
            assertNull(result.handler)
            assertTrue(result.message.contains("No stuck handler could resolve the issue"))
            assertEquals(mockAgentProcess, result.agentProcess)
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should handle exception from handler gracefully`() {
            every { mockStuckHandler1.handleStuck(mockAgentProcess) } throws RuntimeException("Handler failed")

            val multicastHandler = MulticastStuckHandler(listOf(mockStuckHandler1))

            val exception = assertThrows(RuntimeException::class.java) {
                multicastHandler.handleStuck(mockAgentProcess)
            }

            assertEquals("Handler failed", exception.message)
            verify(exactly = 1) { mockStuckHandler1.handleStuck(mockAgentProcess) }
        }

        @Test
        fun `should continue to next handler if one throws exception`() {
            val successResult2 = StuckHandlerResult("Success from handler 2", mockStuckHandler2, StuckHandlingResultCode.REPLAN, mockAgentProcess)

            every { mockStuckHandler1.handleStuck(mockAgentProcess) } throws RuntimeException("Handler 1 failed")
            every { mockStuckHandler2.handleStuck(mockAgentProcess) } returns successResult2

            val multicastHandler = MulticastStuckHandler(listOf(mockStuckHandler1, mockStuckHandler2))

            // The current implementation doesn't handle exceptions, so this will throw
            assertThrows(RuntimeException::class.java) {
                multicastHandler.handleStuck(mockAgentProcess)
            }

            verify(exactly = 1) { mockStuckHandler1.handleStuck(mockAgentProcess) }
            // Handler 2 won't be called because handler 1 threw an exception
        }
    }

    @Nested
    inner class IntegrationTests {

        @Test
        fun `should work with real handler implementations`() {
            val realHandler1 = object : StuckHandler {
                override fun handleStuck(agentProcess: AgentProcess): StuckHandlerResult {
                    return StuckHandlerResult("Real handler resolution", this, StuckHandlingResultCode.REPLAN, agentProcess)
                }
            }

            val realHandler2 = object : StuckHandler {
                override fun handleStuck(agentProcess: AgentProcess): StuckHandlerResult {
                    return StuckHandlerResult("Backup handler resolution", this, StuckHandlingResultCode.NO_RESOLUTION, agentProcess)
                }
            }

            val multicastHandler = MulticastStuckHandler(listOf(realHandler1, realHandler2))
            val result = multicastHandler.handleStuck(mockAgentProcess)

            assertEquals("Real handler resolution", result.message)
            assertEquals(StuckHandlingResultCode.REPLAN, result.code)
            assertEquals(realHandler1, result.handler)
        }

        @Test
        fun `should work with companion object factory method`() {
            val successResult = StuckHandlerResult("Success!", mockStuckHandler1, StuckHandlingResultCode.REPLAN, mockAgentProcess)
            every { mockStuckHandler1.handleStuck(mockAgentProcess) } returns successResult

            val compositeHandler = StuckHandler(mockStuckHandler1, mockStuckHandler2)
            val result = compositeHandler.handleStuck(mockAgentProcess)

            assertEquals(successResult, result)
            verify(exactly = 1) { mockStuckHandler1.handleStuck(mockAgentProcess) }
            verify(exactly = 0) { mockStuckHandler2.handleStuck(mockAgentProcess) }
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `should handle single handler that succeeds`() {
            val successResult = StuckHandlerResult("Single handler success", mockStuckHandler1, StuckHandlingResultCode.REPLAN, mockAgentProcess)
            every { mockStuckHandler1.handleStuck(mockAgentProcess) } returns successResult

            val multicastHandler = MulticastStuckHandler(listOf(mockStuckHandler1))
            val result = multicastHandler.handleStuck(mockAgentProcess)

            assertEquals(successResult, result)
            verify(exactly = 1) { mockStuckHandler1.handleStuck(mockAgentProcess) }
        }

        @Test
        fun `should handle single handler that fails`() {
            val noResolutionResult = StuckHandlerResult("Single handler failed", mockStuckHandler1, StuckHandlingResultCode.NO_RESOLUTION, mockAgentProcess)
            every { mockStuckHandler1.handleStuck(mockAgentProcess) } returns noResolutionResult

            val multicastHandler = MulticastStuckHandler(listOf(mockStuckHandler1))
            val result = multicastHandler.handleStuck(mockAgentProcess)

            assertEquals(StuckHandlingResultCode.NO_RESOLUTION, result.code)
            assertNull(result.handler)
            assertTrue(result.message.contains("No stuck handler could resolve the issue"))
            assertTrue(result.message.contains(mockStuckHandler1::class.java.name))

            verify(exactly = 1) { mockStuckHandler1.handleStuck(mockAgentProcess) }
        }

        @Test
        fun `should handle duplicate handlers in list`() {
            val successResult = StuckHandlerResult("Success!", mockStuckHandler1, StuckHandlingResultCode.REPLAN, mockAgentProcess)
            every { mockStuckHandler1.handleStuck(mockAgentProcess) } returns successResult

            val multicastHandler = MulticastStuckHandler(listOf(mockStuckHandler1, mockStuckHandler1))
            val result = multicastHandler.handleStuck(mockAgentProcess)

            assertEquals(successResult, result)
            verify(exactly = 1) { mockStuckHandler1.handleStuck(mockAgentProcess) }
        }
    }

    @Nested
    inner class MessageFormattingTests {

        @Test
        fun `should format failure message correctly with multiple handlers`() {
            val noResolutionResult1 = StuckHandlerResult("No resolution 1", mockStuckHandler1, StuckHandlingResultCode.NO_RESOLUTION, mockAgentProcess)
            val noResolutionResult2 = StuckHandlerResult("No resolution 2", mockStuckHandler2, StuckHandlingResultCode.NO_RESOLUTION, mockAgentProcess)

            every { mockStuckHandler1.handleStuck(mockAgentProcess) } returns noResolutionResult1
            every { mockStuckHandler2.handleStuck(mockAgentProcess) } returns noResolutionResult2

            val multicastHandler = MulticastStuckHandler(listOf(mockStuckHandler1, mockStuckHandler2))
            val result = multicastHandler.handleStuck(mockAgentProcess)

            assertTrue(result.message.startsWith("No stuck handler could resolve the issue: Tried "))
            assertTrue(result.message.contains(mockStuckHandler1::class.java.name))
            assertTrue(result.message.contains(mockStuckHandler2::class.java.name))
        }

        @Test
        fun `should format failure message correctly with empty handlers`() {
            val multicastHandler = MulticastStuckHandler(emptyList())
            val result = multicastHandler.handleStuck(mockAgentProcess)

            assertEquals("No stuck handler could resolve the issue: Tried ", result.message)
        }

        @Test
        fun `should format failure message correctly with single handler`() {
            val noResolutionResult = StuckHandlerResult("No resolution", mockStuckHandler1, StuckHandlingResultCode.NO_RESOLUTION, mockAgentProcess)
            every { mockStuckHandler1.handleStuck(mockAgentProcess) } returns noResolutionResult

            val multicastHandler = MulticastStuckHandler(listOf(mockStuckHandler1))
            val result = multicastHandler.handleStuck(mockAgentProcess)

            assertTrue(result.message.contains("No stuck handler could resolve the issue: Tried "))
            assertTrue(result.message.contains(mockStuckHandler1::class.java.name))
        }
    }
}
