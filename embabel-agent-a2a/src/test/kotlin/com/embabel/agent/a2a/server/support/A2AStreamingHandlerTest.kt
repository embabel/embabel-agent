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
package com.embabel.agent.a2a.server.support

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.a2a.spec.Message
import io.a2a.spec.Task
import io.a2a.spec.TaskState
import io.a2a.spec.TaskStatus
import io.a2a.spec.TaskStatusUpdateEvent
import io.a2a.spec.TextPart
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class A2AStreamingHandlerTest {

    private lateinit var streamingHandler: A2AStreamingHandler
    private lateinit var taskStateManager: TaskStateManager
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setup() {
        objectMapper = jacksonObjectMapper()
        taskStateManager = TaskStateManager()
        streamingHandler = A2AStreamingHandler(objectMapper, taskStateManager)
    }

    @Test
    fun `should create stream without task registration`() {
        // When
        val emitter = streamingHandler.createStream("stream-1")

        // Then
        assertNotNull(emitter)
    }

    @Test
    fun `should create stream with task registration`() {
        // When
        val emitter = streamingHandler.createStream("stream-1", "task-1", "ctx-1")

        // Then
        assertNotNull(emitter)
        assertTrue(taskStateManager.taskExists("task-1"))
        assertEquals("stream-1", taskStateManager.getStreamId("task-1"))
    }

    @Test
    fun `should close stream`() {
        // Given
        val emitter = streamingHandler.createStream("stream-1", "task-1", "ctx-1")

        // When
        streamingHandler.closeStream("stream-1")

        // Then - stream is closed, no exception thrown
    }

    @Test
    fun `should handle resubscribe to existing task`() {
        // Given - create initial task with events
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
        val message = Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(listOf(TextPart("Hello")))
            .build()
        taskStateManager.recordEvent("task-1", message)

        // When
        val emitter = streamingHandler.resubscribeToTask("task-1", "stream-2")

        // Then
        assertNotNull(emitter)
        assertEquals("stream-2", taskStateManager.getStreamId("task-1"))
    }

    @Test
    fun `should throw exception when resubscribing to non-existent task`() {
        // When/Then
        assertThrows(IllegalArgumentException::class.java) {
            streamingHandler.resubscribeToTask("non-existent", "stream-1")
        }
    }

    @Test
    fun `should record events when sending with task ID`() {
        // Given
        streamingHandler.createStream("stream-1", "task-1", "ctx-1")
        val statusUpdate = TaskStatusUpdateEvent.Builder()
            .taskId("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.WORKING))
            .build()

        // When
        streamingHandler.sendStreamEvent("stream-1", statusUpdate, "task-1")

        // Then
        val events = taskStateManager.getTaskEvents("task-1")
        assertEquals(1, events.size)
        assertTrue(events[0] is TaskStatusUpdateEvent)
    }

    @Test
    fun `should handle send event without task ID`() {
        // Given
        streamingHandler.createStream("stream-1")
        val message = Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(listOf(TextPart("Hello")))
            .build()

        // When - should not throw exception
        streamingHandler.sendStreamEvent("stream-1", message)

        // Then - no exception thrown
    }

    @Test
    fun `should handle send event to non-existent stream gracefully`() {
        // Given
        val message = Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(listOf(TextPart("Hello")))
            .build()

        // When/Then - should not throw exception, just log warning
        assertDoesNotThrow {
            streamingHandler.sendStreamEvent("non-existent", message)
        }
    }

    @Test
    fun `should close stream after resubscribing to completed task`() {
        // Given - create completed task
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
        val completedTask = Task.Builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.COMPLETED))
            .build()
        taskStateManager.recordEvent("task-1", completedTask)

        // When
        val emitter = streamingHandler.resubscribeToTask("task-1", "stream-2")

        // Then - emitter is created (stream will be closed asynchronously after replaying)
        assertNotNull(emitter)
    }
}
