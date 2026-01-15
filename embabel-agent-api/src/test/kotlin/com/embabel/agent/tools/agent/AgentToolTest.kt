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
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessOptions
import com.embabel.common.core.types.NamedAndDescribed
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AgentToolTest {

    data class TestInput(
        val message: String,
        val count: Int,
    )

    private val objectMapper = jacksonObjectMapper()
    private lateinit var mockAutonomy: Autonomy
    private lateinit var mockAgent: Agent
    private lateinit var testTextCommunicator: TextCommunicator

    @BeforeEach
    fun setup() {
        mockAutonomy = mockk<Autonomy>()

        mockAgent = mockk<Agent>()
        every { mockAgent.name } returns "testAgent"
        every { mockAgent.description } returns "Test agent description"

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
        fun `definition uses agent name`() {
            val tool = AgentTool(
                autonomy = mockAutonomy,
                agent = mockAgent,
                textCommunicator = testTextCommunicator,
                objectMapper = objectMapper,
                inputType = TestInput::class.java,
                processOptionsCreator = { ProcessOptions() },
            )

            assertThat(tool.definition.name).isEqualTo("testAgent")
        }

        @Test
        fun `definition uses agent description`() {
            val tool = AgentTool(
                autonomy = mockAutonomy,
                agent = mockAgent,
                textCommunicator = testTextCommunicator,
                objectMapper = objectMapper,
                inputType = TestInput::class.java,
                processOptionsCreator = { ProcessOptions() },
            )

            assertThat(tool.definition.description).isEqualTo("Test agent description")
        }

        @Test
        fun `definition has input schema from type`() {
            val tool = AgentTool(
                autonomy = mockAutonomy,
                agent = mockAgent,
                textCommunicator = testTextCommunicator,
                objectMapper = objectMapper,
                inputType = TestInput::class.java,
                processOptionsCreator = { ProcessOptions() },
            )

            val schema = tool.definition.inputSchema
            assertThat(schema.parameters).hasSize(2)
            assertThat(schema.parameters.map { it.name }).containsExactlyInAnyOrder(
                "message",
                "count"
            )
        }

        @Test
        fun `definition generates valid JSON schema`() {
            val tool = AgentTool(
                autonomy = mockAutonomy,
                agent = mockAgent,
                textCommunicator = testTextCommunicator,
                objectMapper = objectMapper,
                inputType = TestInput::class.java,
                processOptionsCreator = { ProcessOptions() },
            )

            val json = tool.definition.inputSchema.toJsonSchema()
            assertThat(json).contains("\"type\":\"object\"")
            assertThat(json).contains("\"message\"")
            assertThat(json).contains("\"count\"")
        }
    }

    @Nested
    inner class MetadataTests {

        @Test
        fun `metadata is default`() {
            val tool = AgentTool(
                autonomy = mockAutonomy,
                agent = mockAgent,
                textCommunicator = testTextCommunicator,
                objectMapper = objectMapper,
                inputType = TestInput::class.java,
                processOptionsCreator = { ProcessOptions() },
            )

            assertThat(tool.metadata).isEqualTo(Tool.Metadata.DEFAULT)
        }
    }

    @Nested
    inner class ToStringTests {

        @Test
        fun `toString includes agent name and description`() {
            val tool = AgentTool(
                autonomy = mockAutonomy,
                agent = mockAgent,
                textCommunicator = testTextCommunicator,
                objectMapper = objectMapper,
                inputType = TestInput::class.java,
                processOptionsCreator = { ProcessOptions() },
            )

            val str = tool.toString()
            assertThat(str).contains("AgentTool")
            assertThat(str).contains("testAgent")
            assertThat(str).contains("Test agent description")
        }
    }

    @Nested
    inner class DataClassTests {

        @Test
        fun `exposes agent via property`() {
            val tool = AgentTool(
                autonomy = mockAutonomy,
                agent = mockAgent,
                textCommunicator = testTextCommunicator,
                objectMapper = objectMapper,
                inputType = TestInput::class.java,
                processOptionsCreator = { ProcessOptions() },
            )

            assertThat(tool.agent).isSameAs(mockAgent)
        }

        @Test
        fun `exposes textCommunicator via property`() {
            val tool = AgentTool(
                autonomy = mockAutonomy,
                agent = mockAgent,
                textCommunicator = testTextCommunicator,
                objectMapper = objectMapper,
                inputType = TestInput::class.java,
                processOptionsCreator = { ProcessOptions() },
            )

            assertThat(tool.textCommunicator).isSameAs(testTextCommunicator)
        }

        @Test
        fun `exposes objectMapper via property`() {
            val tool = AgentTool(
                autonomy = mockAutonomy,
                agent = mockAgent,
                textCommunicator = testTextCommunicator,
                objectMapper = objectMapper,
                inputType = TestInput::class.java,
                processOptionsCreator = { ProcessOptions() },
            )

            assertThat(tool.objectMapper).isSameAs(objectMapper)
        }

        @Test
        fun `exposes inputType via property`() {
            val tool = AgentTool(
                autonomy = mockAutonomy,
                agent = mockAgent,
                textCommunicator = testTextCommunicator,
                objectMapper = objectMapper,
                inputType = TestInput::class.java,
                processOptionsCreator = { ProcessOptions() },
            )

            assertThat(tool.inputType).isEqualTo(TestInput::class.java)
        }
    }
}
