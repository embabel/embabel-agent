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
package com.embabel.agent.core.support


import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.core.*
import com.embabel.agent.spi.PlannerFactory
import com.embabel.plan.WorldState
import com.embabel.plan.common.condition.ConditionWorldState
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import javax.annotation.concurrent.ThreadSafe
import kotlin.time.measureTime

/**
 * An AgentProcess that can execute multiple actions concurrently.
 * With each invocation of formulateAndExecutePlan(), it will attempt to execute all
 * actions that are currently achievable towards the plan.
 */
@ThreadSafe
open class ConcurrentAgentProcess(
    id: String,
    parentId: String?,
    agent: Agent,
    processOptions: ProcessOptions,
    blackboard: Blackboard,
    platformServices: PlatformServices,
    plannerFactory: PlannerFactory,
    timestamp: Instant = Instant.now(),
    val callbacks: List<AgentProcessCallback> = emptyList(),
) : SimpleAgentProcess(
    id = id,
    parentId = parentId,
    agent = agent,
    processOptions = processOptions,
    blackboard = blackboard,
    platformServices = platformServices,
    plannerFactory = plannerFactory,
    timestamp = timestamp,
) {
    override fun formulateAndExecutePlan(worldState: WorldState): AgentProcess {
        // Mirror SimpleAgentProcess: exclude blacklisted actions, fall back without blacklist if needed
        val plan = planner.bestValuePlanToAnyGoal(
            system = agent.planningSystem,
            excludedActionNames = replanBlacklist,
        )
        if (plan == null) {
            if (replanBlacklist.isNotEmpty()) {
                logger.debug(
                    "No plan found with blacklist {}, clearing and retrying",
                    replanBlacklist,
                )
                replanBlacklist.clear()
                return formulateAndExecutePlan(worldState)
            }
            return handlePlanNotFound(worldState)
        }

        // Clear blacklist after successful planning (matches SimpleAgentProcess behavior)
        replanBlacklist.clear()

        _goal = plan.goal

        if (plan.isComplete()) {
            handleProcessCompletion(plan, worldState)
        } else {
            sendProcessRunningEvent(plan, worldState)

            val achievableActions =
                agent.actions.filter {
                    plan.actions.contains(it) &&
                            it.isAchievable(worldState as ConditionWorldState)
                }
            val actions =
                achievableActions.map { achievableAction ->
                    agent.actions.singleOrNull { it.name == achievableAction.name }
                        ?: error(
                            "No unique action found for ${plan.actions.first().name} in ${
                                agent.actions.map {
                                    it.name
                                }
                            }: Actions are\n${
                                agent.actions.joinToString(
                                    "\n",
                                ) { it.name }
                            }",
                        )
                }
            val process = this
            callbacks.forEach { it.beforeActionLaunched(process) }

            // Collect replan requests from concurrent actions; thread-safe because multiple
            // coroutines may add to this list simultaneously.
            val replanRequests =
                CopyOnWriteArrayList<Pair<Action, ReplanRequestedException>>()

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
                                    } catch (rpe: ReplanRequestedException) {
                                        // Capture for post-execution handling; return TERMINATED so
                                        // the status aggregation loop doesn't fail on a missing value.
                                        replanRequests.add(action to rpe)
                                        ActionStatus(Duration.ZERO, ActionStatusCode.TERMINATED)
                                    } finally {
                                        callbacks.forEach { it.onActionCompleted(process, action) }
                                    }
                                }
                            }.map { deferred ->
                                runBlocking {
                                    deferred.await()
                                }
                            }

                    if (replanRequests.isNotEmpty()) {
                        // If multiple actions requested replan concurrently, handle only the first.
                        // The others' blackboard updates are intentionally dropped — they ran in a
                        // context that is about to be replanned anyway.
                        val (action, rpe) = replanRequests.first()
                        handleReplanRequest(action, rpe)
                    } else {
                        setStatus(actionStatusToAgentProcessStatus(agentStatuses))
                    }
                }
            logger.info("Executed ${actions.size} actions in $elapsed")
        }
        return this
    }

    protected fun actionStatusToAgentProcessStatus(actionStatuses: List<ActionStatus>): AgentProcessStatusCode =
        when {
            // Agent termination takes highest priority - stop entire agent
            actionStatuses.any { it.status == ActionStatusCode.AGENT_TERMINATED } -> {
                val failedCount = actionStatuses.count { it.status == ActionStatusCode.FAILED }
                if (failedCount > 0) {
                    logger.warn("Process {} terminating with {} concurrent failure(s)", id, failedCount)
                }
                logger.debug("Process {} action requested agent termination", id)
                AgentProcessStatusCode.TERMINATED
            }

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

            // Action termination - agent continues (maps to RUNNING)
            actionStatuses.any { it.status == ActionStatusCode.TERMINATED } -> {
                logger.debug("Process {} action terminated early, continuing", id)
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
