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

import io.a2a.spec.Message
import io.a2a.spec.Task
import io.a2a.spec.TaskState
import io.a2a.spec.TaskStatus
import io.a2a.spec.TaskStatusUpdateEvent
import io.a2a.spec.TextPart
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class TaskStateManagerTest {

    private lateinit var taskStateManager: TaskStateManager

    @BeforeEach
    fun setup() {
        taskStateManager = TaskStateManager()
    }

    @Test
    fun `should register task`() {
        // When
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")

        // Then
        assertTrue(taskStateManager.taskExists("task-1"))
        assertTrue(taskStateManager.isTaskActive("task-1"))
        assertEquals("stream-1", taskStateManager.getStreamId("task-1"))
    }

    @Test
    fun `should record events for task`() {
        // Given
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
        val message = Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(listOf(TextPart("Hello")))
            .taskId("task-1")
            .contextId("ctx-1")
            .build()

        // When
        taskStateManager.recordEvent("task-1", message)

        // Then
        val events = taskStateManager.getTaskEvents("task-1")
        assertEquals(1, events.size)
        assertTrue(events[0] is Message)
        assertEquals("msg-1", (events[0] as Message).messageId)
    }

    @Test
    fun `should move task to completed when terminal state received`() {
        // Given
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
        val completedTask = Task.Builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.COMPLETED))
            .build()

        // When
        taskStateManager.recordEvent("task-1", completedTask)

        // Then
        assertFalse(taskStateManager.isTaskActive("task-1"))
        assertTrue(taskStateManager.taskExists("task-1"))
        val taskInfo = taskStateManager.getTaskInfo("task-1")
        assertNotNull(taskInfo?.completedAt)
        assertEquals(TaskState.COMPLETED, taskInfo?.currentState)
    }

    @Test
    fun `should move task to completed on failed state`() {
        // Given
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
        val failedTask = Task.Builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.FAILED))
            .build()

        // When
        taskStateManager.recordEvent("task-1", failedTask)

        // Then
        assertFalse(taskStateManager.isTaskActive("task-1"))
        assertEquals(TaskState.FAILED, taskStateManager.getTaskInfo("task-1")?.currentState)
    }

    @Test
    fun `should move task to completed on canceled state`() {
        // Given
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
        val canceledTask = Task.Builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.CANCELED))
            .build()

        // When
        taskStateManager.recordEvent("task-1", canceledTask)

        // Then
        assertFalse(taskStateManager.isTaskActive("task-1"))
        assertEquals(TaskState.CANCELED, taskStateManager.getTaskInfo("task-1")?.currentState)
    }

    @Test
    fun `should update stream ID for task`() {
        // Given
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")

        // When
        taskStateManager.updateStreamId("task-1", "stream-2")

        // Then
        assertEquals("stream-2", taskStateManager.getStreamId("task-1"))
    }

    @Test
    fun `should return null for non-existent task`() {
        // When/Then
        assertNull(taskStateManager.getTaskInfo("non-existent"))
        assertNull(taskStateManager.getStreamId("non-existent"))
        assertFalse(taskStateManager.taskExists("non-existent"))
        assertTrue(taskStateManager.getTaskEvents("non-existent").isEmpty())
    }

    @Test
    fun `should handle multiple events for same task`() {
        // Given
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
        val message1 = Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(listOf(TextPart("Hello")))
            .build()
        val statusUpdate = TaskStatusUpdateEvent.Builder()
            .taskId("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.WORKING))
            .build()
        val message2 = Message.Builder()
            .messageId("msg-2")
            .role(Message.Role.AGENT)
            .parts(listOf(TextPart("Response")))
            .build()

        // When
        taskStateManager.recordEvent("task-1", message1)
        taskStateManager.recordEvent("task-1", statusUpdate)
        taskStateManager.recordEvent("task-1", message2)

        // Then
        val events = taskStateManager.getTaskEvents("task-1")
        assertEquals(3, events.size)
    }

    @Test
    fun `should cleanup old completed tasks`() {
        // Given
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
        val completedTask = Task.Builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.COMPLETED))
            .build()
        taskStateManager.recordEvent("task-1", completedTask)

        // Simulate old completion time
        val taskInfo = taskStateManager.getTaskInfo("task-1")
        taskInfo?.completedAt = Instant.now().minus(2, ChronoUnit.HOURS)

        // When
        taskStateManager.cleanupOldTasks(Instant.now().minus(1, ChronoUnit.HOURS))

        // Then
        assertFalse(taskStateManager.taskExists("task-1"))
    }

    @Test
    fun `should not cleanup recent completed tasks`() {
        // Given
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
        val completedTask = Task.Builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.COMPLETED))
            .build()
        taskStateManager.recordEvent("task-1", completedTask)

        // When
        taskStateManager.cleanupOldTasks(Instant.now().minus(1, ChronoUnit.HOURS))

        // Then
        assertTrue(taskStateManager.taskExists("task-1"))
    }

    @Test
    fun `should keep task in working state until terminal state received`() {
        // Given
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
        val statusUpdate = TaskStatusUpdateEvent.Builder()
            .taskId("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.WORKING))
            .build()

        // When
        taskStateManager.recordEvent("task-1", statusUpdate)

        // Then
        assertTrue(taskStateManager.isTaskActive("task-1"))
        assertNull(taskStateManager.getTaskInfo("task-1")?.completedAt)
    }
}
