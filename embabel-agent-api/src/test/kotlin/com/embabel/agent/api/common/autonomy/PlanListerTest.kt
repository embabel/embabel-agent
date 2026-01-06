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
package com.embabel.agent.api.common.autonomy

import com.embabel.agent.api.common.workflow.control.SimpleAgentBuilder
import com.embabel.agent.api.dsl.*
import com.embabel.agent.core.Export
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PlanListerTest {

    fun needsVictimAgent() = agent("NeedsVictimAgent", description = "Turn a person into a frog") {

        transformation<MagicVictim, SnakeMeal>(name = "thing") {
            SnakeMeal(frogs = listOf(Frog(name = "Hamish")))
        }

        goal(
            name = "done",
            description = "done",
            satisfiedBy = SnakeMeal::class,
            export = Export(
                startingInputTypes = setOf(MagicVictim::class.java),
            ),
        )
    }

    @Test
    fun `no plans with no agents`() {
        val planLister = DefaultPlanLister(dummyAgentPlatform())
        val plans = planLister.achievablePlans(
            processOptions = ProcessOptions(),
            bindings = emptyMap()
        )
        assert(plans.isEmpty()) { "Expected no plans, but found ${plans.size}" }
    }

    @Test
    fun `plan with one agent that can run from nothing`() {
        val agentPlatform = dummyAgentPlatform()
        agentPlatform.deploy(
            SimpleAgentBuilder
                .returning(Frog::class.java)
                .running { TODO() }
                .buildAgent("name", description = "description")
        )
        val planLister = DefaultPlanLister(agentPlatform)
        val plans = planLister.achievablePlans(
            processOptions = ProcessOptions(),
            bindings = emptyMap()
        )
        assertEquals(1, plans.size, "Expected 1 plans, but found ${plans.size}")
    }

    @Test
    fun `plan with one agent that cannot start from nothing`() {
        val agentPlatform = dummyAgentPlatform()
        agentPlatform.deploy(
            needsVictimAgent()
        )
        val planLister = DefaultPlanLister(agentPlatform)
        val plans = planLister.achievablePlans(
            processOptions = ProcessOptions(),
            bindings = emptyMap()
        )
        assert(plans.isEmpty()) { "Expected no plans, but found ${plans.size}" }
    }

    @Test
    fun `plan with one agent that cannot start from nothing but is satisfied`() {
        val agentPlatform = dummyAgentPlatform()
        agentPlatform.deploy(
            needsVictimAgent()
        )
        val planLister = DefaultPlanLister(agentPlatform)
        val plans = planLister.achievablePlans(
            processOptions = ProcessOptions(),
            bindings = mapOf(
                "it" to MagicVictim(name = "Hamish")
            )
        )
        assertEquals(1, plans.size, "Expected 1 plans, but found ${plans.size}")
    }

    @Test
    fun `plan with one agent that needs UserInput and is satisfied`() {
        val agentPlatform = dummyAgentPlatform()
        agentPlatform.deploy(
            evenMoreEvilWizard()
        )
        val planLister = DefaultPlanLister(agentPlatform)
        val plans = planLister.achievablePlans(
            processOptions = ProcessOptions(),
            bindings = mapOf(
                "it" to UserInput(content = "Hamish is our unfortunate victim")
            )
        )
        assertEquals(1, plans.size, "Expected 1 plans, but found ${plans.size}")
    }
}
