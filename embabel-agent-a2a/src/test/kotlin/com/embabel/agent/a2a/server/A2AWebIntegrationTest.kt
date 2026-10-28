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
import com.embabel.common.util.EmbabelObjectMapperHolder
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentPlatform
import com.embabel.common.core.types.Semver.Companion.DEFAULT_VERSION
import org.a2aproject.sdk.common.A2AHeaders
import org.a2aproject.sdk.compat03.json.JsonUtil_v0_3
import org.a2aproject.sdk.compat03.spec.JSONRPCErrorResponse_v0_3
import org.a2aproject.sdk.compat03.spec.SendMessageRequest_v0_3
import org.a2aproject.sdk.compat03.spec.SendMessageResponse_v0_3
import org.a2aproject.sdk.compat03.spec.SendStreamingMessageRequest_v0_3
import org.a2aproject.sdk.compat03.spec.SendStreamingMessageResponse_v0_3
import org.a2aproject.sdk.compat03.spec.TaskState_v0_3
import org.a2aproject.sdk.compat03.spec.TaskStatusUpdateEvent_v0_3
import org.a2aproject.sdk.compat03.spec.Task_v0_3
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils
import org.a2aproject.sdk.grpc.utils.ProtoUtils
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse
import org.a2aproject.sdk.spec.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
) {

    @BeforeEach
    fun setup() {
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

    private fun errorOf(result: MvcResult): Map<*, *> {
        assertEquals(200, result.response.status, "JSON-RPC errors are returned in the body")
        val response = embabelObjectMapperHolder.get().readValue(result.response.contentAsString, Map::class.java)
        return assertIs<Map<*, *>>(response["error"], "Expected a JSON-RPC error: $response")
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
        fun `should answer with JSON-RPC error when handler throws an Error`() {
            // GetTask is still TODO(), which throws kotlin.NotImplementedError: an Error, not an Exception
            val body = JSONRPCUtils.toJsonRPCRequest(
                "req-1",
                A2AMethods.GET_TASK_METHOD,
                ProtoUtils.ToProto.getTaskRequest(TaskQueryParams("task-123")),
            )

            val error = errorOf(postJsonRpc(body))

            assertEquals(A2AErrorCodes.INTERNAL.code(), error["code"])
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
        fun `should reject A2A 1_0 method name`() {
            val result = postJsonRpc(messageBody(A2AMethods.SEND_MESSAGE_METHOD), a2aVersion = null)

            val response = JsonUtil_v0_3.fromJson(result.response.contentAsString, JSONRPCErrorResponse_v0_3::class.java)
            assertEquals("req-03", response.id)
            assertEquals(A2AErrorCodes.METHOD_NOT_FOUND.code(), response.error.code)
        }
    }

    @Nested
    @Disabled("GetTask and CancelTask are not implemented yet")
    inner class TaskTests {
        @Test
        fun `should get task`() {
            val body = JSONRPCUtils.toJsonRPCRequest(
                "req-1",
                A2AMethods.GET_TASK_METHOD,
                ProtoUtils.ToProto.getTaskRequest(TaskQueryParams("task-123")),
            )

            val response = JSONRPCUtils.parseResponseBody(
                postJsonRpc(body).response.contentAsString,
                A2AMethods.GET_TASK_METHOD,
            ) as GetTaskResponse

            assertEquals("req-1", response.id)
            assertEquals("task-123", response.result.id())
            assertEquals(TaskState.TASK_STATE_COMPLETED, response.result.status().state())
        }

        @Test
        fun `should cancel task`() {
            val body = JSONRPCUtils.toJsonRPCRequest(
                "req-1",
                A2AMethods.CANCEL_TASK_METHOD,
                ProtoUtils.ToProto.cancelTaskRequest(CancelTaskParams("task-123")),
            )

            val response = JSONRPCUtils.parseResponseBody(
                postJsonRpc(body).response.contentAsString,
                A2AMethods.CANCEL_TASK_METHOD,
            ) as CancelTaskResponse

            assertEquals("req-1", response.id)
            assertEquals("task-123", response.result.id())
            assertEquals(TaskState.TASK_STATE_CANCELED, response.result.status().state())
        }
    }
}
