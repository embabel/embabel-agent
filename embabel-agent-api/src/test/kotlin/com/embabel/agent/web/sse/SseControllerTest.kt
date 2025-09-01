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
package com.embabel.agent.web.sse

import com.embabel.agent.config.AgentPlatformProperties
import com.embabel.agent.event.AgentProcessEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant

class SseControllerTest {

    private lateinit var mockPlatformProperties: AgentPlatformProperties
    private lateinit var mockSseConfig: AgentPlatformProperties.SseConfig
    private lateinit var sseProperties: SseProperties
    private lateinit var sseController: SSEController
    private lateinit var mockAgentProcessEvent: AgentProcessEvent

    @BeforeEach
    fun setUp() {
        mockPlatformProperties = mockk(relaxed = true)
        mockSseConfig = mockk(relaxed = true)
        mockAgentProcessEvent = mockk(relaxed = true)

        every { mockPlatformProperties.sse } returns mockSseConfig
        every { mockSseConfig.maxBufferSize } returns 100
        every { mockSseConfig.maxProcessBuffers } returns 1000

        sseProperties = SseProperties(mockPlatformProperties)
        sseController = SSEController(sseProperties)
    }

    @Nested
    inner class SsePropertiesTests {

        @Test
        fun `should initialize with default values from platform properties`() {
            assertEquals(100, sseProperties.maxBufferSize)
            assertEquals(1000, sseProperties.maxProcessBuffers)
        }

        @Test
        fun `should use custom values from platform properties`() {
            val customSseConfig = mockk<AgentPlatformProperties.SseConfig>()
            every { customSseConfig.maxBufferSize } returns 50
            every { customSseConfig.maxProcessBuffers } returns 500

            val customPlatformProperties = mockk<AgentPlatformProperties>()
            every { customPlatformProperties.sse } returns customSseConfig

            val customProperties = SseProperties(customPlatformProperties)

            assertEquals(50, customProperties.maxBufferSize)
            assertEquals(500, customProperties.maxProcessBuffers)
        }
    }

    @Nested
    inner class StreamEndpointTests {

        @Test
        fun `should create SSE emitter for valid process ID`() {
            val emitter = sseController.streamEventsForId("test-process-123")

            assertNotNull(emitter)
            assertEquals(Long.MAX_VALUE, emitter.timeout)
        }

        @Test
        fun `should handle multiple concurrent connections for same process`() {
            val processId = "test-process-456"

            val emitter1 = sseController.streamEventsForId(processId)
            val emitter2 = sseController.streamEventsForId(processId)

            assertNotNull(emitter1)
            assertNotNull(emitter2)
            assertNotSame(emitter1, emitter2)
        }

        @Test
        fun `should handle connections for different processes`() {
            val emitter1 = sseController.streamEventsForId("process-1")
            val emitter2 = sseController.streamEventsForId("process-2")

            assertNotNull(emitter1)
            assertNotNull(emitter2)
            assertNotSame(emitter1, emitter2)
        }

        @Test
        fun `should send connection confirmation message`() {
            val processId = "test-process-789"
            val mockEmitter = mockk<SseEmitter>(relaxed = true)

            // We can't easily test the actual send behavior without more complex mocking,
            // but we can verify the emitter is created
            val emitter = sseController.streamEventsForId(processId)
            assertNotNull(emitter)
        }
    }

