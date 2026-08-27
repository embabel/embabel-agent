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
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Export
import com.embabel.agent.core.ToolGroupDescription
import com.embabel.agent.core.ToolGroupPermission
import com.embabel.agent.spi.support.AgentScanningBeanPostProcessorEvent
import com.embabel.agent.test.domain.Frog
import com.embabel.agent.test.domain.MagicVictim
import com.embabel.agent.test.dsl.SnakeMeal
import com.embabel.agent.tools.mcp.McpToolGroup
import com.embabel.common.test.ai.config.FakeAiConfiguration
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val CONSUMED_GOAL_NAME = "mcpConsumedWizardGoal"

private fun consumedWizard() = agent("McpConsumedWizard", description = "Turn a person into a snake meal") {

    flow {
        aggregate<MagicVictim, Frog, SnakeMeal>(
            transforms = listOf({ Frog(it.input.name) }),
            merge = { frogs, _ -> SnakeMeal(frogs) },
        )
    }

    goal(
        name = CONSUMED_GOAL_NAME,
        description = "Turn the victim into a snake meal",
        satisfiedBy = SnakeMeal::class,
        export = Export(remote = true, startingInputTypes = setOf(MagicVictim::class.java)),
    )
}

/**
 * The client direction of MCP: [McpToolGroup] consuming a real MCP server over the wire,
 * so that an Embabel agent can call tools that live outside the platform.
 *
 * [McpServerProtocolIntegrationTest] covers the opposite direction — Embabel *serving*
 * MCP. Here the in-process server is only a stand-in for a third-party MCP server; the
 * subject under test is [McpToolGroup] and the `ToolCallback` to [Tool] conversion
 * beneath it, which today is exercised only against mocks.
 *
 * `McpToolGroupTest` already pins the swallow behaviour with a throwing mock client, so an
 * empty tool list there is the asserted outcome. This test is the only place where an empty
 * list means a genuine defect: everything is wired correctly against a live server, so
 * [McpToolGroup.loadTools] returning nothing can only be a regression.
 *
 * TODO(#issue-mcp-client-e2e): enable once the client direction is agreed to be in scope
 *  for this module.
 */
@Disabled("TODO: client-direction MCP coverage not yet part of the CI contract — see class KDoc")
@SpringBootTest(
    classes = [AgentTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.ai.mcp.server.enabled=true",
        "spring.ai.mcp.server.protocol=SSE",
        "spring.ai.mcp.client.enabled=false",
    ],
)
@ActiveProfiles("test")
@Import(FakeAiConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpClientToolGroupIntegrationTest(
    @param:Autowired
    private val autonomy: Autonomy,
    @param:Autowired
    private val applicationContext: ConfigurableApplicationContext,
    @param:Value("\${local.server.port}")
    private val port: Int,
) {

    private lateinit var client: McpSyncClient

    @BeforeAll
    fun exposeAServerWorthConsuming() {
        autonomy.agentPlatform.deploy(consumedWizard())
        applicationContext.publishEvent(AgentScanningBeanPostProcessorEvent(this))
        client = McpClient
            .sync(HttpClientSseClientTransport.builder("http://localhost:$port").build())
            .requestTimeout(Duration.ofSeconds(30))
            .build()
        client.initialize()
    }

    @AfterAll
    fun closeClient() {
        client.closeGracefully()
    }

    private fun toolGroup(filter: (org.springframework.ai.tool.ToolCallback) -> Boolean = { true }) =
        McpToolGroup(
            description = ToolGroupDescription(description = "Tools from an external MCP server", role = "external"),
            provider = "test",
            name = "external-mcp",
            permissions = setOf(ToolGroupPermission.INTERNET_ACCESS),
            clients = listOf(client),
            filter = filter,
        )

    private fun consumedTool(): Tool =
        toolGroup().tools.single { it.definition.name.contains(CONSUMED_GOAL_NAME) }

    @Test
    fun `tool group exposes the remote server's tools as native Embabel tools`() {
        val tools = toolGroup().tools

        assertTrue(tools.isNotEmpty(), "loadTools swallows failures and returns empty — an empty list means broken")
        assertTrue(
            tools.any { it.definition.name.contains(CONSUMED_GOAL_NAME) },
            "Remote goal tool should be visible through the tool group: ${tools.map { it.definition.name }}"
        )
        assertNotNull(consumedTool().definition.inputSchema, "Converted tool must keep its input schema")
    }

    @Test
    fun `calling a tool through the tool group round trips to the remote server`() {
        val result = consumedTool().call("""{"name": "Hamish"}""")

        val text = assertNotNull(result as? Tool.Result.Text, "Expected a text result, got $result")
        assertTrue(text.content.contains("SnakeMeal"), "Should carry the remote tool's output: ${text.content}")
        assertTrue(text.content.contains("Hamish"), "Should carry the caller's input through: ${text.content}")
    }

    @Test
    fun `the filter predicate is applied to the remote tool list`() {
        val filtered = toolGroup { false }.tools

        assertTrue(filtered.isEmpty(), "A reject-all filter must yield no tools, got ${filtered.map { it.definition.name }}")
    }
}
