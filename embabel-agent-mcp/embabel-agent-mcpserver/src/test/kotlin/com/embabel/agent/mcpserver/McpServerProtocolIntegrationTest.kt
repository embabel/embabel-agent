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
package com.embabel.agent.mcpserver

import com.embabel.agent.AgentTestApplication
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.api.dsl.aggregate
import com.embabel.agent.core.Export
import com.embabel.agent.spi.support.AgentScanningBeanPostProcessorEvent
import com.embabel.agent.test.domain.Frog
import com.embabel.agent.test.domain.MagicVictim
import com.embabel.agent.test.dsl.SnakeMeal
import com.embabel.agent.tools.agent.CONFIRMATION_TOOL_NAME
import com.embabel.agent.tools.agent.FORM_SUBMISSION_TOOL_NAME
import com.embabel.common.test.ai.config.FakeAiConfiguration
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val REMOTE_GOAL_NAME = "mcpRemoteWizardGoal"

private const val LOCAL_GOAL_NAME = "mcpLocalWizardGoal"

private fun wizard(
    agentName: String,
    goalName: String,
    export: Export,
) = agent(agentName, description = "Turn a person into a snake meal") {

    flow {
        aggregate<MagicVictim, Frog, SnakeMeal>(
            transforms = listOf({ Frog(it.input.name) }),
            merge = { frogs, _ -> SnakeMeal(frogs) },
        )
    }

    goal(
        name = goalName,
        description = "Turn the victim into a snake meal",
        satisfiedBy = SnakeMeal::class,
        export = export,
    )
}

private fun mcpRemotelyExportedWizard() = wizard(
    agentName = "McpRemotelyExportedWizard",
    goalName = REMOTE_GOAL_NAME,
    export = Export(remote = true, startingInputTypes = setOf(MagicVictim::class.java)),
)

private fun mcpLocallyExportedWizard() = wizard(
    agentName = "McpLocallyExportedWizard",
    goalName = LOCAL_GOAL_NAME,
    export = Export(remote = false, startingInputTypes = setOf(MagicVictim::class.java)),
)

/**
 * Drives a real MCP server over its wire protocol: an in-process Spring Boot server on a
 * loopback port, reached by an MCP client that performs the initialize handshake,
 * `tools/list` and `tools/call`.
 *
 * The server runs in-process on a port assigned by the OS (RANDOM_PORT) and is reached
 * over loopback, so nothing leaves the machine and parallel CI jobs cannot clash on a
 * fixed port.
 *
 * This is the only test that exercises the full export path — deployed goal to
 * [PerGoalMcpExportToolCallbackPublisher] to `McpSyncServer` registration to protocol
 * round trip. Tests that call tools directly bypass everything below the agent.
 */