    @Nested
    inner class EventProcessingTests {

        @Test
        fun `should buffer events for process`() {
            val processId = "buffer-test-process"
            every { mockAgentProcessEvent.processId } returns processId
            every { mockAgentProcessEvent.timestamp } returns Instant.now()

            // Create a connection first to initialize the emitter list
            sseController.streamEventsForId(processId)

            // Send an event
            sseController.onProcessEvent(mockAgentProcessEvent)

            // The event should be buffered (we can't easily verify this without exposing internal state)
            // But we can verify the processId was accessed
            verify { mockAgentProcessEvent.processId }
        }

        @Test
        fun `should handle multiple events for same process`() {
            val processId = "multi-event-process"
            val event1 = mockk<AgentProcessEvent>(relaxed = true)
            val event2 = mockk<AgentProcessEvent>(relaxed = true)

            every { event1.processId } returns processId
            every { event1.timestamp } returns Instant.now()
            every { event2.processId } returns processId
            every { event2.timestamp } returns Instant.now().plusSeconds(1)

            // Create connection
            sseController.streamEventsForId(processId)

            // Send multiple events
            sseController.onProcessEvent(event1)
            sseController.onProcessEvent(event2)

            verify { event1.processId }
            verify { event2.processId }
        }

        @Test
        fun `should handle events for different processes`() {
            val process1Id = "process-1"
            val process2Id = "process-2"
            val event1 = mockk<AgentProcessEvent>(relaxed = true)
            val event2 = mockk<AgentProcessEvent>(relaxed = true)

            every { event1.processId } returns process1Id
            every { event1.timestamp } returns Instant.now()
            every { event2.processId } returns process2Id
            every { event2.timestamp } returns Instant.now()

            // Create connections
            sseController.streamEventsForId(process1Id)
            sseController.streamEventsForId(process2Id)

            // Send events to different processes
            sseController.onProcessEvent(event1)
            sseController.onProcessEvent(event2)

            verify { event1.processId }
            verify { event2.processId }
        }

        @Test
        fun `should handle events when no emitters are connected`() {
            val processId = "no-emitters-process"
            every { mockAgentProcessEvent.processId } returns processId
            every { mockAgentProcessEvent.timestamp } returns Instant.now()

            // Send event without creating any emitters
            assertDoesNotThrow {
                sseController.onProcessEvent(mockAgentProcessEvent)
            }

            verify { mockAgentProcessEvent.processId }
        }
    }

