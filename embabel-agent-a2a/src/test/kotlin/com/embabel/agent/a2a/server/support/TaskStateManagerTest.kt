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
package com.embabel.agent.a2a.server.support

import io.a2a.spec.Message
import io.a2a.spec.Task
import io.a2a.spec.TaskState
import io.a2a.spec.TaskStatus
import io.a2a.spec.TaskStatusUpdateEvent
import io.a2a.spec.TextPart
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        // Use TaskStatusUpdateEvent with final=true as per actual streaming implementation
        val completedStatus = TaskStatusUpdateEvent.Builder()
            .taskId("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.COMPLETED))
            .isFinal(true)
            .build()

        // When
        taskStateManager.recordEvent("task-1", completedStatus)

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
        // Use TaskStatusUpdateEvent with final=true as per actual streaming implementation
        val failedStatus = TaskStatusUpdateEvent.Builder()
            .taskId("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.FAILED))
            .isFinal(true)
            .build()

        // When
        taskStateManager.recordEvent("task-1", failedStatus)

        // Then
        assertFalse(taskStateManager.isTaskActive("task-1"))
        assertEquals(TaskState.FAILED, taskStateManager.getTaskInfo("task-1")?.currentState)
    }

    @Test
    fun `should move task to completed on canceled state`() {
        // Given
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
        // Use TaskStatusUpdateEvent with final=true as per actual streaming implementation
        val canceledStatus = TaskStatusUpdateEvent.Builder()
            .taskId("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.CANCELED))
            .isFinal(true)
            .build()

        // When
        taskStateManager.recordEvent("task-1", canceledStatus)

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
        // Use TaskStatusUpdateEvent with final=true as per actual streaming implementation
        val completedStatus = TaskStatusUpdateEvent.Builder()
            .taskId("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.COMPLETED))
            .isFinal(true)
            .build()
        taskStateManager.recordEvent("task-1", completedStatus)

        // When
        taskStateManager.cleanupOldTasks(Instant.now().plus(1, ChronoUnit.HOURS))

        // Then
        assertFalse(taskStateManager.taskExists("task-1"))
    }

    @Test
    fun `should not cleanup recent completed tasks`() {
        // Given
        taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
        // Use TaskStatusUpdateEvent with final=true as per actual streaming implementation
        val completedStatus = TaskStatusUpdateEvent.Builder()
            .taskId("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(TaskState.COMPLETED))
            .isFinal(true)
            .build()
        taskStateManager.recordEvent("task-1", completedStatus)

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

    @Test
    fun `should register task without a stream`() {
        taskStateManager.registerTask("task-1", "ctx-1")

        assertTrue(taskStateManager.isTaskActive("task-1"))
        assertNull(taskStateManager.getStreamId("task-1"))
    }

    @Nested
    inner class GetTask {

        @Test
        fun `returns null for unknown task`() {
            assertNull(taskStateManager.getTask("non-existent"))
        }

        @Test
        fun `returns the recorded task`() {
            taskStateManager.registerTask("task-1", "ctx-1")
            val history = Message.Builder()
                .messageId("msg-1")
                .role(Message.Role.USER)
                .parts(listOf(TextPart("Hello")))
                .build()
            taskStateManager.recordEvent(
                "task-1",
                Task.Builder()
                    .id("task-1")
                    .contextId("ctx-1")
                    .status(TaskStatus(TaskState.COMPLETED))
                    .history(listOf(history))
                    .build()
            )

            val task = taskStateManager.getTask("task-1")!!

            assertEquals("task-1", task.id)
            assertEquals("ctx-1", task.contextId)
            assertEquals(TaskState.COMPLETED, task.status.state)
            assertEquals(listOf(history), task.history)
        }

        @Test
        fun `returns the latest status of a streamed task`() {
            taskStateManager.registerTask("task-1", "ctx-1", "stream-1")
            taskStateManager.recordEvent("task-1", statusUpdate(TaskState.WORKING))
            taskStateManager.recordEvent("task-1", statusUpdate(TaskState.COMPLETED))

            val task = taskStateManager.getTask("task-1")!!

            assertEquals("ctx-1", task.contextId)
            assertEquals(TaskState.COMPLETED, task.status.state)
        }

        @Test
        fun `returns working status for task without events`() {
            taskStateManager.registerTask("task-1", "ctx-1")

            assertEquals(TaskState.WORKING, taskStateManager.getTask("task-1")!!.status.state)
        }
    }

    @Nested
    inner class Retention {

        private val retention = Duration.ofMinutes(10)
        private var now = Instant.parse("2026-01-01T00:00:00Z")
        private val clock = mockk<Clock> { every { instant() } answers { now } }
        private val manager = TaskStateManager(retention, clock)

        @Test
        fun `registering a task evicts tasks completed longer ago than the retention`() {
            manager.registerTask("task-1", "ctx-1")
            manager.recordEvent("task-1", statusUpdate(TaskState.COMPLETED))
            now = now.plus(retention).plusSeconds(1)

            manager.registerTask("task-2", "ctx-1")

            assertFalse(manager.taskExists("task-1"))
            assertTrue(manager.taskExists("task-2"))
        }

        @Test
        fun `keeps tasks completed within the retention`() {
            manager.registerTask("task-1", "ctx-1")
            manager.recordEvent("task-1", statusUpdate(TaskState.FAILED))
            now = now.plus(retention).minusSeconds(1)

            manager.registerTask("task-2", "ctx-1")

            assertEquals(TaskState.FAILED, manager.getTask("task-1")?.status?.state)
        }

        @Test
        fun `does not evict active tasks however old`() {
            manager.registerTask("task-1", "ctx-1")
            manager.recordEvent("task-1", statusUpdate(TaskState.WORKING))
            now = now.plus(retention.multipliedBy(100))

            manager.registerTask("task-2", "ctx-1")

            assertTrue(manager.isTaskActive("task-1"))
        }

        @Test
        fun `retention defaults to one hour`() {
            assertEquals(Duration.ofHours(1), TaskStateManager().taskRetention)
        }
    }

    @Nested
    inner class Concurrency {

        private val threads = 8
        private val eventsPerThread = 2_000

        @Test
        fun `concurrent events are all recorded`() {
            taskStateManager.registerTask("task-1", "ctx-1", "stream-1")

            runConcurrently(threads) {
                repeat(eventsPerThread) { taskStateManager.recordEvent("task-1", statusUpdate(TaskState.WORKING)) }
            }

            assertEquals(threads * eventsPerThread, taskStateManager.getTaskEvents("task-1").size)
        }

        @Test
        fun `stream id updates do not lose events or terminal state`() {
            taskStateManager.registerTask("task-1", "ctx-1", "stream-0")

            runConcurrently(threads) { worker ->
                if (worker == 0) {
                    repeat(eventsPerThread) { taskStateManager.recordEvent("task-1", statusUpdate(TaskState.WORKING)) }
                    taskStateManager.recordEvent("task-1", statusUpdate(TaskState.COMPLETED))
                } else {
                    repeat(eventsPerThread) { taskStateManager.updateStreamId("task-1", "stream-$worker-$it") }
                }
            }

            assertEquals(eventsPerThread + 1, taskStateManager.getTaskEvents("task-1").size)
            assertFalse(taskStateManager.isTaskActive("task-1"))
            assertEquals(TaskState.COMPLETED, taskStateManager.getTaskInfo("task-1")?.currentState)
            assertNotNull(taskStateManager.getTaskInfo("task-1")?.completedAt)
        }

        private fun runConcurrently(threads: Int, work: (Int) -> Unit) {
            val pool = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            val futures = (0 until threads).map { worker ->
                pool.submit {
                    start.await()
                    work(worker)
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()
        }
    }

    private fun statusUpdate(state: TaskState): TaskStatusUpdateEvent =
        TaskStatusUpdateEvent.Builder()
            .taskId("task-1")
            .contextId("ctx-1")
            .status(TaskStatus(state))
            .isFinal(state.isFinal)
            .build()
}
