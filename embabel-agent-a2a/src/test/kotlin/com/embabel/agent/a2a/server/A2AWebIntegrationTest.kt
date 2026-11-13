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
import com.embabel.agent.a2a.server.support.TaskStateManager
import com.embabel.common.util.EmbabelObjectMapperHolder
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentPlatform
import com.embabel.common.core.types.Semver.Companion.DEFAULT_VERSION
import org.a2aproject.sdk.common.A2AHeaders
import org.a2aproject.sdk.compat03.json.JsonUtil_v0_3
import org.a2aproject.sdk.compat03.spec.CancelTaskRequest_v0_3
import org.a2aproject.sdk.compat03.spec.DeleteTaskPushNotificationConfigRequest_v0_3
import org.a2aproject.sdk.compat03.spec.GetAuthenticatedExtendedCardRequest_v0_3
import org.a2aproject.sdk.compat03.spec.GetTaskPushNotificationConfigRequest_v0_3
import org.a2aproject.sdk.compat03.spec.GetTaskRequest_v0_3
import org.a2aproject.sdk.compat03.spec.GetTaskResponse_v0_3
import org.a2aproject.sdk.compat03.spec.JSONRPCErrorResponse_v0_3
import org.a2aproject.sdk.compat03.spec.ListTaskPushNotificationConfigRequest_v0_3
import org.a2aproject.sdk.compat03.spec.SendMessageRequest_v0_3
import org.a2aproject.sdk.compat03.spec.SendMessageResponse_v0_3
import org.a2aproject.sdk.compat03.spec.SendStreamingMessageRequest_v0_3
import org.a2aproject.sdk.compat03.spec.SendStreamingMessageResponse_v0_3
import org.a2aproject.sdk.compat03.spec.SetTaskPushNotificationConfigRequest_v0_3
import org.a2aproject.sdk.compat03.spec.TaskResubscriptionRequest_v0_3
import org.a2aproject.sdk.compat03.spec.TaskState_v0_3
import org.a2aproject.sdk.compat03.spec.TaskStatusUpdateEvent_v0_3
import org.a2aproject.sdk.compat03.spec.Task_v0_3
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils
import org.a2aproject.sdk.grpc.utils.ProtoUtils
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse
import org.a2aproject.sdk.spec.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Talks to the A2A endpoints the way an A2A 1.0 client of the A2A Java SDK does:
 * requests are written and responses read with the SDK's own JSON-RPC utilities.
 */
