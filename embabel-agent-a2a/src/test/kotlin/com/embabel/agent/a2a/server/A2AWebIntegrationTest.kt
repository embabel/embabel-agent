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
package com.embabel.agent.a2a.server

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.e2e.FakeConfig
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.testing.integration.DummyObjectCreatingLlmOperations
import com.embabel.common.core.types.Semver.Companion.DEFAULT_VERSION
import com.embabel.example.simple.horoscope.TestHoroscopeService
import com.embabel.example.simple.horoscope.java.TestStarNewsFinder
import com.fasterxml.jackson.databind.ObjectMapper
import io.a2a.spec.AgentCard
import io.a2a.spec.CancelTaskResponse
import io.a2a.spec.GetTaskPushNotificationConfigResponse
import io.a2a.spec.GetTaskResponse
import io.a2a.spec.JSONRPCRequest
import io.a2a.spec.Message
import io.a2a.spec.MessageSendParams
import io.a2a.spec.PushNotificationAuthenticationInfo
import io.a2a.spec.PushNotificationConfig
import io.a2a.spec.SendMessageRequest
import io.a2a.spec.SendMessageResponse
import io.a2a.spec.SetTaskPushNotificationConfigResponse
import io.a2a.spec.Task
import io.a2a.spec.TaskIdParams
import io.a2a.spec.TaskPushNotificationConfig
import io.a2a.spec.TaskQueryParams
import io.a2a.spec.TaskState
import io.a2a.spec.TextPart
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestConfiguration
class DummyLlmOps {

    @Bean
    @Primary
    fun llmOperations(): LlmOperations = DummyObjectCreatingLlmOperations.LoremIpsum

}

@SpringBootTest
@ActiveProfiles(value = ["test", "a2a"])
@AutoConfigureMockMvc(addFilters = false)
@EnableAutoConfiguration
@Import(
    value = [
        FakeConfig::class,
        DummyLlmOps::class,
    ]
)
class A2AWebIntegrationTest(
    @Autowired
    private val mockMvc: MockMvc,
    @Autowired
    private val agentPlatform: AgentPlatform,
    @Autowired
    private val objectMapper: ObjectMapper,
    @Autowired
    private val horoscopeService: TestHoroscopeService,
) {

    @BeforeEach
    fun setup() {
        AgentMetadataReader().createAgentScopes(
            com.embabel.example.simple.horoscope.kotlin.TestStarNewsFinder(
                horoscopeService = horoscopeService,
                wordCount = 100,
                storyCount = 5,
            ),
            TestStarNewsFinder(horoscopeService, 5),
        ).forEach { agentPlatform.deploy(it) }
    }

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
            val agentCard = objectMapper.readValue(content, AgentCard::class.java)

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
            assertEquals(false, agentCard.capabilities.streaming)
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
            val params =  MessageSendParams.Builder().message(message).build()
            val request = SendMessageRequest.Builder()
                .jsonrpc(JSONRPCRequest.JSONRPC_VERSION)
                .method(SendMessageRequest.METHOD)
                .id("msg-123")
                .params(params)
                .build()

            val result = mockMvc.post("/a2a") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }
                .andExpect {
                    status().isOk()
                    content { contentType(MediaType.APPLICATION_JSON) }
                }.andReturn()

            val content = result.response.contentAsString
            val response = objectMapper.readValue(content, SendMessageResponse::class.java)

            assertNotNull(response)
            assertEquals("msg-123", response.id)

            val task = objectMapper.convertValue(response.result, Task::class.java)
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

            // Note: We can't fully test SSE with MockMvc in a standard way
            // This test just verifies the endpoint doesn't throw an error
            mockMvc.post("/a2a/message/stream") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(params)
            }
                .andExpect {
                    status().isOk()
                }
        }
    }

    @Nested
    @Disabled
    inner class TaskTests {
        @Test
        fun `should get task`() {
            val params = TaskQueryParams("task-123")

            val result = mockMvc.post("/a2a/tasks/get") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(params)
            }
                .andExpect {
                    status().isOk()
                    content { contentType(MediaType.APPLICATION_JSON) }
                }.andReturn()

            val content = result.response.contentAsString
            val response = objectMapper.readValue(content, GetTaskResponse::class.java)

            assertNotNull(response)
            assertEquals("task-123", response.id)

            val task = objectMapper.convertValue(response.result, Task::class.java)
            assertEquals("task-123", task.id)
            assertEquals("ctx-1", task.contextId)
            assertEquals(TaskState.COMPLETED, task.status.state)
        }

        @Test
        fun `should cancel task`() {
            val params = TaskIdParams("task-123")

            val result = mockMvc.post("/a2a/tasks/cancel") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(params)
            }
                .andExpect {
                    status().isOk()
                    content { contentType(MediaType.APPLICATION_JSON) }
                }.andReturn()

            val content = result.response.contentAsString
            val response = objectMapper.readValue(content, CancelTaskResponse::class.java)

            assertNotNull(response)
            assertEquals("task-123", response.id)

            val task = objectMapper.convertValue(response.result, Task::class.java)
            assertEquals("task-123", task.id)
            assertEquals("ctx-1", task.contextId)
            assertEquals(TaskState.CANCELED, task.status.state)
        }
    }

    @Nested
    @Disabled
    inner class PushNotificationTests {
        @Test
        fun `should set push notification config`() {
            val config = PushNotificationConfig(
                "https://client/notify",
                "test-token",
                PushNotificationAuthenticationInfo(
                    listOf("Bearer"),
                    "test-secret"
                ),
                null
            )
            val params = TaskPushNotificationConfig(
                "task-123",
                config
            )

            val result = mockMvc.post("/a2a/tasks/pushNotificationConfig/set") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(params)
            }
                .andExpect {
                    status().isOk()
                    content { contentType(MediaType.APPLICATION_JSON) }
                }.andReturn()

            val content = result.response.contentAsString
            val response = objectMapper.readValue(content, SetTaskPushNotificationConfigResponse::class.java)

            assertNotNull(response)
            assertEquals("task-123", response.id)

            val resultConfig = objectMapper.convertValue(response.result, TaskPushNotificationConfig::class.java)
            assertEquals("task-123", resultConfig.taskId)
            assertEquals("https://client/notify", resultConfig.pushNotificationConfig.url)
            assertEquals("test-token", resultConfig.pushNotificationConfig.token)
            assertEquals(listOf("Bearer"), resultConfig.pushNotificationConfig.authentication?.schemes)
            assertEquals("test-secret", resultConfig.pushNotificationConfig.authentication?.credentials)
        }

        @Test
        fun `should get push notification config`() {
            val params = TaskIdParams("task-123")

            val result = mockMvc.post("/a2a/tasks/pushNotificationConfig/get") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(params)
            }
                .andExpect {
                    status().isOk()
                    content { contentType(MediaType.APPLICATION_JSON) }
                }.andReturn()

            val content = result.response.contentAsString
            val response = objectMapper.readValue(content, GetTaskPushNotificationConfigResponse::class.java)

            assertNotNull(response)
            assertEquals("task-123", response.id)

            val config = objectMapper.convertValue(response.result, TaskPushNotificationConfig::class.java)
            assertEquals("task-123", config.taskId)
            assertEquals("https://client/notify", config.pushNotificationConfig.url)
            assertEquals("demo-token", config.pushNotificationConfig.token)
            assertEquals(listOf("Bearer"), config.pushNotificationConfig.authentication?.schemes)
            assertEquals("secret", config.pushNotificationConfig.authentication?.credentials)
        }
    }
}
