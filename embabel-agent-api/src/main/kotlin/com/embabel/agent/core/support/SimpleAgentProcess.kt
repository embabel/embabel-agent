/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.core.support

import com.embabel.agent.core.*
import com.embabel.agent.event.AgentProcessPlanFormulatedEvent
import com.embabel.agent.event.GoalAchievedEvent
import com.embabel.agent.spi.PlatformServices
import com.embabel.common.util.indentLines
import com.embabel.plan.WorldState
import com.embabel.plan.goap.AStarGoapPlanner
import com.embabel.plan.goap.WorldStateDeterminer
import java.time.Instant

internal class SimpleAgentProcess(
    id: String,
    parentId: String?,
    agent: Agent,
    processOptions: ProcessOptions,
    blackboard: Blackboard,
    platformServices: PlatformServices,
    timestamp: Instant = Instant.now(),
) : AbstractAgentProcess(
    id = id,
    parentId = parentId,
    agent = agent,
    processOptions = processOptions,
    blackboard = blackboard,
    platformServices = platformServices,
    timestamp = timestamp,
) {

    private val _llmInvocations = mutableListOf<LlmInvocation>()

    override val llmInvocations: List<LlmInvocation>
        get() = _llmInvocations.toList()

    override val worldStateDeterminer: WorldStateDeterminer = BlackboardWorldStateDeterminer(processContext)

    override val planner = AStarGoapPlanner(worldStateDeterminer)

    override fun recordLlmInvocation(llmInvocation: LlmInvocation) {
        _llmInvocations.add(llmInvocation)
    }

    override fun formulateAndExecutePlan(worldState: WorldState): AgentProcess {
        val plan = planner.bestValuePlanToAnyGoal(system = agent.planningSystem)
        if (plan == null) {
            logger.info(
                "❌ Process $id stuck\n" +
                """|No plan from:
                   |${worldState.infoString(verbose = true, indent = 1)}
                   |in:
                   |${agent.planningSystem.infoString(verbose = true, 1)}
                   |context:
                   |${blackboard.infoString(true, 1)}
                   |"""
                    .trimMargin()
                    .indentLines(1)
            )
            setStatus(AgentProcessStatusCode.STUCK)
            return this
        }

        if (goal != null && goal?.name != plan.goal.name) {
            logger.info("Process {} goal changed: {} -> {}", this.id, goal?.name, plan.goal.name)
            require(processOptions.allowGoalChange) {
                "Process ${this.id} goal changed from ${goal?.name} to ${plan.goal.name}, but allowGoalChange is false"
            }
        }
        _goal = plan.goal

        if (plan.isComplete()) {
            logger.debug(
                "✅ Process {} completed, achieving goal {} in {} seconds",
                this.id,
                plan.goal.name,
                this.runningTime.seconds,
            )
            platformServices.eventListener.onProcessEvent(
                GoalAchievedEvent(
                    agentProcess = this,
                    worldState = worldState,
                    goal = plan.goal,
                )
            )
            logger.debug("Final blackboard: {}", blackboard.infoString())
            setStatus(AgentProcessStatusCode.COMPLETED)
        } else {
            platformServices.eventListener.onProcessEvent(
                AgentProcessPlanFormulatedEvent(
                    agentProcess = this,
                    worldState = worldState,
                    plan = plan,
                )
            )
            logger.debug("▶️ Process {} running: {}\n\tPlan: {}", id, worldState, plan.infoString())
            val agent = agent.actions.singleOrNull { it.name == plan.actions.first().name }
                ?: error(
                    "No unique action found for ${plan.actions.first().name} in ${agent.actions.map { it.name }}: Actions are\n${
                        agent.actions.joinToString(
                            "\n"
                        ) { it.name }
                    }")
            val actionStatus = executeAction(agent)
            setStatus(actionStatusToAgentProcessStatus(actionStatus))
        }
        return this
    }
}