@SpringBootTest
@ActiveProfiles(value = ["test"])
@AutoConfigureMockMvc(addFilters = false)
@EnableAutoConfiguration
@ComponentScan(basePackages = ["com.embabel.agent.a2a"])
@Import(
    value = [
        FakeAiConfiguration::class,
        FakeRankerConfiguration::class,
    ]
)
class A2AWebIntegrationTest(
    @Autowired
    private val mockMvc: MockMvc,
    @Autowired
    private val agentPlatform: AgentPlatform,
    @Autowired
    private val embabelObjectMapperHolder: EmbabelObjectMapperHolder,
    @Autowired
    private val horoscopeService: TestHoroscopeService,
    @Autowired
    private val taskStateManager: TaskStateManager,
) {

    @BeforeEach
    fun setup() {
        deployStarNewsFinder(horoscopeService)
    }

    private fun deployStarNewsFinder(horoscopeService: TestHoroscopeService) {
        AgentMetadataReader().createAgentScopes(
            TestStarNewsFinder(
                horoscopeService = horoscopeService,
                wordCount = 100,
                storyCount = 5,
            ),
        ).forEach { agentPlatform.deploy(it) }
    }

    private fun userMessage(): Message = Message.builder()
        .role(Message.Role.ROLE_USER)
        .parts(listOf(TextPart("Hello, agent!")))
        .messageId("msg-123")
        .taskId("task-123")
        .contextId("ctx-123")
        .build()

    private fun sendMessageBody(method: String = A2AMethods.SEND_MESSAGE_METHOD): String =
        JSONRPCUtils.toJsonRPCRequest(
            "msg-123",
            method,
            ProtoUtils.ToProto.sendMessageRequest(MessageSendParams.builder().message(userMessage()).build()),
        )

    private fun postJsonRpc(
        body: String,
        a2aVersion: String? = AgentInterface.CURRENT_PROTOCOL_VERSION,
    ): MvcResult = mockMvc.post("/a2a") {
        contentType = MediaType.APPLICATION_JSON
        content = body
        a2aVersion?.let { header(A2AHeaders.A2A_VERSION, it) }
    }.andReturn()

    private fun getTaskResponse(taskId: String): GetTaskResponse {
        val body = JSONRPCUtils.toJsonRPCRequest(
            "get-$taskId",
            A2AMethods.GET_TASK_METHOD,
            ProtoUtils.ToProto.getTaskRequest(TaskQueryParams(taskId)),
        )
        return JSONRPCUtils.parseResponseBody(
            postJsonRpc(body).response.contentAsString,
            A2AMethods.GET_TASK_METHOD,
        ) as GetTaskResponse
    }

    private fun getTask(taskId: String): Task = assertNotNull(getTaskResponse(taskId).result)

    private fun subscribeToTaskBody(taskId: String): String =
        JSONRPCUtils.toJsonRPCRequest(
            "req-456",
            A2AMethods.SUBSCRIBE_TO_TASK_METHOD,
            ProtoUtils.ToProto.subscribeToTaskRequest(TaskIdParams(taskId)),
        )

    private fun errorOf(result: MvcResult): Map<*, *> = assertIs<Map<*, *>>(
        errorResponseOf(result)["error"],
        "Expected a JSON-RPC error: ${result.response.contentAsString}",
    )

    private fun errorResponseOf(result: MvcResult): Map<*, *> {
        assertEquals(200, result.response.status, "JSON-RPC errors are returned in the body")
        val response = embabelObjectMapperHolder.get().readValue(result.response.contentAsString, Map::class.java)
        assertIs<Map<*, *>>(response["error"], "Expected a JSON-RPC error: $response").let(::assertNoServerInternals)
        return response
    }

    private fun assertNoServerInternals(error: Map<*, *>) {
        assertTrue(
            setOf("code", "message", "data").containsAll(error.keys),
            "The error must not expose server internals such as the stack trace: ${error.keys}",
        )
    }

    /**
     * Posts a raw JSON-RPC body and checks it is answered with an error for the request id, or a null id
     * @return the error code
     */
    private fun errorCodeFor(body: String, expectedId: Any?, a2aVersion: String? = AgentInterface.CURRENT_PROTOCOL_VERSION): Int {
        val response = errorResponseOf(postJsonRpc(body, a2aVersion))
        assertTrue(response.containsKey("id"), "A JSON-RPC response always has an id: $response")
        assertEquals(expectedId, response["id"])
        return (assertIs<Map<*, *>>(response["error"])["code"] as Number).toInt()
    }

    @Nested
    inner class AgentCardTests {
        @Test
        fun `should return A2A 1_0 agent card`() {
            val result = mockMvc.get("/a2a/.well-known/agent.json")
                .andExpect {
                    status().isOk()
                    content { contentType(MediaType.APPLICATION_JSON) }
                }.andReturn()

            val agentCard = JsonUtil.fromJson(result.response.contentAsString, AgentCard::class.java)

            assertNotNull(agentCard.name())
            assertNotNull(agentCard.description())
            assertEquals(
                listOf(AgentInterface.CURRENT_PROTOCOL_VERSION, "0.3"),
                agentCard.supportedInterfaces().map { it.protocolVersion() },
                "The preferred interface is A2A 1.0, then A2A 0.3",
            )
            val agentInterface = agentCard.supportedInterfaces().first()
            assertTrue(agentCard.supportedInterfaces().all { it.protocolBinding() == TransportProtocol.JSONRPC.asString() })
            assertTrue(agentCard.supportedInterfaces().all { it.url() == agentInterface.url() })
            assertEquals(agentInterface.url(), agentCard.url(), "A2A 0.3 clients read the url field")
            assertTrue(
                agentInterface.url().contains("localhost"),
                "Agent interface url should expose localhost: '${agentInterface.url()}'"
            )
            assertTrue(agentInterface.url().endsWith(":80/a2a"), "Agent interface url should expose port and path: '${agentInterface.url()}'")
            assertEquals("Embabel", agentCard.provider()?.organization())
            assertEquals("https://embabel.com", agentCard.provider()?.url())
            assertEquals(DEFAULT_VERSION, agentCard.version())
            assertEquals("https://embabel.com/docs", agentCard.documentationUrl())
            assertEquals(true, agentCard.capabilities().streaming())
            assertEquals(false, agentCard.capabilities().pushNotifications())
            assertEquals(false, agentCard.capabilities().extendedAgentCard())
            assertEquals(listOf("application/json", "text/plain"), agentCard.defaultInputModes())
            assertEquals(listOf("application/json", "text/plain"), agentCard.defaultOutputModes())
        }

        @Test
        fun `should return agent card at A2A 0_3 well-known path`() {
            val result = mockMvc.get("/a2a/.well-known/agent-card.json")
                .andExpect {
                    status().isOk()
                    content { contentType(MediaType.APPLICATION_JSON) }
                }.andReturn()

            val agentCard = JsonUtil.fromJson(result.response.contentAsString, AgentCard::class.java)

            assertNotNull(agentCard.name())
        }

        @Test
        fun `should return the same agent card at the A2A 0_2 and 0_3 well-known paths`() {
            val legacy = mockMvc.get("/a2a/.well-known/agent.json")
                .andExpect { status().isOk() }.andReturn().response.contentAsString
            val current = mockMvc.get("/a2a/.well-known/agent-card.json")
                .andExpect { status().isOk() }.andReturn().response.contentAsString

            assertEquals(legacy, current)
        }
    }

    @Nested
    inner class MessageTests {
        @Test
        fun `should handle message send`() {
            val result = postJsonRpc(sendMessageBody())
            assertEquals(200, result.response.status)
            assertTrue(
                MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(result.response.contentType!!))
            )

            val response = JSONRPCUtils.parseResponseBody(
                result.response.contentAsString,
                A2AMethods.SEND_MESSAGE_METHOD,
            ) as SendMessageResponse

            assertEquals("msg-123", response.id)
            val task = assertIs<Task>(response.result)
            assertEquals("task-123", task.id())
            assertEquals("ctx-123", task.contextId())
            assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state())
            assertEquals("Hello, agent!", (task.history()?.first()?.parts()?.first() as? TextPart)?.text())
            // The test agent returns a HasContent Writeup: its content is sent as a TextPart
            val artifactParts = task.artifacts()!!.single().parts()
            val content = artifactParts.filterIsInstance<TextPart>().single().text()
            assertTrue(content.isNotBlank(), "Agent output must reach the client")
            assertEquals(1, artifactParts.filterIsInstance<DataPart>().size)
        }

        @Test
        fun `should handle message stream`() {
            val result = mockMvc.post("/a2a") {
                contentType = MediaType.APPLICATION_JSON
                content = sendMessageBody(A2AMethods.SEND_STREAMING_MESSAGE_METHOD)
                header(A2AHeaders.A2A_VERSION, AgentInterface.CURRENT_PROTOCOL_VERSION)
            }.andExpect {
                request { asyncStarted() }
            }.andReturn()
            mockMvc.perform(asyncDispatch(result))

            val events = result.response.contentAsString.lines()
                .filter { it.startsWith("data:") }
                .map { ProtoUtils.FromProto.streamingEventKind(JSONRPCUtils.parseResponseEvent(it.removePrefix("data:"))) }

            assertTrue(events.isNotEmpty(), "Stream should carry events")
            val last = assertIs<TaskStatusUpdateEvent>(events.last())
            assertEquals("task-123", last.taskId())
            assertEquals(TaskState.TASK_STATE_COMPLETED, last.status().state())
        }

        @Test
        fun `should handle subscribe to task request`() {
            val body = JSONRPCUtils.toJsonRPCRequest(
                "req-456",
                A2AMethods.SUBSCRIBE_TO_TASK_METHOD,
                ProtoUtils.ToProto.subscribeToTaskRequest(TaskIdParams("task-123")),
            )

            // The task does not exist: we only check the request is routed to the streaming handler
            val result = postJsonRpc(body)
            assertEquals(200, result.response.status)
        }

        @Test
        fun `should keep the type of a string id that looks like a number`() {
            val body = JSONRPCUtils.toJsonRPCRequest(
                "42",
                A2AMethods.SEND_MESSAGE_METHOD,
                ProtoUtils.ToProto.sendMessageRequest(MessageSendParams.builder().message(userMessage()).build()),
            )

            val response = embabelObjectMapperHolder.get()
                .readValue(postJsonRpc(body).response.contentAsString, Map::class.java)

            assertNotNull(response["result"], "Expected a result: $response")
            assertEquals("42", response["id"])
        }

        @Test
        fun `should carry the generated task and context ids in the status message`() {
            val message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(listOf(TextPart("Hello, agent!")))
                .messageId("msg-123")
                .build()
            val body = JSONRPCUtils.toJsonRPCRequest(
                "msg-123",
                A2AMethods.SEND_MESSAGE_METHOD,
                ProtoUtils.ToProto.sendMessageRequest(MessageSendParams.builder().message(message).build()),
            )

            val response = JSONRPCUtils.parseResponseBody(
                postJsonRpc(body).response.contentAsString,
                A2AMethods.SEND_MESSAGE_METHOD,
            ) as SendMessageResponse

            val task = assertIs<Task>(response.result)
            assertEquals(task.id(), task.status().message()?.taskId())
            assertEquals(task.contextId(), task.status().message()?.contextId())
        }

        @Test
        fun `should reject an unsupported A2A-Version`() {
            val error = errorOf(postJsonRpc(sendMessageBody(), a2aVersion = "2.0"))

            assertEquals(A2AErrorCodes.VERSION_NOT_SUPPORTED.code(), error["code"])
        }

        @Test
        fun `should replay task events when resubscribing to known task`() {
            val taskId = "resub-${UUID.randomUUID()}"
            taskStateManager.registerTask(taskId, "ctx-resub", "stream-original")
            taskStateManager.recordEvent(taskId, statusUpdate(taskId, TaskState.TASK_STATE_WORKING))

            val result = subscribeToTask(taskId)

            val event = assertIs<TaskStatusUpdateEvent>(streamingEventOf(awaitFirstSseData(result)))
            assertEquals(taskId, event.taskId())
            assertEquals(TaskState.TASK_STATE_WORKING, event.status().state())
        }

        @Test
        fun `should replay completed task and close stream without changing its state`() {
            val taskId = "resub-done-${UUID.randomUUID()}"
            taskStateManager.registerTask(taskId, "ctx-resub")
            listOf(TaskState.TASK_STATE_WORKING, TaskState.TASK_STATE_COMPLETED).forEach { state ->
                taskStateManager.recordEvent(taskId, statusUpdate(taskId, state))
            }
            val eventCountBefore = taskStateManager.getTaskEvents(taskId).size
            val completedAtBefore = taskStateManager.getTaskInfo(taskId)!!.completedAt

            val result = subscribeToTask(taskId)
            // Returns once the stream is closed, fails if it stays open
            result.getAsyncResult(Duration.ofSeconds(5).toMillis())

            val states = sseData(result)
                .map { assertIs<TaskStatusUpdateEvent>(streamingEventOf(it)).status().state() }
            assertEquals(listOf(TaskState.TASK_STATE_WORKING, TaskState.TASK_STATE_COMPLETED), states)
            val after = taskStateManager.getTaskInfo(taskId)!!
            assertEquals(eventCountBefore, after.events.size)
            assertEquals(completedAtBefore, after.completedAt)
            assertEquals(TaskState.TASK_STATE_COMPLETED, getTask(taskId).status().state())
        }

        @Test
        fun `should return task not found error when resubscribing to unknown task`() {
            val result = subscribeToTask("unknown-${UUID.randomUUID()}")

            val response = embabelObjectMapperHolder.get().readValue(awaitFirstSseData(result), Map::class.java)
            assertEquals("req-456", response["id"])
            val error = assertIs<Map<*, *>>(response["error"])
            assertEquals(A2AErrorCodes.TASK_NOT_FOUND.code(), error["code"])
            assertNoServerInternals(error)
        }

        @Test
        fun `should answer with JSON-RPC error and fail the task when the agent throws an Error`() {
            deployStarNewsFinder { throw NotImplementedError("not yet") }

            val error = errorOf(postJsonRpc(sendMessageBody()))

            assertEquals(A2AErrorCodes.INTERNAL.code(), error["code"])
            assertEquals(TaskState.TASK_STATE_FAILED, getTask("task-123").status().state())
        }

        private fun statusUpdate(taskId: String, state: TaskState): TaskStatusUpdateEvent =
            TaskStatusUpdateEvent.builder()
                .taskId(taskId)
                .contextId("ctx-resub")
                .status(TaskStatus(state))
                .build()

        private fun subscribeToTask(taskId: String): MvcResult = mockMvc.post("/a2a") {
            contentType = MediaType.APPLICATION_JSON
            content = subscribeToTaskBody(taskId)
            header(A2AHeaders.A2A_VERSION, AgentInterface.CURRENT_PROTOCOL_VERSION)
        }.andExpect {
            request { asyncStarted() }
        }.andReturn()

        private fun streamingEventOf(data: String): StreamingEventKind =
            ProtoUtils.FromProto.streamingEventKind(JSONRPCUtils.parseResponseEvent(data))

        private fun sseData(result: MvcResult): List<String> =
            result.response.contentAsString.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:") }
                .toList()

        private fun awaitFirstSseData(result: MvcResult): String {
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (System.nanoTime() < deadline) {
                sseData(result).firstOrNull()?.let { return it }
                Thread.sleep(20)
            }
            fail("No SSE event received: '${result.response.contentAsString}'")
        }

        @Test
        fun `should reject A2A 0_3 method name`() {
            val error = errorOf(postJsonRpc(sendMessageBody(method = "message/send")))

            assertEquals(A2AErrorCodes.METHOD_NOT_FOUND.code(), error["code"])
        }
    }

    /**
     * A2A 0.3 clients send no A2A-Version header, or A2A-Version 0.3, and use the A2A 0.3 method names and JSON.
     */
    @Nested
    inner class A2A03Tests {

        private fun messageBody(method: String, id: Any = "req-03"): String =
            embabelObjectMapperHolder.get().writeValueAsString(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to id,
                    "method" to method,
                    "params" to mapOf(
                        "message" to mapOf(
                            "kind" to "message",
                            "messageId" to "msg-03",
                            "role" to "user",
                            "taskId" to "task-03",
                            "contextId" to "ctx-03",
                            "parts" to listOf(mapOf("kind" to "text", "text" to "Hello, agent!")),
                        )
                    ),
                )
            )

        private fun assertCompletedTask(result: MvcResult) {
            assertEquals(200, result.response.status)
            val response = JsonUtil_v0_3.fromJson(result.response.contentAsString, SendMessageResponse_v0_3::class.java)
            assertEquals("req-03", response.id)
            assertNull(response.error, "Unexpected error: ${result.response.contentAsString}")
            val task = assertIs<Task_v0_3>(response.result)
            assertEquals("task-03", task.id())
            assertEquals("ctx-03", task.contextId())
            assertEquals(TaskState_v0_3.COMPLETED, task.status().state())
            assertTrue(task.artifacts().single().parts().isNotEmpty())
        }

        @Test
        fun `should handle message send without A2A-Version header`() {
            assertCompletedTask(postJsonRpc(messageBody(SendMessageRequest_v0_3.METHOD), a2aVersion = null))
        }

        @Test
        fun `should handle message send with A2A-Version 0_3`() {
            assertCompletedTask(postJsonRpc(messageBody(SendMessageRequest_v0_3.METHOD), a2aVersion = "0.3"))
        }

        @Test
        fun `should handle message stream`() {
            val result = mockMvc.post("/a2a") {
                contentType = MediaType.APPLICATION_JSON
                content = messageBody(SendStreamingMessageRequest_v0_3.METHOD)
            }.andExpect {
                request { asyncStarted() }
            }.andReturn()
            mockMvc.perform(asyncDispatch(result))

            val events = result.response.contentAsString.lines()
                .filter { it.startsWith("data:") }
                .map { JsonUtil_v0_3.fromJson(it.removePrefix("data:"), SendStreamingMessageResponse_v0_3::class.java) }

            assertTrue(events.isNotEmpty(), "Stream should carry events")
            assertTrue(events.all { it.error == null })
            val last = assertIs<TaskStatusUpdateEvent_v0_3>(events.last().result)
            assertEquals("task-03", last.taskId())
            assertEquals(TaskState_v0_3.COMPLETED, last.status().state())
            assertTrue(last.isFinal, "The last A2A 0.3 status update is final")
        }

        @Test
        fun `should return task not found error when resubscribing to unknown task`() {
            val result = mockMvc.post("/a2a") {
                contentType = MediaType.APPLICATION_JSON
                content = rpc("req-03", TaskResubscriptionRequest_v0_3.METHOD, mapOf("id" to "unknown-${UUID.randomUUID()}"))
            }.andExpect {
                request { asyncStarted() }
            }.andReturn()
            result.getAsyncResult(Duration.ofSeconds(5).toMillis())

            val data = result.response.contentAsString.lineSequence().first { it.startsWith("data:") }.removePrefix("data:")
            val response = embabelObjectMapperHolder.get().readValue(data, Map::class.java)
            assertEquals("req-03", response["id"])
            val error = assertIs<Map<*, *>>(response["error"])
            assertEquals(A2AErrorCodes.TASK_NOT_FOUND.code(), error["code"])
            assertNoServerInternals(error)
        }

        @Test
        fun `should get a finished task with only its id`() {
            postJsonRpc(messageBody(SendMessageRequest_v0_3.METHOD), a2aVersion = null)

            val response = JsonUtil_v0_3.fromJson(
                postJsonRpc(rpc("get-03", GetTaskRequest_v0_3.METHOD, mapOf("id" to "task-03")), a2aVersion = null)
                    .response.contentAsString,
                GetTaskResponse_v0_3::class.java,
            )

            assertEquals("get-03", response.id)
            assertEquals(TaskState_v0_3.COMPLETED, assertNotNull(response.result).status().state())
        }

        @Test
        fun `should not cancel a finished task`() {
            postJsonRpc(messageBody(SendMessageRequest_v0_3.METHOD), a2aVersion = null)

            val code = errorCodeFor(rpc("cancel-03", CancelTaskRequest_v0_3.METHOD, mapOf("id" to "task-03")), "cancel-03", null)

            assertEquals(A2AErrorCodes.TASK_NOT_CANCELABLE.code(), code)
        }

        @Test
        fun `should return task not found for an unknown task`() {
            val unknown = mapOf("id" to "unknown-${UUID.randomUUID()}")

            assertEquals(A2AErrorCodes.TASK_NOT_FOUND.code(), errorCodeFor(rpc("g", GetTaskRequest_v0_3.METHOD, unknown), "g", null))
            assertEquals(A2AErrorCodes.TASK_NOT_FOUND.code(), errorCodeFor(rpc("c", CancelTaskRequest_v0_3.METHOD, unknown), "c", null))
        }

        @Test
        fun `should return push notification not supported for every push notification method`() {
            listOf(
                SetTaskPushNotificationConfigRequest_v0_3.METHOD,
                GetTaskPushNotificationConfigRequest_v0_3.METHOD,
                ListTaskPushNotificationConfigRequest_v0_3.METHOD,
                DeleteTaskPushNotificationConfigRequest_v0_3.METHOD,
            ).forEach { method ->
                assertEquals(
                    A2AErrorCodes.PUSH_NOTIFICATION_NOT_SUPPORTED.code(),
                    errorCodeFor(rpc("push-03", method, mapOf("id" to "task-03")), "push-03", null),
                    method,
                )
            }
        }

        @Test
        fun `should return extended card not configured`() {
            assertEquals(
                A2AErrorCodes.EXTENDED_AGENT_CARD_NOT_CONFIGURED.code(),
                errorCodeFor(rpc("card-03", GetAuthenticatedExtendedCardRequest_v0_3.METHOD), "card-03", null),
            )
        }

        @Test
        fun `should answer malformed requests with JSON-RPC errors`() {
            assertEquals(A2AErrorCodes.JSON_PARSE.code(), errorCodeFor("{", null, null))
            assertEquals(A2AErrorCodes.INVALID_REQUEST.code(), errorCodeFor("[]", null, null))
            assertEquals(
                A2AErrorCodes.INVALID_PARAMS.code(),
                errorCodeFor(rpc("p-03", SendMessageRequest_v0_3.METHOD, emptyMap<String, Any>()), "p-03", null),
            )
            assertEquals(A2AErrorCodes.METHOD_NOT_FOUND.code(), errorCodeFor(rpc("m-03", "tasks/list"), "m-03", null))
        }

        @Test
        fun `should answer with a numeric request id as sent`() {
            val response = embabelObjectMapperHolder.get().readValue(
                postJsonRpc(rpc(42, GetTaskRequest_v0_3.METHOD, mapOf("id" to "t")), a2aVersion = null).response.contentAsString,
                Map::class.java,
            )

            assertEquals(42, response["id"])
        }

        private fun rpc(id: Any?, method: String, params: Any? = null): String =
            embabelObjectMapperHolder.get().writeValueAsString(
                buildMap {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    params?.let { put("params", it) }
                }
            )

        @Test
        fun `should reject A2A 1_0 method name`() {
            val result = postJsonRpc(messageBody(A2AMethods.SEND_MESSAGE_METHOD), a2aVersion = null)

            val response = JsonUtil_v0_3.fromJson(result.response.contentAsString, JSONRPCErrorResponse_v0_3::class.java)
            assertEquals("req-03", response.id)
            assertEquals(A2AErrorCodes.METHOD_NOT_FOUND.code(), response.error.code)
        }
    }

    @Nested
    inner class JsonRpcErrorTests {

        private fun rpc(id: Any?, method: String, params: Any? = null): String =
            embabelObjectMapperHolder.get().writeValueAsString(
                buildMap {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    params?.let { put("params", it) }
                }
            )

        @Test
        fun `should return push notification not supported for every push notification method`() {
            listOf(
                A2AMethods.SET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD to mapOf("taskId" to "t", "url" to "https://client/notify"),
                A2AMethods.GET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD to mapOf("taskId" to "t", "id" to "c"),
                A2AMethods.LIST_TASK_PUSH_NOTIFICATION_CONFIG_METHOD to mapOf("taskId" to "t"),
                A2AMethods.DELETE_TASK_PUSH_NOTIFICATION_CONFIG_METHOD to mapOf("taskId" to "t", "id" to "c"),
            ).forEach { (method, params) ->
                assertEquals(
                    A2AErrorCodes.PUSH_NOTIFICATION_NOT_SUPPORTED.code(),
                    errorCodeFor(rpc("push-1", method, params), "push-1"),
                    method,
                )
            }
        }

        @Test
        fun `should return unsupported operation for the extended agent card and task listing`() {
            assertEquals(
                A2AErrorCodes.UNSUPPORTED_OPERATION.code(),
                errorCodeFor(rpc("card-1", A2AMethods.GET_EXTENDED_AGENT_CARD_METHOD, emptyMap<String, Any>()), "card-1"),
            )
            assertEquals(
                A2AErrorCodes.UNSUPPORTED_OPERATION.code(),
                errorCodeFor(rpc("list-1", A2AMethods.LIST_TASK_METHOD, emptyMap<String, Any>()), "list-1"),
            )
        }

        @Test
        fun `should answer malformed requests with JSON-RPC errors`() {
            assertEquals(A2AErrorCodes.JSON_PARSE.code(), errorCodeFor("{", null))
            assertEquals(A2AErrorCodes.INVALID_REQUEST.code(), errorCodeFor("[]", null))
            assertEquals(A2AErrorCodes.INVALID_REQUEST.code(), errorCodeFor("""{"jsonrpc":"2.0","id":"r-1"}""", "r-1"))
            assertEquals(
                A2AErrorCodes.INVALID_REQUEST.code(),
                errorCodeFor("""{"jsonrpc":"1.0","id":"r-2","method":"GetTask","params":{"id":"t"}}""", "r-2"),
            )
            assertEquals(
                A2AErrorCodes.INVALID_PARAMS.code(),
                errorCodeFor(rpc("r-3", A2AMethods.SEND_MESSAGE_METHOD, emptyMap<String, Any>()), "r-3"),
            )
            assertEquals(A2AErrorCodes.METHOD_NOT_FOUND.code(), errorCodeFor(rpc("r-4", "tasks/list"), "r-4"))
        }

        @Test
        fun `should answer with a numeric request id as sent`() {
            assertEquals(A2AErrorCodes.TASK_NOT_FOUND.code(), errorCodeFor(rpc(42, A2AMethods.GET_TASK_METHOD, mapOf("id" to "t")), 42))
        }
    }

    @Nested
    inner class TaskTests {
        @Test
        fun `should get a finished task`() {
            postJsonRpc(sendMessageBody())

            val response = getTaskResponse("task-123")

            assertEquals("get-task-123", response.id)
            assertEquals("task-123", response.result.id())
            assertEquals("ctx-123", response.result.contextId())
            assertEquals(TaskState.TASK_STATE_COMPLETED, response.result.status().state())
            assertEquals(1, response.result.artifacts().size)
        }

        @Test
        fun `should return task not found when getting an unknown task`() {
            val error = errorOf(postJsonRpc(getTaskBody("unknown-${UUID.randomUUID()}")))

            assertEquals(A2AErrorCodes.TASK_NOT_FOUND.code(), error["code"])
        }

        @Test
        fun `should not cancel a finished task`() {
            postJsonRpc(sendMessageBody())

            val error = errorOf(postJsonRpc(cancelTaskBody("task-123")))

            assertEquals(A2AErrorCodes.TASK_NOT_CANCELABLE.code(), error["code"])
            assertEquals(TaskState.TASK_STATE_COMPLETED, getTask("task-123").status().state())
        }

        @Test
        fun `should return task not found when canceling an unknown task`() {
            val error = errorOf(postJsonRpc(cancelTaskBody("unknown-${UUID.randomUUID()}")))

            assertEquals(A2AErrorCodes.TASK_NOT_FOUND.code(), error["code"])
        }

        private fun getTaskBody(taskId: String): String =
            JSONRPCUtils.toJsonRPCRequest(
                "req-1",
                A2AMethods.GET_TASK_METHOD,
                ProtoUtils.ToProto.getTaskRequest(TaskQueryParams(taskId)),
            )

        private fun cancelTaskBody(taskId: String): String =
            JSONRPCUtils.toJsonRPCRequest(
                "req-1",
                A2AMethods.CANCEL_TASK_METHOD,
                ProtoUtils.ToProto.cancelTaskRequest(CancelTaskParams(taskId)),
            )
    }
}
