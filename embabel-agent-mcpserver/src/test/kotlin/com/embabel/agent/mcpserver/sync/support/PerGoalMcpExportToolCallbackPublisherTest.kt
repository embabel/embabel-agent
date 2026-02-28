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
package com.embabel.agent.mcpserver.sync.support

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.api.dsl.aggregate
import com.embabel.agent.core.Export
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.domain.Frog
import com.embabel.agent.test.domain.MagicVictim
import com.embabel.agent.test.dsl.SnakeMeal
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.agent.test.integration.RandomRanker
import com.embabel.agent.test.integration.forAutonomyTesting
import com.embabel.agent.tools.agent.CONFIRMATION_TOOL_NAME
import com.embabel.agent.tools.agent.FORM_SUBMISSION_TOOL_NAME
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression test for issue #1447: HITL tools (_confirm, submitFormAndResumeProcess)
 * must be included in the MCP tool export alongside goal tools.
 *
 * Prior to the fix, [PerGoalMcpExportToolCallbackPublisher.toolCallbacks] only returned
 * goal-based tools from [PerGoalToolFactory.goalTools], omitting the platform tools
 * (HITL callbacks) entirely.
 */
class PerGoalMcpExportToolCallbackPublisherTest {

    private lateinit var autonomy: Autonomy
    private lateinit var mcpSyncServer: McpSyncServer

    @BeforeEach
    fun setUp() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(remoteExportedAgent())
        autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())
        mcpSyncServer = mockk(relaxed = true)
    }

    @Test
    fun `toolCallbacks includes platform HITL tools alongside goal tools`() {
        val publisher = PerGoalMcpExportToolCallbackPublisher(
            autonomy = autonomy,
            mcpSyncServer = mcpSyncServer,
            applicationName = "testApp",
        )

        val callbacks = publisher.toolCallbacks
        val toolNames = callbacks.map { it.toolDefinition.name() }

        // Should have goal tools
        val goalToolNames = toolNames.filter {
            it != CONFIRMATION_TOOL_NAME && it != FORM_SUBMISSION_TOOL_NAME
        }
        assertTrue(goalToolNames.isNotEmpty(), "Should include at least one goal tool: $toolNames")

        // Issue #1447: platform HITL tools must be present
        assertTrue(
            toolNames.contains(CONFIRMATION_TOOL_NAME),
            "toolCallbacks must include '$CONFIRMATION_TOOL_NAME' for HITL support: $toolNames"
        )
        assertTrue(
            toolNames.contains(FORM_SUBMISSION_TOOL_NAME),
            "toolCallbacks must include '$FORM_SUBMISSION_TOOL_NAME' for HITL support: $toolNames"
        )
    }

    @Test
    fun `toolCallbacks count equals goal tools plus platform tools`() {
        val publisher = PerGoalMcpExportToolCallbackPublisher(
            autonomy = autonomy,
            mcpSyncServer = mcpSyncServer,
            applicationName = "testApp",
        )

        val callbacks = publisher.toolCallbacks
        val goalCount = autonomy.agentPlatform.goals
            .filter { it.export.remote }
            .sumOf { it.export.startingInputTypes.size }
        val platformCount = 2 // _confirm + submitFormAndResumeProcess

        assertEquals(
            goalCount + platformCount,
            callbacks.size,
            "Should have $goalCount goal tools + $platformCount platform tools = ${goalCount + platformCount} total, " +
                "but got ${callbacks.size}: ${callbacks.map { it.toolDefinition.name() }}"
        )
    }

    companion object {
        /**
         * Creates an agent with remote export enabled so its goals appear
         * in the MCP publisher's tool list.
         */
        private fun remoteExportedAgent() =
            agent("ExportedWizard", description = "Turn a person into a frog") {
                transformation<UserInput, MagicVictim>(name = "identify") {
                    MagicVictim(name = "Hamish")
                }
                flow {
                    aggregate<MagicVictim, Frog, SnakeMeal>(
                        transforms = listOf(
                            { Frog(it.input.name) },
                            { Frog("2") },
                            { Frog("3") },
                        ),
                        merge = { frogs, _ -> SnakeMeal(frogs) },
                    )
                }
                goal(
                    name = "done",
                    description = "done",
                    satisfiedBy = SnakeMeal::class,
                    export = Export(
                        remote = true,
                        startingInputTypes = setOf(MagicVictim::class.java),
                    ),
                )
            }
    }
}
