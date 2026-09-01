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

import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.dsl.evenMoreEvilWizardWithStructuredInput
import com.embabel.agent.api.dsl.exportedEvenMoreEvilWizard
import com.embabel.agent.api.dsl.MagicVictim
import com.embabel.agent.api.dsl.userInputToFrogOrPersonBranch
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.agent.test.integration.RandomRanker
import com.embabel.agent.test.integration.forAutonomyTesting
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Export
import com.embabel.agent.core.ToolNamingStrategy
import com.embabel.agent.domain.io.UserInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test


class PerGoalToolFactoryTest {

    @Test
    fun `platformTools includes HITL tools with expected names`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(exportedEvenMoreEvilWizard())
        val autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())

        val factory = PerGoalToolFactory(autonomy, "testApp")

        val platformToolNames = factory.platformTools.map { it.definition.name }
        assertTrue(
            platformToolNames.contains(CONFIRMATION_TOOL_NAME),
            "Platform tools should include '$CONFIRMATION_TOOL_NAME': $platformToolNames"
        )
        assertTrue(
            platformToolNames.contains(FORM_SUBMISSION_TOOL_NAME),
            "Platform tools should include '$FORM_SUBMISSION_TOOL_NAME': $platformToolNames"
        )
        assertEquals(2, factory.platformTools.size, "Should have exactly 2 platform tools")
    }

    @Test
    fun `allTools includes both goal tools and platform tools`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(exportedEvenMoreEvilWizard())
        val autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())

        val factory = PerGoalToolFactory(autonomy, "testApp")

        val allTools = factory.allTools(remoteOnly = false, listeners = emptyList())
        val allToolNames = allTools.map { it.definition.name }

        // Should contain goal tools
        assertTrue(
            allToolNames.any { !it.startsWith("_") && it != FORM_SUBMISSION_TOOL_NAME },
            "Should contain at least one goal tool"
        )
        // Should also contain platform tools
        assertTrue(
            allToolNames.contains(CONFIRMATION_TOOL_NAME),
            "allTools should include '$CONFIRMATION_TOOL_NAME'"
        )
        assertTrue(
            allToolNames.contains(FORM_SUBMISSION_TOOL_NAME),
            "allTools should include '$FORM_SUBMISSION_TOOL_NAME'"
        )
    }

    @Test
    fun `test local export by default`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(evenMoreEvilWizard())
        agentPlatform.deploy(userInputToFrogOrPersonBranch())
        val autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())

        val provider = PerGoalToolFactory(autonomy, "testApp")

        val tools = provider.allTools(remoteOnly = false, listeners = emptyList())
        assertEquals(
            3, tools.size,
            "Should not have 1 tool with no export defined: have ${tools.map { it.definition.name }}"
        )
    }

    @Test
    fun `test no remote export by default`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(evenMoreEvilWizard())
        agentPlatform.deploy(userInputToFrogOrPersonBranch())
        val autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())

        val provider = PerGoalToolFactory(autonomy, "testApp")

        val tools = provider.allTools(remoteOnly = true, listeners = emptyList())
        assertEquals(
            0,
            tools.size,
            "Should not have any tools with no export defined: ${tools.map { it.definition.name }}"
        )
    }

    @Test
    fun `test explicit remote export`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(exportedEvenMoreEvilWizard())
        agentPlatform.deploy(userInputToFrogOrPersonBranch())
        val autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())

        val provider = PerGoalToolFactory(autonomy, "testApp")

        val tools = provider.allTools(remoteOnly = true, listeners = emptyList())
        assertEquals(
            3,
            tools.size,
            "Should have tools with export defined: ${tools.map { it.definition.name }}"
        )
    }

    @Test
    fun `test user input function per goal`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(exportedEvenMoreEvilWizard())
        agentPlatform.deploy(userInputToFrogOrPersonBranch())
        val autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())

        val provider = PerGoalToolFactory(autonomy, "testApp")

        val tools = provider.allTools(remoteOnly = false, listeners = emptyList())

        assertNotNull(tools)
        assertEquals(
            autonomy.agentPlatform.goals.size + 1,
            tools.size,
            "Should have one tool per goal plus continue"
        )

        for (tool in tools) {
            assertFalse(
                tool.definition.inputSchema.toJsonSchema().contains("timestamp"),
                "Tool should not have timestamp in input schema: ${tool.definition.inputSchema.toJsonSchema()}"
            )
            val toolDefinition = tool.definition
            if (tool.definition.name
                    .contains(FORM_SUBMISSION_TOOL_NAME) || tool.definition.name
                    .contains(CONFIRMATION_TOOL_NAME)
            ) {
                // This is a special case
                break
            }
            val goal = autonomy.agentPlatform.goals.find { tool.definition.name.contains(it.name) }
            assertNotNull(
                goal,
                "Tool should correspond to a platform goal: Offending tool: $tool"
            )
            assertNotNull(tool.definition.inputSchema.toJsonSchema(), "Should have generated schema")
        }
    }

    @Test
    fun `test structured input type function for goal`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(evenMoreEvilWizardWithStructuredInput())
        val autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())

        val provider = PerGoalToolFactory(autonomy, "testApp")
        val tools = provider.allTools(remoteOnly = false, listeners = emptyList())

        assertNotNull(tools)
        assertEquals(
            2 + 1, // 2 functions for the goal + 1 continuation
            tools.size,
            "Should have 2 tools for the one goal plus continuation: Have ${tools.map { it.definition.name }}"
        )

        // Tools should have distinct names
        val toolNames = tools.map { it.definition.name }
        assertEquals(
            toolNames.toSet().size,
            toolNames.size,
            "Tools should have distinct names: $toolNames"
        )

        for (tool in tools) {
            if (tool.definition.name
                    .contains(FORM_SUBMISSION_TOOL_NAME) || tool.definition.name
                    .contains(CONFIRMATION_TOOL_NAME)
            ) {
                // This is a special case
                break
            }

            assertFalse(
                tool.definition.inputSchema.toJsonSchema().contains("timestamp"),
                "Tool should not have timestamp in input schema: ${tool.definition.inputSchema.toJsonSchema()}"
            )
            val toolDefinition = tool.definition
            val goalName = toolDefinition.name
            val goal = autonomy.agentPlatform.goals.find { toolDefinition.name.contains(it.name) }
            assertNotNull(
                goal,
                "Tool should correspond to a platform goal: $goalName, Offending tool: ${tool.definition.name}",
            )
            assertNotNull(tool.definition.inputSchema.toJsonSchema(), "Should have generated schema")
        }
    }

    @Test
    fun `creates a distinct tool for each starting input type`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(
            agentWithExportedGoal(
                agentName = "Wizard",
                description = "done",
                startingInputTypes = setOf(UserInput::class.java, MagicVictim::class.java),
            )
        )
        val autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())

        val goalTools = PerGoalToolFactory(autonomy, "testApp")
            .goalTools(remoteOnly = true, listeners = emptyList())

        assertEquals(2, goalTools.size)
        assertEquals(2, goalTools.map { it.definition.name }.toSet().size)
    }

    @Test
    fun `fully qualified naming keeps same goal names from different agents`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(agentWithExportedGoal("AardvarkWizard", "Aardvark meaning"))
        agentPlatform.deploy(agentWithExportedGoal("ZebraWizard", "Zebra meaning"))
        val autonomy = autonomy(agentPlatform, ToolNamingStrategy.FULLY_QUALIFIED)

        val factory = PerGoalToolFactory(autonomy, "testApp")

        val goalToolNames = factory.goalTools(remoteOnly = true, listeners = emptyList())
            .map { it.definition.name }

        assertEquals(
            setOf("AardvarkWizard_done", "ZebraWizard_done"),
            goalToolNames.toSet(),
        )
        val aardvarkGoal = agentPlatform.agents().single { it.name == "AardvarkWizard" }.goals.single()
        assertEquals(
            "AardvarkWizard_done",
            factory.toolsForGoal(aardvarkGoal, emptyList()).single().definition.name,
        )
    }

    @Test
    fun `public goal tools use the owning agent when goals are equal`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(agentWithExportedGoal("AardvarkWizard", "Same meaning"))
        agentPlatform.deploy(agentWithExportedGoal("ZebraWizard", "Same meaning"))
        val factory = PerGoalToolFactory(
            autonomy(agentPlatform, ToolNamingStrategy.FULLY_QUALIFIED),
            "testApp",
        )
        val zebraGoal = agentPlatform.agents().single { it.name == "ZebraWizard" }.goals.single()

        val tool = factory.toolsForGoal(zebraGoal, emptyList()).single()

        assertEquals("ZebraWizard_done", tool.definition.name)
    }

    @Test
    fun `legacy name only keeps the first same-named goal`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(agentWithExportedGoal("AardvarkWizard", "Aardvark meaning"))
        agentPlatform.deploy(agentWithExportedGoal("ZebraWizard", "Zebra meaning"))
        val autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())

        val factory = PerGoalToolFactory(autonomy, "testApp")

        val goalTools = factory.goalTools(remoteOnly = true, listeners = emptyList())

        assertEquals(listOf("testApp_done"), goalTools.map { it.definition.name })
        assertEquals(listOf("Aardvark meaning"), goalTools.map { it.goal.description })
    }

    private fun autonomy(agentPlatform: AgentPlatform, namingStrategy: ToolNamingStrategy): Autonomy {
        val platformServices = object : PlatformServices by agentPlatform.platformServices {
            override fun toolNamingStrategy() = namingStrategy
        }
        val namedPlatform = object : AgentPlatform by agentPlatform {
            override val platformServices = platformServices
        }
        return Autonomy(namedPlatform, RandomRanker(), forAutonomyTesting())
    }

    private fun agentWithExportedGoal(
        agentName: String,
        description: String,
        startingInputTypes: Set<Class<*>> = setOf(UserInput::class.java),
    ) = agent(agentName, description = description) {
        transformation<UserInput, MagicVictim>(name = "$agentName-action") {
            MagicVictim(agentName)
        }
        goal(
            name = "done",
            description = description,
            satisfiedBy = MagicVictim::class,
            export = Export(
                remote = true,
                startingInputTypes = startingInputTypes,
            ),
        )
    }

}
