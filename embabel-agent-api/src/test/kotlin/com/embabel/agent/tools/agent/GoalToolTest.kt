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
package com.embabel.agent.tools.agent

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Goal
import com.embabel.agent.api.common.PlatformServices
import com.embabel.common.core.types.NamedAndDescribed
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GoalToolTest {

    data class TestInput(
        val name: String,
        val value: Int,
    )

    private val objectMapper = jacksonObjectMapper()
    private lateinit var mockAutonomy: Autonomy
    private lateinit var mockAgentPlatform: AgentPlatform
    private lateinit var mockPlatformServices: PlatformServices
    private lateinit var mockGoal: Goal
    private lateinit var testTextCommunicator: TextCommunicator

    @BeforeEach
    fun setup() {
        mockPlatformServices = mockk<PlatformServices>()
        every { mockPlatformServices.objectMapper } returns objectMapper

        mockAgentPlatform = mockk<AgentPlatform>()
        every { mockAgentPlatform.platformServices } returns mockPlatformServices

        mockAutonomy = mockk<Autonomy>()
        every { mockAutonomy.agentPlatform } returns mockAgentPlatform

        mockGoal = mockk<Goal>()
        every { mockGoal.name } returns "testGoal"
        every { mockGoal.description } returns "Test goal description"

        testTextCommunicator = object : TextCommunicator {
            override fun communicateAwaitable(
                goal: NamedAndDescribed,
                pwe: ProcessWaitingException,
            ): String = "awaiting"

            override fun communicateResult(agentProcessExecution: AgentProcessExecution): String =
                "result"
        }
    }

    @Nested
    inner class DefinitionTests {

        @Test
        fun `definition uses goal name when explicitly provided`() {
            val tool = GoalTool(
                autonomy = mockAutonomy,
                textCommunicator = testTextCommunicator,
                name = "customName",
                description = "Custom description",
                goal = mockGoal,
                inputType = TestInput::class.java,
            )

            assertThat(tool.definition.name).isEqualTo("customName")
            assertThat(tool.definition.description).isEqualTo("Custom description")
        }

        @Test
        fun `definition has input schema from type`() {
            val tool = GoalTool(
                autonomy = mockAutonomy,
                textCommunicator = testTextCommunicator,
                name = "testTool",
                goal = mockGoal,
                inputType = TestInput::class.java,
            )

            val schema = tool.definition.inputSchema
            assertThat(schema.parameters).hasSize(2)
            assertThat(schema.parameters.map { it.name }).containsExactlyInAnyOrder("name", "value")
        }

        @Test
        fun `definition generates valid JSON schema`() {
            val tool = GoalTool(
                autonomy = mockAutonomy,
                textCommunicator = testTextCommunicator,
                name = "testTool",
                goal = mockGoal,
                inputType = TestInput::class.java,
            )

            val json = tool.definition.inputSchema.toJsonSchema()
            assertThat(json).contains("\"type\":\"object\"")
            assertThat(json).contains("\"name\"")
            assertThat(json).contains("\"value\"")
        }
    }

    @Nested
    inner class MetadataTests {

        @Test
        fun `metadata is default`() {
            val tool = GoalTool(
                autonomy = mockAutonomy,
                textCommunicator = testTextCommunicator,
                name = "testTool",
                goal = mockGoal,
                inputType = TestInput::class.java,
            )

            assertThat(tool.metadata).isEqualTo(Tool.Metadata.DEFAULT)
        }
    }

    @Nested
    inner class ListenerTests {

        @Test
        fun `withListener adds listener to list`() {
            val tool = GoalTool(
                autonomy = mockAutonomy,
                textCommunicator = testTextCommunicator,
                name = "testTool",
                goal = mockGoal,
                inputType = TestInput::class.java,
                listeners = emptyList(),
            )

            val mockListener = mockk<AgenticEventListener>()
            val newTool = tool.withListener(mockListener)

            assertThat(newTool.listeners).hasSize(1)
            assertThat(newTool.listeners).contains(mockListener)
        }

        @Test
        fun `withListener preserves existing listeners`() {
            val existingListener = mockk<AgenticEventListener>()
            val tool = GoalTool(
                autonomy = mockAutonomy,
                textCommunicator = testTextCommunicator,
                name = "testTool",
                goal = mockGoal,
                inputType = TestInput::class.java,
                listeners = listOf(existingListener),
            )

            val newListener = mockk<AgenticEventListener>()
            val newTool = tool.withListener(newListener)

            assertThat(newTool.listeners).hasSize(2)
            assertThat(newTool.listeners).contains(existingListener)
            assertThat(newTool.listeners).contains(newListener)
        }
    }

    @Nested
    inner class ToStringTests {

        @Test
        fun `toString includes goal name and description`() {
            val tool = GoalTool(
                autonomy = mockAutonomy,
                textCommunicator = testTextCommunicator,
                name = "testTool",
                goal = mockGoal,
                inputType = TestInput::class.java,
            )

            val str = tool.toString()
            assertThat(str).contains("GoalTool")
            assertThat(str).contains("testGoal")
            assertThat(str).contains("Test goal description")
        }
    }
}
