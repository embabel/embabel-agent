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


import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.core.*
import com.embabel.plan.WorldState
import com.embabel.plan.goap.GoapWorldState
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.time.measureTime

/**
 * An AgentProcess that can execute multiple actions concurrently.
 * With each invocation of formulateAndExecutePlan(), it will attempt to execute all
 * actions that are currently achievable towards the plan.
 */
open class ConcurrentAgentProcess(
    id: String,
    parentId: String?,
    agent: Agent,
    processOptions: ProcessOptions,
    blackboard: Blackboard,
    platformServices: PlatformServices,
    timestamp: Instant = Instant.now(),
    val callbacks: List<AgentProcessCallback> = emptyList()
) : SimpleAgentProcess(
    id = id,
    parentId = parentId,
    agent = agent,
    processOptions = processOptions,
    blackboard = blackboard,
    platformServices = platformServices,
    timestamp = timestamp,
    ) {
    override fun formulateAndExecutePlan(worldState: WorldState): AgentProcess {
        val plan = planner.bestValuePlanToAnyGoal(system = agent.planningSystem)
        if (plan == null) {
            return handlePlanNotFound(worldState)
        }

        logGoalInformation(plan)
        _goal = plan.goal

        if (plan.isComplete()) {
            handleProcessCompletion(plan, worldState)
        } else {
            sendProcessRunningEvent(plan, worldState)

            val achievableActions =
                agent.actions.filter {
                    plan.actions.contains(it) &&
                            it.isAchievable(worldState as GoapWorldState)
                }
            val actions =
                achievableActions.map { achievableAction ->
                    agent.actions.singleOrNull { it.name == achievableAction.name }
                        ?: error(
                            "No unique action found for ${plan.actions.first().name} in ${agent.actions.map {
                                it.name
                            }}: Actions are\n${
                                agent.actions.joinToString(
                                    "\n",
                                ) { it.name }
                            }",
                        )
                }
            val process = this
            callbacks.forEach { it.beforeActionLaunched(process) }
            val elapsed =
                measureTime {
                    logger.info("Executing ${actions.size} actions concurrently: \n${actions.map { it.name }}")
                    val agentStatuses =
                        actions
                            .map { action ->
                                platformServices.asyncer.async {
                                    try {
                                        callbacks.forEach { it.onActionLaunched(process, action) }
                                        executeAction(action)
                                    } finally {
                                        callbacks.forEach { it.onActionCompleted(process, action) }
                                    }
                                }
                            }.map { deferred ->
                                runBlocking {
                                    deferred.await()
                                }
                            }
                    setStatus(actionStatusToAgentProcessStatus(agentStatuses))
                }
            logger.info("Executed ${actions.size} actions in $elapsed")
        }
        return this
    }

    protected fun actionStatusToAgentProcessStatus(actionStatuses: List<ActionStatus>): AgentProcessStatusCode =
        when {
            actionStatuses.any { it.status == ActionStatusCode.FAILED } -> {
                logger.debug("❌ Process {} action {} failed", id, ActionStatusCode.FAILED)
                AgentProcessStatusCode.FAILED
            }

            actionStatuses.any { it.status == ActionStatusCode.PAUSED } -> {
                logger.debug("⏳ Process {} action {} paused", id, ActionStatusCode.PAUSED)
                AgentProcessStatusCode.PAUSED
            }
            actionStatuses.any { it.status == ActionStatusCode.SUCCEEDED } -> {
                logger.debug("Process {} action {} is running", id, ActionStatusCode.SUCCEEDED)
                AgentProcessStatusCode.RUNNING
            }
            actionStatuses.any { it.status == ActionStatusCode.WAITING } -> {
                logger.debug("⏳ Process {} action {} waiting", id, ActionStatusCode.WAITING)
                AgentProcessStatusCode.WAITING
            }
            else -> {
                error("Unexpected action statuses: $actionStatuses")
            }
        }
}
