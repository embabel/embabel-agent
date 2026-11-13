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
package com.embabel.agent.a2a.server

import com.embabel.agent.a2a.example.simple.horoscope.TestHoroscopeService
import com.embabel.agent.a2a.example.simple.horoscope.kotlin.TestStarNewsFinder
import com.embabel.agent.a2a.server.config.FakeAiConfiguration
import com.embabel.agent.a2a.server.config.FakeRankerConfiguration
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentPlatform
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport
import org.a2aproject.sdk.spec.A2AClientException
import org.a2aproject.sdk.spec.CancelTaskParams
import org.a2aproject.sdk.spec.DataPart
import org.a2aproject.sdk.spec.InternalError
import org.a2aproject.sdk.spec.Message
import org.a2aproject.sdk.spec.MessageSendParams
import org.a2aproject.sdk.spec.StreamingEventKind
import org.a2aproject.sdk.spec.Task
import org.a2aproject.sdk.spec.TaskIdParams
import org.a2aproject.sdk.spec.TaskNotCancelableError
import org.a2aproject.sdk.spec.TaskNotFoundError
import org.a2aproject.sdk.spec.TaskQueryParams
import org.a2aproject.sdk.spec.TaskState
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent
import org.a2aproject.sdk.spec.TextPart
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Talks to the A2A endpoint over HTTP with the A2A Java SDK's own JSON-RPC client,
 * so a change of SDK version is checked against a real client, including its error paths.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(value = ["test"])
@EnableAutoConfiguration
@ComponentScan(basePackages = ["com.embabel.agent.a2a"])
@Import(value = [FakeAiConfiguration::class, FakeRankerConfiguration::class])
class A2ASdkClientInteropTest(
    @Autowired private val agentPlatform: AgentPlatform,
    @Autowired private val horoscopeService: TestHoroscopeService,
) {

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: JSONRPCTransport

    @BeforeEach
    fun setup() {
        deployStarNewsFinder(horoscopeService)
        client = JSONRPCTransport("http://localhost:$port/a2a")
    }

    @AfterEach
    fun close() {
        client.close()
    }

    private fun deployStarNewsFinder(horoscopeService: TestHoroscopeService) {
        AgentMetadataReader().createAgentScopes(
            TestStarNewsFinder(horoscopeService = horoscopeService, wordCount = 100, storyCount = 5),
        ).forEach { agentPlatform.deploy(it) }
    }

    private fun params(taskId: String? = UUID.randomUUID().toString()): MessageSendParams =
        MessageSendParams.builder()
            .message(
                Message.builder()
                    .role(Message.Role.ROLE_USER)
                    .parts(listOf(TextPart("Hello, agent!")))
                    .messageId(UUID.randomUUID().toString())
                    .taskId(taskId)
                    .build()
            )
            .build()

    private fun sendAndFinish(): Task = assertIs<Task>(client.sendMessage(params(), null))

    /**
     * Collects the events of a stream until the client reports that it ended, normally or with an error.
     */
    private class Stream {
        val events = CopyOnWriteArrayList<StreamingEventKind>()
        val end = CompletableFuture<Throwable?>()
        fun await(): Throwable? = end.get(10, TimeUnit.SECONDS)
    }

    private fun stream(start: (Stream) -> Unit): Stream = Stream().also(start)

    private inline fun <reified E : Throwable> assertCausedBy(error: Throwable?) {
        assertNotNull(error, "Expected ${E::class.simpleName}")
        if (generateSequence(error) { it.cause }.none { it is E }) {
            fail("Expected ${E::class.simpleName} in the cause chain of $error")
        }
    }

    @Nested
    inner class Messages {

        @Test
        fun `send message returns the completed task with its artifact`() {
            val task = sendAndFinish()

            assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state())
            val parts = task.artifacts().single().parts()
            assertTrue(parts.filterIsInstance<TextPart>().single().text().isNotBlank())
            assertEquals(1, parts.filterIsInstance<DataPart>().size)
        }

        @Test
        fun `send message without task id gets a generated task id`() {
            val task = assertIs<Task>(client.sendMessage(params(taskId = null), null))

            assertTrue(task.id().isNotBlank())
            assertEquals(task.id(), client.getTask(TaskQueryParams(task.id()), null).id())
        }

        @Test
        fun `send message answers with internal error when the agent throws an Error`() {
            deployStarNewsFinder { throw NotImplementedError("not yet") }

            val error = runCatching { client.sendMessage(params(), null) }.exceptionOrNull()

            assertIs<A2AClientException>(error)
            assertCausedBy<InternalError>(error)
        }

        @Test
        fun `streamed message ends normally with the completed status`() {
            val stream = stream { s -> client.sendMessageStreaming(params(), s.events::add, s.end::complete, null) }

            assertEquals(null, stream.await(), "The stream ends without an error")
            val last = assertIs<TaskStatusUpdateEvent>(stream.events.last())
            assertEquals(TaskState.TASK_STATE_COMPLETED, last.status().state())
        }
    }

    @Nested
    inner class Tasks {

        @Test
        fun `get task returns a finished task`() {
            val sent = sendAndFinish()

            val task = client.getTask(TaskQueryParams(sent.id()), null)

            assertEquals(sent.id(), task.id())
            assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state())
        }

        @Test
        fun `get task reports task not found`() {
            val error = runCatching { client.getTask(TaskQueryParams("unknown-${UUID.randomUUID()}"), null) }
                .exceptionOrNull()

            assertCausedBy<TaskNotFoundError>(error)
        }

        @Test
        fun `cancel task reports a finished task as not cancelable`() {
            val sent = sendAndFinish()

            val error = runCatching { client.cancelTask(CancelTaskParams(sent.id()), null) }.exceptionOrNull()

            assertCausedBy<TaskNotCancelableError>(error)
        }

        @Test
        fun `subscribe to an unknown task reports task not found`() {
            val stream = stream { s ->
                client.subscribeToTask(TaskIdParams("unknown-${UUID.randomUUID()}"), s.events::add, s.end::complete, null)
            }

            assertCausedBy<TaskNotFoundError>(stream.await())
            assertTrue(stream.events.isEmpty())
        }
    }
}
