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
import io.a2a.spec.*
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

    private fun jsonRpc(id: Any?, method: String, params: Any? = null): String =
        embabelObjectMapperHolder.get().writeValueAsString(
            buildMap {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                params?.let { put("params", it) }
            }
        )

    private fun sendMessage(taskId: String): String =
        jsonRpc(
            "send-$taskId",
            SendMessageRequest.METHOD,
            mapOf(
                "message" to mapOf(
                    "kind" to "message",
                    "messageId" to "msg-$taskId",
                    "role" to "user",
                    "taskId" to taskId,
                    "contextId" to "ctx-$taskId",
                    "parts" to listOf(mapOf("kind" to "text", "text" to "Hello, agent!")),
                )
            ),
        )

    private fun postJsonRpc(body: String): MvcResult = mockMvc.post("/a2a") {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }.andReturn()

    private fun responseOf(result: MvcResult): JsonNode {
        assertEquals(200, result.response.status, "JSON-RPC answers, errors included, are returned with HTTP 200")
        assertTrue(
            MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(result.response.contentType!!)),
            "Unexpected content type: ${result.response.contentType}",
        )
        return embabelObjectMapperHolder.get().readTree(result.response.contentAsString)
    }

    /**
     * Checks the response is a JSON-RPC error answering the request id, and returns its code
     */
    private fun errorCodeOf(result: MvcResult, expectedId: String?): Int {
        val response = responseOf(result)
        val error = response.path("error")
        assertTrue(error.isObject, "Expected a JSON-RPC error: ${result.response.contentAsString}")
        assertTrue(
            setOf("code", "message", "data").containsAll(error.propertyNames().toSet()),
            "The error must not expose server internals such as the stack trace: ${error.propertyNames().toSet()}",
        )
        assertEquals("2.0", response.path("jsonrpc").asString())
        if (expectedId == null) {
            assertTrue(response.path("id").isNull, "Expected a null id: ${result.response.contentAsString}")
        } else {
            assertEquals(expectedId, response.path("id").asString())
        }
        return error.path("code").asInt()
    }

    private fun taskStateOf(taskId: String): String =
        responseOf(postJsonRpc(jsonRpc("get-$taskId", GetTaskRequest.METHOD, mapOf("id" to taskId))))
            .path("result").path("status").path("state").asString()

    @Nested
    inner class AgentCardTests {
        @Test
        fun `should return agent card`() {
            val result = mockMvc.get("/a2a/.well-known/agent.json")
                .andExpect {
                    status().isOk()
                    content { contentType(MediaType.APPLICATION_JSON) }
                }.andReturn()

            val content = result.response.contentAsString
            val agentCard = embabelObjectMapperHolder.get().readValue(content, AgentCard::class.java)

            assertNotNull(agentCard)
            assertNotNull(agentCard.name)
            assertNotNull(agentCard.description)
            assertTrue(
                agentCard.url.contains("localhost"),
                "Agent card url should expose localhost: '${agentCard.url}'"
            )
            assertTrue(agentCard.url.contains(":"), "Agent card url should expose port: '${agentCard.url}'")
            assertEquals("Embabel", agentCard.provider?.organization)
            assertEquals("https://embabel.com", agentCard.provider?.url)
            assertEquals(DEFAULT_VERSION, agentCard.version)
            assertEquals("https://embabel.com/docs", agentCard.documentationUrl)
            assertEquals(true, agentCard.capabilities.streaming)
            assertEquals(false, agentCard.capabilities.pushNotifications)
            assertEquals(false, agentCard.capabilities.stateTransitionHistory)
            assertEquals(listOf("application/json", "text/plain"), agentCard.defaultInputModes)
            assertEquals(listOf("application/json", "text/plain"), agentCard.defaultOutputModes)
//            assertTrue(agentCard.skills.isNotEmpty(), "Must have some skills")
//            assertEquals("echo", agentCard.skills[0].id)
//            assertEquals("Echo", agentCard.skills[0].name)
//            assertEquals("Echoes messages.", agentCard.skills[0].description)
//            assertEquals(listOf("test"), agentCard.skills[0].tags)
//            assertEquals(listOf("Say hello!"), agentCard.skills[0].examples)
            assertEquals(false, agentCard.supportsAuthenticatedExtendedCard)
        }

        @Test
        fun `should return agent card at A2A 0_3 well-known path`() {
            val result = mockMvc.get("/a2a/.well-known/agent-card.json")
                .andExpect {
                    status().isOk()
                    content { contentType(MediaType.APPLICATION_JSON) }
                }.andReturn()

            val agentCard = embabelObjectMapperHolder.get()
                .readValue(result.response.contentAsString, AgentCard::class.java)

            assertNotNull(agentCard.name)
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
            val message = Message.Builder()
                .role(Message.Role.USER)
                .parts(listOf(TextPart("Hello, agent!")))
                .messageId("msg-123")
                .taskId("task-123")
                .contextId("ctx-123")
                .build()
            val params = MessageSendParams.Builder().message(message).build()
            val request = SendMessageRequest.Builder()
                .jsonrpc(JSONRPCRequest.JSONRPC_VERSION)
                .method(SendMessageRequest.METHOD)
                .id("msg-123")
                .params(params)
                .build()

            val result = mockMvc.post("/a2a") {
                contentType = MediaType.APPLICATION_JSON
                content = embabelObjectMapperHolder.get().writeValueAsString(request)
            }
                .andExpect {
                    status().isOk()
                    content { contentType(MediaType.APPLICATION_JSON) }
                }.andReturn()

            val content = result.response.contentAsString
            val response = embabelObjectMapperHolder.get().readValue(content, SendMessageResponse::class.java)

            assertNotNull(response)
            assertEquals("msg-123", response.id)

            val task = embabelObjectMapperHolder.get().convertValue(response.result, Task::class.java)
            assertEquals("task-123", task.id)
            assertEquals("ctx-123", task.contextId)
            assertEquals(TaskState.COMPLETED, task.status.state)
            assertTrue(task.history?.isNotEmpty() ?: false)
            assertEquals("Hello, agent!", (task.history.get(0)?.parts?.get(0) as? TextPart)?.text)
        }

        @Test
        fun `should handle message stream`() {
            val message = Message.Builder()
                .role(Message.Role.USER)
                .parts(listOf(TextPart("Hello, agent!")))
                .messageId("msg-123")
                .taskId("task-123")
                .contextId("ctx-123")
                .build()
            val params = MessageSendParams.Builder().message(message).build()
            val request = SendStreamingMessageRequest.Builder()
                .jsonrpc(JSONRPCRequest.JSONRPC_VERSION)
                .method(SendStreamingMessageRequest.METHOD)
                .id("stream-123")
                .params(params)
                .build()

            // Note: We can't fully test SSE with MockMvc in a standard way
            // This test just verifies the endpoint accepts the streaming request without error
            mockMvc.post("/a2a") {
                contentType = MediaType.APPLICATION_JSON
                content = embabelObjectMapperHolder.get().writeValueAsString(request)
            }
                .andExpect {
                    status().isOk()
                }
        }

        @Test
        fun `should replay task events when resubscribing to known task`() {
            val taskId = "resub-${UUID.randomUUID()}"
            taskStateManager.registerTask(taskId, "ctx-resub", "stream-original")
            taskStateManager.recordEvent(
                taskId,
                TaskStatusUpdateEvent.Builder()
                    .taskId(taskId)
                    .contextId("ctx-resub")
                    .status(TaskStatus(TaskState.WORKING))
                    .build(),
            )

            val result = mockMvc.post("/a2a") {
                contentType = MediaType.APPLICATION_JSON
                content = resubscribeRequest(taskId)
            }.andExpect {
                request { asyncStarted() }
            }.andReturn()

            val data = awaitFirstSseData(result)
            assertEquals(taskId, data.path("result").path("taskId").asString())
            assertEquals("working", data.path("result").path("status").path("state").asString())
        }

        @Test
        fun `should replay completed task and close stream without changing its state`() {
            val taskId = "resub-done-${UUID.randomUUID()}"
            taskStateManager.registerTask(taskId, "ctx-resub")
            listOf(TaskState.WORKING, TaskState.COMPLETED).forEach { state ->
                taskStateManager.recordEvent(
                    taskId,
                    TaskStatusUpdateEvent.Builder()
                        .taskId(taskId)
                        .contextId("ctx-resub")
                        .status(TaskStatus(state))
                        .isFinal(state == TaskState.COMPLETED)
                        .build(),
                )
            }
            val eventCountBefore = taskStateManager.getTaskEvents(taskId).size
            val completedAtBefore = taskStateManager.getTaskInfo(taskId)!!.completedAt

            val result = mockMvc.post("/a2a") {
                contentType = MediaType.APPLICATION_JSON
                content = resubscribeRequest(taskId)
            }.andExpect {
                request { asyncStarted() }
            }.andReturn()
            // Returns once the stream is closed, fails if it stays open
            result.getAsyncResult(Duration.ofSeconds(5).toMillis())

            val states = sseData(result).map { it.path("result").path("status").path("state").asString() }
            assertEquals(listOf("working", "completed"), states)
            val after = taskStateManager.getTaskInfo(taskId)!!
            assertEquals(eventCountBefore, after.events.size)
            assertEquals(completedAtBefore, after.completedAt)
            assertEquals(TaskState.COMPLETED, getTask(taskId).status.state)
        }

        @Test
        fun `should return task not found error when resubscribing to unknown task`() {
            val result = mockMvc.post("/a2a") {
                contentType = MediaType.APPLICATION_JSON
                content = resubscribeRequest("unknown-${UUID.randomUUID()}")
            }.andExpect {
                request { asyncStarted() }
            }.andReturn()

            val data = awaitFirstSseData(result)
            assertEquals(JSONRPCRequest.JSONRPC_VERSION, data.path("jsonrpc").asString())
            assertEquals("req-456", data.path("id").asString())
            assertEquals(TaskNotFoundError.DEFAULT_CODE, data.path("error").path("code").asInt())
            assertEquals(
                setOf("code", "message"),
                data.path("error").propertyNames().toSet(),
                "The error must not expose server internals such as the stack trace",
            )
        }

        private fun resubscribeRequest(taskId: String): String =
            embabelObjectMapperHolder.get().writeValueAsString(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to "req-456",
                    "method" to TaskResubscriptionRequest.METHOD,
                    "params" to mapOf("id" to taskId),
                )
            )

        private fun sseData(result: MvcResult): List<JsonNode> =
            result.response.contentAsString.lineSequence()
                .filter { it.startsWith("data:") }
                .map { embabelObjectMapperHolder.get().readTree(it.removePrefix("data:")) }
                .toList()

        private fun getTask(taskId: String): Task {
            val request = GetTaskRequest.Builder()
                .jsonrpc(JSONRPCRequest.JSONRPC_VERSION)
                .method(GetTaskRequest.METHOD)
                .id("get-$taskId")
                .params(TaskQueryParams(taskId))
                .build()
            val content = mockMvc.post("/a2a") {
                contentType = MediaType.APPLICATION_JSON
                content = embabelObjectMapperHolder.get().writeValueAsString(request)
            }.andExpect {
                status { isOk() }
            }.andReturn().response.contentAsString
            val response = embabelObjectMapperHolder.get().readValue(content, GetTaskResponse::class.java)
            return assertNotNull(response.result)
        }

        private fun awaitFirstSseData(result: MvcResult): JsonNode {
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (System.nanoTime() < deadline) {
                val dataLine = result.response.contentAsString.lineSequence()
                    .firstOrNull { it.startsWith("data:") }
                if (dataLine != null) {
                    return embabelObjectMapperHolder.get().readTree(dataLine.removePrefix("data:"))
                }
                Thread.sleep(20)
            }
            fail("No SSE event received: '${result.response.contentAsString}'")
        }
    }

    @Nested
    inner class TaskTests {
        @Test
        fun `should get a finished task with only its id`() {
            postJsonRpc(sendMessage("task-get"))

            val response = responseOf(postJsonRpc(jsonRpc("get-1", GetTaskRequest.METHOD, mapOf("id" to "task-get"))))

            assertEquals("get-1", response.path("id").asString())
            assertEquals("task-get", response.path("result").path("id").asString())
            assertEquals("completed", response.path("result").path("status").path("state").asString())
        }

        @Test
        fun `should return task not found when getting an unknown task`() {
            val result = postJsonRpc(jsonRpc("get-2", GetTaskRequest.METHOD, mapOf("id" to "unknown-${UUID.randomUUID()}")))

            assertEquals(TaskNotFoundError.DEFAULT_CODE, errorCodeOf(result, "get-2"))
        }

        @Test
        fun `should not cancel a finished task`() {
            postJsonRpc(sendMessage("task-cancel"))

            val result = postJsonRpc(jsonRpc("cancel-1", CancelTaskRequest.METHOD, mapOf("id" to "task-cancel")))

            assertEquals(TaskNotCancelableError.DEFAULT_CODE, errorCodeOf(result, "cancel-1"))
            assertEquals("completed", taskStateOf("task-cancel"))
        }

        @Test
        fun `should return task not found when canceling an unknown task`() {
            val result = postJsonRpc(jsonRpc("cancel-2", CancelTaskRequest.METHOD, mapOf("id" to "unknown-${UUID.randomUUID()}")))

            assertEquals(TaskNotFoundError.DEFAULT_CODE, errorCodeOf(result, "cancel-2"))
        }
    }

    /**
     * The agent card declares no push notifications and no authenticated extended card.
     */
    @Nested
    inner class UnsupportedMethodTests {
        @Test
        fun `should return push notification not supported for every push notification method`() {
            listOf(
                SetTaskPushNotificationConfigRequest.METHOD,
                GetTaskPushNotificationConfigRequest.METHOD,
                ListTaskPushNotificationConfigRequest.METHOD,
                DeleteTaskPushNotificationConfigRequest.METHOD,
            ).forEach { method ->
                val result = postJsonRpc(jsonRpc("push-1", method, mapOf("id" to "task-123")))

                assertEquals(PushNotificationNotSupportedError.DEFAULT_CODE, errorCodeOf(result, "push-1"), method)
            }
        }

        @Test
        fun `should return extended card not configured`() {
            val result = postJsonRpc(jsonRpc("card-1", GetAuthenticatedExtendedCardRequest.METHOD))

            assertEquals(AuthenticatedExtendedCardNotConfiguredError.DEFAULT_CODE, errorCodeOf(result, "card-1"))
        }

        @Test
        fun `should return method not found for an unknown method`() {
            val result = postJsonRpc(jsonRpc("unknown-1", "tasks/list", emptyMap<String, Any>()))

            assertEquals(MethodNotFoundError().code, errorCodeOf(result, "unknown-1"))
        }
    }

    @Nested
    inner class JsonRpcErrorTests {
        @Test
        fun `should return parse error for invalid JSON`() {
            assertEquals(JSONParseError().code, errorCodeOf(postJsonRpc("{"), null))
        }

        @Test
        fun `should return invalid request for a body that is not an object`() {
            assertEquals(InvalidRequestError().code, errorCodeOf(postJsonRpc("[]"), null))
        }

        @Test
        fun `should return invalid request without a method`() {
            val result = postJsonRpc("""{"jsonrpc":"2.0","id":"r-1"}""")

            assertEquals(InvalidRequestError().code, errorCodeOf(result, "r-1"))
        }

        @Test
        fun `should return invalid request for another JSON-RPC version`() {
            val result = postJsonRpc("""{"jsonrpc":"1.0","id":"r-2","method":"tasks/get","params":{"id":"t"}}""")

            assertEquals(InvalidRequestError().code, errorCodeOf(result, "r-2"))
        }

        @Test
        fun `should return invalid params without params`() {
            val result = postJsonRpc(jsonRpc("r-3", GetTaskRequest.METHOD))

            assertEquals(InvalidParamsError().code, errorCodeOf(result, "r-3"))
        }

        @Test
        fun `should return invalid params for a message send without a message`() {
            val result = postJsonRpc(jsonRpc("r-4", SendMessageRequest.METHOD, mapOf("configuration" to emptyMap<String, Any>())))

            assertEquals(InvalidParamsError().code, errorCodeOf(result, "r-4"))
        }

        @Test
        fun `should answer with the request id as sent`() {
            val numeric = responseOf(postJsonRpc("""{"jsonrpc":"2.0","id":42,"method":"tasks/get","params":{"id":"t"}}"""))
            val text = responseOf(postJsonRpc("""{"jsonrpc":"2.0","id":"42","method":"tasks/get","params":{"id":"t"}}"""))

            assertTrue(numeric.path("id").isNumber, "Numeric id: ${numeric.path("id")}")
            assertEquals(42, numeric.path("id").asInt())
            assertTrue(text.path("id").isString, "String id: ${text.path("id")}")
        }

        @Test
        fun `should answer with a numeric id beyond 32 bits or with a fraction as sent`() {
            postJsonRpc(sendMessage("task-wide-id"))

            listOf("1700000000000", "1.5").forEach { id ->
                val task = responseOf(postJsonRpc("""{"jsonrpc":"2.0","id":$id,"method":"tasks/get","params":{"id":"task-wide-id"}}"""))
                val unknownTask = responseOf(postJsonRpc("""{"jsonrpc":"2.0","id":$id,"method":"tasks/get","params":{"id":"t"}}"""))
                val unknownMethod = responseOf(postJsonRpc("""{"jsonrpc":"2.0","id":$id,"method":"tasks/list"}"""))

                assertEquals("completed", task.path("result").path("status").path("state").asString())
                assertEquals(TaskNotFoundError.DEFAULT_CODE, unknownTask.path("error").path("code").asInt())
                assertEquals(MethodNotFoundError().code, unknownMethod.path("error").path("code").asInt())
                listOf(task, unknownTask, unknownMethod).forEach { response ->
                    assertEquals(id, response.path("id").toString(), "Expected the id as sent: $response")
                }
            }
        }

        @Test
        fun `should serve a request with a null id like a request without an id`() {
            listOf(""""id":null,""", "").forEach { id ->
                val result = postJsonRpc("""{"jsonrpc":"2.0",$id"method":"tasks/get","params":{"id":"t"}}""")

                assertEquals(TaskNotFoundError.DEFAULT_CODE, errorCodeOf(result, null))
            }
        }

        @Test
        fun `should return internal error and fail the task when the agent throws an Error`() {
            deployStarNewsFinder { throw NotImplementedError("not yet") }

            val result = postJsonRpc(sendMessage("task-error"))

            assertEquals(InternalError("").code, errorCodeOf(result, "send-task-error"))
            assertEquals("failed", taskStateOf("task-error"))
        }
    }
}
