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
package com.embabel.agent.e2e

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.dsl.MagicVictim
import com.embabel.agent.api.dsl.SnakeMeal
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.api.dsl.aggregate
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Export
import com.embabel.agent.tools.agent.GoalTool
import com.embabel.agent.tools.agent.PerGoalToolFactory
import com.embabel.agent.tools.agent.PromptedTextCommunicator
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

const val REMOTE_GOAL_NAME = "remotelyExportedWizardGoal"

const val LOCAL_GOAL_NAME = "locallyExportedWizardGoal"

/**
 * Goal names on the platform are not qualified by agent, so these fixtures use
 * names of their own rather than the shared `done` of the dsl test agents.
 */
private fun wizard(
    agentName: String,
    goalName: String,
    export: Export,
) = agent(agentName, description = "Turn a person into a frog") {

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

fun remotelyExportedWizard() = wizard(
    agentName = "RemotelyExportedWizard",
    goalName = REMOTE_GOAL_NAME,
    export = Export(remote = true, startingInputTypes = setOf(MagicVictim::class.java)),
)

fun locallyExportedWizard() = wizard(
    agentName = "LocallyExportedWizard",
    goalName = LOCAL_GOAL_NAME,
    export = Export(remote = false, startingInputTypes = setOf(MagicVictim::class.java)),
)

/**
 * Integration test of the layer beneath MCP: a goal on a live platform becomes a
 * [com.embabel.agent.api.tool.Tool], and calling that tool runs a real agent process.
 *
 * No MCP server, transport or protocol is involved here — tools are invoked directly.
 * `McpServerProtocolIntegrationTest` in embabel-agent-mcpserver covers the protocol.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(
    value = [
        FakeConfig::class,
    ]
)
class ToolFactorySpringIntegrationTest(
    @param:Autowired
    private val autonomy: Autonomy,
) {

    private val agentPlatform: AgentPlatform = autonomy.agentPlatform

    private val toolFactory = PerGoalToolFactory(
        autonomy = autonomy,
        applicationName = "test-app",
        textCommunicator = PromptedTextCommunicator,
    )

    @BeforeEach
    fun setup() {
        agentPlatform.deploy(remotelyExportedWizard())
        agentPlatform.deploy(locallyExportedWizard())
    }

    private fun remoteWizardTool(): GoalTool<*> =
        toolFactory.goalTools(remoteOnly = true, listeners = emptyList())
            .single { it.goal.name == REMOTE_GOAL_NAME }

    /**
     * A goal tool is what the platform publishes for one exported goal: one tool per goal,
     * per starting input type it accepts. Calling it starts an agent process whose objective
     * is that goal, and returns whatever that process produced. This is the layer an MCP
     * server exports, without the protocol on top.
     */
    @Nested
    inner class GoalToolExecution {

        @Test
        fun `calling a goal tool runs an agent process and returns its result`() {
            val result = remoteWizardTool().call("""{"name": "Hamish"}""")

            val text = assertInstanceOf(Tool.Result.Text::class.java, result)
            assertTrue(
                text.content.contains("SnakeMeal"),
                "Result should be the goal output SnakeMeal: ${text.content}"
            )
            assertTrue(
                text.content.contains("Hamish"),
                "Result should carry the tool input through to the output: ${text.content}"
            )
        }

        @Test
        fun `malformed tool input is reported as an error result`() {
            val result = remoteWizardTool().call("not json at all")

            val error = assertInstanceOf(Tool.Result.Error::class.java, result)
            assertTrue(
                error.message.contains("BAD INPUT ERROR"),
                "Error should tell the caller to retry with valid input: ${error.message}"
            )
        }

        @Test
        fun `listeners added to a goal tool receive events from the process it starts`() {
            val processEvents = ConcurrentLinkedQueue<AgentProcessEvent>()
            val listener = object : AgenticEventListener {
                override fun onProcessEvent(event: AgentProcessEvent) {
                    processEvents.add(event)
                }
            }

            val result = remoteWizardTool().withListener(listener).call("""{"name": "Hamish"}""")

            assertInstanceOf(Tool.Result.Text::class.java, result)
            assertTrue(
                processEvents.isNotEmpty(),
                "Listener should have been propagated to the agent process started by the tool"
            )
        }

        @Test
        fun `a listener is only added to the copy leaving the original tool unchanged`() {
            val original = remoteWizardTool()
            val listener = object : AgenticEventListener {}

            val withListener = original.withListener(listener)

            assertEquals(original.definition.name, withListener.definition.name)
            assertEquals(listOf(listener), withListener.listeners)
            assertTrue(original.listeners.isEmpty(), "Original tool should not be mutated")
        }
    }

    @Nested
    inner class RemoteExport {

        @Test
        fun `remote export publishes only goals marked remote`() {
            val remoteToolGoals = toolFactory.goalTools(remoteOnly = true, listeners = emptyList())
                .map { it.goal }

            assertTrue(
                remoteToolGoals.any { it.name == REMOTE_GOAL_NAME },
                "Remotely exported goal should be published: ${remoteToolGoals.map { it.name }}"
            )
            assertTrue(
                remoteToolGoals.none { it.name == LOCAL_GOAL_NAME },
                "Locally exported goal should not be published remotely: ${remoteToolGoals.map { it.name }}"
            )
            assertTrue(
                remoteToolGoals.all { it.export.remote },
                "Every published tool must map to a remote goal: ${remoteToolGoals.map { it.name }}"
            )
        }

        @Test
        fun `local export publishes goals that remote export withholds`() {
            val localToolGoals = toolFactory.goalTools(remoteOnly = false, listeners = emptyList())
                .map { it.goal }

            assertTrue(
                localToolGoals.any { it.name == LOCAL_GOAL_NAME },
                "Locally exported goal should be published locally: ${localToolGoals.map { it.name }}"
            )
        }

        @Test
        fun `all tools combine goal tools and platform tools`() {
            val goalTools = toolFactory.goalTools(remoteOnly = true, listeners = emptyList())
            val allTools = toolFactory.allTools(remoteOnly = true, listeners = emptyList())

            assertEquals(
                (goalTools + toolFactory.platformTools).map { it.definition.name }.toSet(),
                allTools.map { it.definition.name }.toSet(),
            )
        }
    }
}
