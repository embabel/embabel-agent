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
package com.embabel.agent.core

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.plan.common.condition.ConditionDetermination
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val SHARED_ACTION_NAME = "sharedActionName"

private const val SHARED_CONDITION_NAME = "sharedConditionName"

private const val SHARED_GOAL_NAME = "sharedGoalName"

private fun sharedCondition() = object : Condition {
    override val name = SHARED_CONDITION_NAME
    override val cost = 0.0
    override fun evaluate(context: OperationContext) = ConditionDetermination(true)
}

/**
 * The description differs per agent, so two agents using one goal name really are
 * declaring two different goals — the case the platform cannot honour.
 */
private fun agentWithGoalNamed(agentName: String, goalName: String) =
    agent(agentName, description = "Turn a person into a frog") {
        transformation<UserInput, Frog>(name = "$agentName-action") { Frog(agentName) }
        goal(name = goalName, description = "what $agentName means by it", satisfiedBy = Frog::class)
    }

/**
 * Both agents declare the very same goal, so there is nothing to disambiguate.
 */
private fun agentWithIdenticalGoal(agentName: String) =
    agent(agentName, description = "Turn a person into a frog") {
        transformation<UserInput, Frog>(name = "$agentName-action") { Frog(agentName) }
        goal(name = SHARED_GOAL_NAME, description = "one meaning, agreed by both", satisfiedBy = Frog::class)
    }

private fun agentWithActionNamed(agentName: String, actionName: String) =
    agent(agentName, description = "Turn a person into a frog") {
        transformation<UserInput, Frog>(name = actionName) { Frog(agentName) }
        goal(name = "$agentName-goal", description = "done", satisfiedBy = Frog::class)
    }

private fun agentWithCondition(agentName: String, sharedCondition: Condition) =
    agent(agentName, description = "Turn a person into a frog") {
        condition { sharedCondition }
        transformation<UserInput, Frog>(name = "$agentName-action") { Frog(agentName) }
        goal(name = "$agentName-goal", description = "done", satisfiedBy = Frog::class)
    }

/**
 * [AgentPlatform] exposes goals, actions and conditions as flat views over every deployed
 * agent, keyed by name — goals become MCP tools named after them, and the ranker picks
 * between them by name. Two agents contributing different elements under one name cannot
 * both be honoured, so the platform refuses the deployment rather than silently dropping
 * one of them during aggregation.
 */
class DefaultAgentPlatformCollisionTest {

    @Nested
    inner class RejectsCollisions {

        @Test
        fun `a goal name already used by another agent is rejected`() {
            val platform = IntegrationTestUtils.dummyAgentPlatform()
            platform.deploy(agentWithGoalNamed("AardvarkWizard", SHARED_GOAL_NAME))

            val thrown = assertFailsWith<IllegalArgumentException> {
                platform.deploy(agentWithGoalNamed("ZebraWizard", SHARED_GOAL_NAME))
            }

            assertTrue(
                thrown.message!!.contains("goal '$SHARED_GOAL_NAME'"),
                "Message must name the offending goal: ${thrown.message}"
            )
            assertTrue(
                thrown.message!!.contains("ZebraWizard"),
                "Message must name the agent being rejected: ${thrown.message}"
            )
        }

        @Test
        fun `an action name already used by another agent is rejected`() {
            val platform = IntegrationTestUtils.dummyAgentPlatform()
            platform.deploy(agentWithActionNamed("AardvarkWizard", SHARED_ACTION_NAME))

            val thrown = assertFailsWith<IllegalArgumentException> {
                platform.deploy(agentWithActionNamed("ZebraWizard", SHARED_ACTION_NAME))
            }

            assertTrue(
                thrown.message!!.contains("action '$SHARED_ACTION_NAME'"),
                "Message must name the offending action: ${thrown.message}"
            )
        }

        @Test
        fun `a condition name already used by another agent is rejected`() {
            val platform = IntegrationTestUtils.dummyAgentPlatform()
            platform.deploy(agentWithCondition("AardvarkWizard", sharedCondition()))

            val thrown = assertFailsWith<IllegalArgumentException> {
                platform.deploy(agentWithCondition("ZebraWizard", sharedCondition()))
            }

            assertTrue(
                thrown.message!!.contains("condition '$SHARED_CONDITION_NAME'"),
                "Message must name the offending condition: ${thrown.message}"
            )
        }

        @Test
        fun `a rejected agent is not left half deployed`() {
            val platform = IntegrationTestUtils.dummyAgentPlatform()
            platform.deploy(agentWithGoalNamed("AardvarkWizard", SHARED_GOAL_NAME))

            runCatching { platform.deploy(agentWithGoalNamed("ZebraWizard", SHARED_GOAL_NAME)) }

            assertEquals(
                listOf("AardvarkWizard"),
                platform.agents().map { it.name },
                "A rejected deployment must leave the platform untouched"
            )
        }
    }

    @Nested
    inner class AllowsWhatIsUnambiguous {

        @Test
        fun `agents sharing one condition instance still deploy`() {
            val platform = IntegrationTestUtils.dummyAgentPlatform()
            val shared = sharedCondition()
            platform.deploy(agentWithCondition("AardvarkWizard", shared))
            platform.deploy(agentWithCondition("ZebraWizard", shared))

            assertEquals(2, platform.agents().size, "There is nothing to disambiguate")
            assertEquals(
                1,
                platform.conditions.count { it.name == SHARED_CONDITION_NAME },
                "The shared condition is one condition, not two"
            )
        }

        @Test
        fun `agents declaring the very same goal still deploy`() {
            val platform = IntegrationTestUtils.dummyAgentPlatform()
            platform.deploy(agentWithIdenticalGoal("AardvarkWizard"))
            platform.deploy(agentWithIdenticalGoal("ZebraWizard"))

            assertEquals(2, platform.agents().size, "One goal declared twice is not a conflict")
            assertEquals(
                1,
                platform.goals.count { it.name == SHARED_GOAL_NAME },
                "The shared goal is one goal, not two"
            )
        }

        @Test
        fun `redeploying an agent replaces it rather than colliding with itself`() {
            val platform = IntegrationTestUtils.dummyAgentPlatform()
            platform.deploy(agentWithGoalNamed("AardvarkWizard", SHARED_GOAL_NAME))
            platform.deploy(agentWithGoalNamed("AardvarkWizard", SHARED_GOAL_NAME))

            assertEquals(1, platform.agents().size)
        }

        @Test
        fun `distinct names across agents are all published`() {
            val platform = IntegrationTestUtils.dummyAgentPlatform()
            platform.deploy(agentWithGoalNamed("AardvarkWizard", "aardvarkGoal"))
            platform.deploy(agentWithGoalNamed("ZebraWizard", "zebraGoal"))

            assertEquals(
                setOf("aardvarkGoal", "zebraGoal"),
                platform.goals.map { it.name }.toSet(),
                "Nothing should be dropped when names do not collide"
            )
        }
    }
}
