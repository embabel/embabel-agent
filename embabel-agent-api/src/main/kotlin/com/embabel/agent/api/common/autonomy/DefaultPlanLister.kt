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

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import com.embabel.common.util.time
import com.embabel.plan.Plan
import com.embabel.plan.common.condition.ConditionPlanner
import com.embabel.plan.common.condition.ConditionPlanningSystem
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
internal class DefaultPlanLister(
    private val agentPlatform: AgentPlatform,
) : PlanLister {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun achievablePlans(
        processOptions: ProcessOptions,
        bindings: Map<String, Any>,
    ): List<Plan> {
        // We'll never run this agent
        val uberAgent = agentPlatform.createAgent(
            name = "PlanLister",
            provider = "Embabel",
            description = "Won't even run, just lists plans",
        )
        // TODO this will get bound to the process repository, which we don't want
        val dummyAgentProcess = agentPlatform.createAgentProcess(
            processOptions = processOptions,
            agent = uberAgent,
            bindings = bindings,
        )
        val planner = dummyAgentProcess.planner as? ConditionPlanner
            ?: TODO("Only GoapPlanners are presently supported: found ${dummyAgentProcess.planner::class.qualifiedName}")
        val planningSystem = planningSystem()
        val (plans, ms) = time {
            planner.plansToGoals(
                system = planningSystem,
            )
        }
        logger.info(
            "Achievable plans given {} actions and {} goals in {}ms from bindings {} : {}\n{}",
            planningSystem.actions.size,
            planningSystem.goals.size,
            ms,
            bindings,
            plans.joinToString("\n") { it.infoString(verbose = true, indent = 1) },
            planningSystem.infoString(verbose = true, indent = 1),
        )
        return plans
    }

    private fun planningSystem(): ConditionPlanningSystem {
        return ConditionPlanningSystem(
            actions = agentPlatform.actions.toSet(),
            goals = agentPlatform.goals,
        )
    }
}