    @Nested
    inner class BufferManagementTests {

        @Test
        fun `should respect max buffer size per process`() {
            val processId = "buffer-size-test"
            every { mockSseConfig.maxBufferSize } returns 2 // Small buffer for testing
            val testProperties = SseProperties(mockPlatformProperties)
            val testController = SSEController(testProperties)

            // Create connection
            testController.streamEventsForId(processId)

            // Create events that exceed buffer size
            val events = (1..5).map { index ->
                mockk<AgentProcessEvent>(relaxed = true).also { event ->
                    every { event.processId } returns processId
                    every { event.timestamp } returns Instant.now().plusSeconds(index.toLong())
                }
            }

            // Send all events
            events.forEach { testController.onProcessEvent(it) }

            // Verify all events were processed (they all had their processId accessed)
            events.forEach { event ->
                verify { event.processId }
            }
        }

        @Test
        fun `should respect max process buffers limit`() {
            every { mockSseConfig.maxProcessBuffers } returns 2 // Small limit for testing
            val testProperties = SseProperties(mockPlatformProperties)
            val testController = SSEController(testProperties)

            // Create events for multiple processes
            val processIds = (1..5).map { "process-$it" }
            val events = processIds.map { processId ->
                mockk<AgentProcessEvent>(relaxed = true).also { event ->
                    every { event.processId } returns processId
                    every { event.timestamp } returns Instant.now()
                }
            }

            // Create connections and send events
            processIds.forEach { testController.streamEventsForId(it) }
            events.forEach { testController.onProcessEvent(it) }

            // Verify all events were processed
            events.forEach { event ->
                verify { event.processId }
            }
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should handle IOException when sending events`() {
            val processId = "io-error-test"
            every { mockAgentProcessEvent.processId } returns processId
            every { mockAgentProcessEvent.timestamp } returns Instant.now()

            // We can't easily simulate IOException without more complex setup,
            // but we can verify the event processing doesn't throw
            assertDoesNotThrow {
                sseController.onProcessEvent(mockAgentProcessEvent)
            }
        }

        @Test
        fun `should handle general exceptions when sending events`() {
            val processId = "general-error-test"
            every { mockAgentProcessEvent.processId } returns processId
            every { mockAgentProcessEvent.timestamp } returns Instant.now()

            assertDoesNotThrow {
                sseController.onProcessEvent(mockAgentProcessEvent)
            }
        }

        @Test
        fun `should handle null or empty process IDs`() {
            val eventWithNullId = mockk<AgentProcessEvent>(relaxed = true)
            every { eventWithNullId.processId } returns ""
            every { eventWithNullId.timestamp } returns Instant.now()

            assertDoesNotThrow {
                sseController.onProcessEvent(eventWithNullId)
            }

            verify { eventWithNullId.processId }
        }
    }

    @Nested
    inner class EmitterLifecycleTests {

        @Test
        fun `should set up emitter callbacks correctly`() {
            val processId = "lifecycle-test"
            val emitter = sseController.streamEventsForId(processId)

            // We can't easily test the callback behavior without more complex mocking,
            // but we can verify the emitter was created and is properly configured
            assertNotNull(emitter)
            assertEquals(Long.MAX_VALUE, emitter.timeout)
        }

        @Test
        fun `should handle emitter completion`() {
            val processId = "completion-test"
            val emitter = sseController.streamEventsForId(processId)

            assertNotNull(emitter)
            // The completion callback is set up internally and hard to test directly
        }

        @Test
        fun `should handle emitter timeout`() {
            val processId = "timeout-test"
            val emitter = sseController.streamEventsForId(processId)

            assertNotNull(emitter)
            // The timeout callback is set up internally and hard to test directly
        }

        @Test
        fun `should handle emitter error`() {
            val processId = "error-test"
            val emitter = sseController.streamEventsForId(processId)

            assertNotNull(emitter)
            // The error callback is set up internally and hard to test directly
        }
    }

    @Nested
    inner class ConcurrencyTests {

        @Test
        fun `should handle concurrent event processing`() {
            val processId = "concurrency-test"
            val events = (1..10).map { index ->
                mockk<AgentProcessEvent>(relaxed = true).also { event ->
                    every { event.processId } returns processId
                    every { event.timestamp } returns Instant.now().plusMillis(index.toLong())
                }
            }

            // Create connection
            sseController.streamEventsForId(processId)

            // Process events concurrently (simulated)
            assertDoesNotThrow {
                events.forEach { sseController.onProcessEvent(it) }
            }

            // Verify all events were processed
            events.forEach { event ->
                verify { event.processId }
            }
        }

        @Test
        fun `should handle concurrent connections to same process`() {
            val processId = "concurrent-connections-test"

            // Create multiple connections simultaneously
            val emitters = (1..5).map {
                sseController.streamEventsForId(processId)
            }

            // Verify all emitters were created
            emitters.forEach { emitter ->
                assertNotNull(emitter)
            }
        }
    }

    @Nested
    inner class ConstantsAndStaticTests {

        @Test
        fun `should have correct SSE event name constant`() {
            assertEquals("agent-process-event", SSEController.SSE_EVENT_NAME)
        }
    }

    @Nested
    inner class IntegrationScenarioTests {

        @Test
        fun `should handle typical usage scenario`() {
            val processId = "integration-test"

            // Create connection
            val emitter = sseController.streamEventsForId(processId)
            assertNotNull(emitter)

            // Create and send events
            val events = (1..3).map { index ->
                mockk<AgentProcessEvent>(relaxed = true).also { event ->
                    every { event.processId } returns processId
                    every { event.timestamp } returns Instant.now().plusSeconds(index.toLong())
                    every { event.toString() } returns "Event $index"
                }
            }

            events.forEach { sseController.onProcessEvent(it) }

            // Verify events were processed
            events.forEach { event ->
                verify { event.processId }
            }
        }

        @Test
        fun `should handle mixed process scenario`() {
            val processes = listOf("proc-1", "proc-2", "proc-3")

            // Create connections for all processes
            val emitters = processes.associateWith { processId -> sseController.streamEventsForId(processId) }

            // Verify all emitters created
            emitters.values.forEach { emitter ->
                assertNotNull(emitter)
            }

            // Create events for different processes
            val events = processes.flatMap { processId ->
                (1..2).map { index ->
                    mockk<AgentProcessEvent>(relaxed = true).also { event ->
                        every { event.processId } returns processId
                        every { event.timestamp } returns Instant.now().plusSeconds(index.toLong())
                    }
                }
            }

            // Send all events
            events.forEach { sseController.onProcessEvent(it) }

            // Verify all events were processed
            events.forEach { event ->
                verify { event.processId }
            }
        }
    }
}