@SpringBootTest(
    classes = [AgentTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.ai.mcp.server.enabled=true",
        "spring.ai.mcp.server.protocol=SSE",
        "spring.ai.mcp.server.name=embabel-mcp-protocol-it",
        "spring.ai.mcp.server.version=9.9.9",
        "spring.ai.mcp.client.enabled=false",
    ],
)
@ActiveProfiles("test")
@Import(FakeAiConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpServerProtocolIntegrationTest(
    @param:Autowired
    private val autonomy: Autonomy,
    @param:Autowired
    private val applicationContext: ConfigurableApplicationContext,
    @param:Value("\${local.server.port}")
    private val port: Int,
) {

    private lateinit var client: McpSyncClient

    private lateinit var handshake: McpSchema.InitializeResult

    /**
     * One deployment, one exposure and one MCP session for the whole class. Nothing here
     * mutates server state per test, and a session per test method left SSE connections
     * being torn down mid-response, which shows up as broken pipe noise in the build log.
     */
    @BeforeAll
    fun deployAgentsAndOpenOneMcpSession() {
        autonomy.agentPlatform.deploy(mcpRemotelyExportedWizard())
        autonomy.agentPlatform.deploy(mcpLocallyExportedWizard())
        // AgentScanningBeanPostProcessorEvent is published by a bean excluded under the
        // "test" profile, so the production initialization path is triggered explicitly.
        // Tool exposure is synchronous: SyncServerStrategy.addTool is a Mono.fromRunnable and
        // nothing in AbstractMcpServerConfiguration schedules it elsewhere, so publishEvent
        // returns only once every tool is registered. No polling needed.
        applicationContext.publishEvent(AgentScanningBeanPostProcessorEvent(this))
        client = McpClient
            .sync(HttpClientSseClientTransport.builder("http://localhost:$port").build())
            .requestTimeout(Duration.ofSeconds(30))
            .build()
        handshake = client.initialize()
    }

    @AfterAll
    fun closeMcpSession() {
        client.closeGracefully()
    }

    private fun McpSchema.ListToolsResult.named(goalName: String): McpSchema.Tool =
        tools().single { it.name().contains(goalName) }

    @Nested
    inner class Handshake {

        @Test
        fun `initialize reports the configured server identity and tool capability`() {
            assertEquals("embabel-mcp-protocol-it", handshake.serverInfo().name())
            assertEquals("9.9.9", handshake.serverInfo().version())
            assertNotNull(handshake.capabilities().tools(), "Server must advertise the tools capability")
        }
    }

    @Nested
    inner class ToolsList {

        @Test
        fun `remotely exported goal is published as a tool with a usable input schema`() {
            val tool = client.listTools().named(REMOTE_GOAL_NAME)

            assertEquals("object", tool.inputSchema()["type"], "Input schema must be a JSON Schema object")
            val properties = tool.inputSchema()["properties"] as? Map<*, *>
            assertNotNull(properties, "Input schema must declare properties: ${tool.inputSchema()}")
            assertTrue(
                properties.containsKey("name"),
                "Input schema must expose the MagicVictim name property: $properties"
            )
            assertTrue(tool.description().isNotBlank(), "Tool must carry a description for the model")
        }

        @Test
        fun `locally exported goal is withheld from the MCP server`() {
            val toolNames = client.listTools().tools().map { it.name() }

            assertTrue(
                toolNames.any { it.contains(REMOTE_GOAL_NAME) },
                "Guard against a vacuous pass: the remote goal must be listed: $toolNames"
            )
            assertTrue(
                toolNames.none { it.contains(LOCAL_GOAL_NAME) },
                "Only remotely exported goals may reach MCP clients: $toolNames"
            )
        }

        @Test
        fun `HITL platform tools are published alongside goal tools`() {
            val toolNames = client.listTools().tools().map { it.name() }

            assertTrue(
                toolNames.any { it.contains(CONFIRMATION_TOOL_NAME) },
                "Confirmation tool must be reachable over MCP for HITL: $toolNames"
            )
            assertTrue(
                toolNames.any { it.contains(FORM_SUBMISSION_TOOL_NAME) },
                "Form submission tool must be reachable over MCP for HITL: $toolNames"
            )
        }
    }

    @Nested
    inner class ToolsCall {

        @Test
        fun `calling the goal tool runs the agent and returns its output over the protocol`() {
            val result = client.callTool(
                McpSchema.CallToolRequest(
                    client.listTools().named(REMOTE_GOAL_NAME).name(),
                    mapOf("name" to "Hamish"),
                )
            )

            assertEquals(false, result.isError(), "Call should succeed: ${result.content()}")
            val text = result.content().filterIsInstance<McpSchema.TextContent>().joinToString("\n") { it.text() }
            assertTrue(text.contains("SnakeMeal"), "Result should be the goal output: $text")
            assertTrue(text.contains("Hamish"), "Result should carry the caller's input through: $text")
        }

        @Test
        fun `calling an unknown tool is rejected as a protocol error`() {
            val thrown = assertThrows<McpError> {
                client.callTool(McpSchema.CallToolRequest("no_such_tool_at_all", emptyMap()))
            }

            assertTrue(
                thrown.message!!.contains("Unknown tool"),
                "Server must reject an unregistered tool by name, got: ${thrown.message}"
            )
        }
    }
}
