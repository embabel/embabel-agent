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

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.a2a.spec.CancelTaskRequest
import io.a2a.spec.DataPart
import io.a2a.spec.GetTaskRequest
import io.a2a.spec.InternalError
import io.a2a.spec.Message
import io.a2a.spec.MessageSendParams
import io.a2a.spec.SendMessageRequest
import io.a2a.spec.SendStreamingMessageRequest
import io.a2a.spec.Task
import io.a2a.spec.TaskIdParams
import io.a2a.spec.TaskNotCancelableError
import io.a2a.spec.TaskNotFoundError
import io.a2a.spec.TaskQueryParams
import io.a2a.spec.TaskState
import io.a2a.spec.TaskStatusUpdateEvent
import io.a2a.spec.TextPart
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AutonomyA2ARequestHandlerTest {

    private val autonomy = mockk<Autonomy>(relaxed = true)
    private val taskStateManager = TaskStateManager()
    private val handler = AutonomyA2ARequestHandler(
        autonomy = autonomy,
        agenticEventListener = mockk<AgenticEventListener>(relaxed = true),
        streamingHandler = A2AStreamingHandler(EmbabelObjectMapperHolder.createDefault(), taskStateManager),
        taskStateManager = taskStateManager,
    )

    @Nested
    inner class MessageSend {

        @Test
        fun `uses every text part of a multi-part message as the intent`() {
            val intent = slot<String>()
            every { autonomy.chooseAndRunAgent(capture(intent), any()) } returns execution("done")

            val response = handler.handleJsonRpc(sendMessage("task-1", "first", "second"))

            assertNull(response.error)
            assertEquals("first\nsecond", intent.captured)
        }

        @Test
        fun `accepts a message without text parts`() {
            every { autonomy.chooseAndRunAgent(any(), any()) } returns execution("done")

            val message = Message.Builder()
                .messageId("msg-1")
                .role(Message.Role.USER)
                .parts(listOf(DataPart(mapOf("city" to "Sydney"))))
                .build()

            val response = handler.handleJsonRpc(SendMessageRequest("req-1", MessageSendParams(message, null, null)))

            assertNull(response.error)
        }

        @Test
        fun `returns internal error with the request id when the agent fails`() {
            every { autonomy.chooseAndRunAgent(any(), any()) } throws IllegalStateException("boom")

            val response = handler.handleJsonRpc(sendMessage("task-1", "hello"))

            assertInstanceOf(InternalError::class.java, response.error)
            assertEquals("req-1", response.id)
        }

        @Test
        fun `completed task can be read back with tasks get`() {
            every { autonomy.chooseAndRunAgent(any(), any()) } returns execution("done")
            handler.handleJsonRpc(sendMessage("task-1", "hello"))

            val response = handler.handleJsonRpc(GetTaskRequest("req-2", TaskQueryParams("task-1")))

            val task = response.result as Task
            assertEquals("task-1", task.id)
            assertEquals(TaskState.COMPLETED, task.status.state)
            assertEquals(1, task.artifacts.size)
        }

        @Test
        fun `failed task can be read back with tasks get`() {
            every { autonomy.chooseAndRunAgent(any(), any()) } throws IllegalStateException("boom")
            handler.handleJsonRpc(sendMessage("task-1", "hello"))

            val response = handler.handleJsonRpc(GetTaskRequest("req-2", TaskQueryParams("task-1")))

            assertEquals(TaskState.FAILED, (response.result as Task).status.state)
        }
    }

    @Nested
    inner class TasksGet {

        @Test
        fun `returns task not found for unknown task`() {
            val response = handler.handleJsonRpc(GetTaskRequest("req-1", TaskQueryParams("unknown")))

            assertInstanceOf(TaskNotFoundError::class.java, response.error)
            assertEquals("req-1", response.id)
        }
    }

    @Nested
    inner class TasksCancel {

        @Test
        fun `returns task not cancelable for known task`() {
            every { autonomy.chooseAndRunAgent(any(), any()) } returns execution("done")
            handler.handleJsonRpc(sendMessage("task-1", "hello"))

            val response = handler.handleJsonRpc(CancelTaskRequest("req-2", TaskIdParams("task-1")))

            assertInstanceOf(TaskNotCancelableError::class.java, response.error)
            assertEquals("req-2", response.id)
        }

        @Test
        fun `returns task not found for unknown task`() {
            val response = handler.handleJsonRpc(CancelTaskRequest("req-1", TaskIdParams("unknown")))

            assertInstanceOf(TaskNotFoundError::class.java, response.error)
        }
    }

    @Nested
    inner class MessageStream {

        @Test
        fun `error status update is final`() {
            every { autonomy.chooseAndRunAgent(any(), any()) } throws IllegalStateException("boom")

            handler.handleJsonRpcStream(SendStreamingMessageRequest("req-1", params("task-1", "hello")))

            val last = awaitStatusUpdates("task-1").last()
            assertEquals(TaskState.FAILED, last.status.state)
            assertTrue(last.isFinal)
        }

        @Test
        fun `processing update is sent before the agent runs`() {
            var updatesBeforeAgentRun = emptyList<String>()
            every { autonomy.chooseAndRunAgent(any(), any()) } answers {
                updatesBeforeAgentRun = statusUpdates("task-1").map { (it.status.message.parts.single() as TextPart).text }
                execution("done")
            }

            handler.handleJsonRpcStream(SendStreamingMessageRequest("req-1", params("task-1", "hello")))
            awaitStatusUpdates("task-1")

            assertEquals(listOf("Task started...", "Processing task..."), updatesBeforeAgentRun)
        }

        private fun statusUpdates(taskId: String): List<TaskStatusUpdateEvent> =
            taskStateManager.getTaskEvents(taskId).filterIsInstance<TaskStatusUpdateEvent>()

        private fun awaitStatusUpdates(taskId: String): List<TaskStatusUpdateEvent> {
            val deadline = Instant.now().plus(Duration.ofSeconds(10))
            while (taskStateManager.getTaskInfo(taskId)?.currentState?.isFinal != true) {
                check(Instant.now().isBefore(deadline)) { "Task $taskId did not finish" }
                Thread.sleep(10)
            }
            return statusUpdates(taskId)
        }
    }

    private fun execution(output: Any): AgentProcessExecution =
        mockk<AgentProcessExecution> { every { this@mockk.output } returns output }

    private fun sendMessage(taskId: String, vararg texts: String) = SendMessageRequest("req-1", params(taskId, *texts))

    private fun params(taskId: String, vararg texts: String) = MessageSendParams(
        Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(texts.map { TextPart(it) })
            .taskId(taskId)
            .contextId("ctx-1")
            .build(),
        null,
        null,
    )
}
