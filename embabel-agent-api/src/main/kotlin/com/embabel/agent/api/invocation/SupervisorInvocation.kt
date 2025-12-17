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
package com.embabel.agent.api.invocation

import com.embabel.agent.api.annotation.support.SupervisorAction
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.common.scope.AgentScopeBuilder
import com.embabel.agent.core.*
import com.embabel.agent.core.support.AbstractAction
import com.embabel.agent.core.support.Rerun
import com.embabel.agent.spi.common.Constants.EMBABEL_PROVIDER
import com.embabel.common.core.types.Semver
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Invoker for supervisor-orchestrated agents.
 *
 * Uses an LLM to orchestrate actions from `@EmbabelComponent` and `@Agent` annotated classes
 * to achieve a specified goal type. The supervisor sees type schemas and decides which
 * actions to call based on available artifacts on the blackboard.
 *
 * Unlike [UtilityInvocation], this requires a goal type to work toward.
 *
 * @param agentPlatform the agent platform to create and manage agent processes
 * @param goalType the type of object to produce as the goal
 * @param goalDescription description of the goal for the supervisor LLM
 * @param processOptions options to configure the agent process
 * @param agentScopeBuilder emits the scope to create the supervisor agent in
 */
data class SupervisorInvocation<T : Any> @JvmOverloads constructor(
    private val agentPlatform: AgentPlatform,
    private val goalType: Class<T>,
    private val goalDescription: String = "Produce ${goalType.simpleName}",
    private val processOptions: ProcessOptions = ProcessOptions(),
    private val agentScopeBuilder: AgentScopeBuilder = AgentScopeBuilder.fromPlatform(agentPlatform),
) : TypedInvocation<T> {

    override val resultType: Class<T> get() = goalType

    private val logger = LoggerFactory.getLogger(javaClass)

    fun withProcessOptions(options: ProcessOptions): SupervisorInvocation<T> =
        copy(processOptions = options)

    fun withGoalDescription(description: String): SupervisorInvocation<T> =
        copy(goalDescription = description)

    fun withScope(agentScopeBuilder: AgentScopeBuilder): SupervisorInvocation<T> =
        copy(agentScopeBuilder = agentScopeBuilder)

    override fun runAsync(
        obj: Any,
        vararg objs: Any,
    ): CompletableFuture<AgentProcess> {
        val args = arrayOf(obj, *objs)
        val agentProcess = agentPlatform.createAgentProcessFrom(
            agent = createSupervisorAgent(),
            processOptions = validProcessOptions(),
            objectsToAdd = args,
        )
        return agentPlatform.start(agentProcess)
    }

    override fun runAsync(map: Map<String, Any>): CompletableFuture<AgentProcess> {
        val agentProcess = agentPlatform.createAgentProcess(
            agent = createSupervisorAgent(),
            processOptions = validProcessOptions(),
            bindings = map,
        )
        return agentPlatform.start(agentProcess)
    }

    /**
     * Create a supervisor agent from platform actions.
     *
     * This wraps all available actions in a [SupervisorAction] that uses an LLM
     * to orchestrate them toward the goal.
     */
    fun createSupervisorAgent(): Agent {
        val scope = agentScopeBuilder.build()

        // Get all actions from the scope (excluding any existing goal actions)
        val allActions = scope.actions.toList()

        if (allActions.isEmpty()) {
            throw IllegalStateException(
                "No actions available for supervisor. " +
                        "Ensure @EmbabelComponent or @Agent classes with @Action methods are registered."
            )
        }

        logger.info(
            "Creating supervisor agent with {} tool actions, goal type: {}",
            allActions.size,
            goalType.simpleName
        )

        // Create a synthetic goal action that produces the goal type
        val goalAction = GoalAction(goalType, goalDescription)

        // Create the supervisor action that orchestrates all tool actions
        val supervisorAction = SupervisorAction(
            name = "platform.supervisor",
            description = "Supervisor action that orchestrates tools to achieve: $goalDescription",
            inputs = computeSupervisorInputs(allActions, goalAction),
            outputs = setOf(IoBinding(type = goalType)),
            toolActions = allActions,
            goalAction = goalAction,
        )

        // Create the goal that the supervisor works toward
        val goal = Goal(
            name = "supervisor.goal",
            description = goalDescription,
            inputs = setOf(IoBinding(type = goalType)),
            outputType = JvmType(goalType),
            pre = setOf(Rerun.hasRunCondition(supervisorAction)),
        )

        return Agent(
            name = "${agentPlatform.name}.supervisor",
            provider = EMBABEL_PROVIDER,
            description = "Platform supervisor agent targeting ${goalType.simpleName}",
            version = Semver("0.1.0"),
            conditions = scope.conditions,
            actions = listOf(supervisorAction),
            goals = setOf(goal),
            opaque = false,
        )
    }

    /**
     * Compute the inputs needed for the supervisor action.
     * These are inputs from tool actions that aren't produced by any action.
     */
    private fun computeSupervisorInputs(
        toolActions: List<Action>,
        goalAction: Action,
    ): Set<IoBinding> {
        val allActions = toolActions + goalAction
        val allOutputTypes = allActions.flatMap { it.outputs }.map { it.type }.toSet()

        val toolInputs = toolActions.flatMap { it.inputs }
        val goalInputsNotProduced = goalAction.inputs.filter { it.type !in allOutputTypes }

        return (toolInputs + goalInputsNotProduced)
            .filter { input -> input.type !in allOutputTypes }
            .distinctBy { it.type }
            .toSet()
    }

    private fun validProcessOptions(): ProcessOptions {
        return if (processOptions.plannerType == PlannerType.SUPERVISOR) {
            processOptions
        } else {
            logger.info("Correcting plannerType to {} for SupervisorInvocation", PlannerType.SUPERVISOR)
            processOptions.copy(plannerType = PlannerType.SUPERVISOR)
        }
    }

    companion object {

        /**
         * Create a SupervisorInvocation targeting the specified goal type.
         *
         * @param agentPlatform the platform containing actions to orchestrate
         * @param goalType the type of object to produce
         */
        @JvmStatic
        fun <T : Any> on(
            agentPlatform: AgentPlatform,
            goalType: Class<T>,
        ): SupervisorInvocation<T> =
            SupervisorInvocation(
                agentPlatform = agentPlatform,
                goalType = goalType,
            )

        /**
         * Kotlin-friendly factory using reified type.
         */
        inline fun <reified T : Any> on(
            agentPlatform: AgentPlatform,
        ): SupervisorInvocation<T> =
            on(agentPlatform, T::class.java)
    }
}

/**
 * A goal action that produces the goal type.
 * Called by the supervisor when it determines the goal is achieved.
 */
private class GoalAction<T : Any>(
    private val goalType: Class<T>,
    goalDescription: String,
) : AbstractAction(
    name = "supervisor.achieveGoal",
    description = goalDescription,
    pre = emptyList(),
    post = emptyList(),
    cost = { 0.0 },
    value = { 1.0 },
    inputs = setOf(IoBinding(type = goalType)),
    outputs = setOf(IoBinding(type = goalType)),
    toolGroups = emptySet(),
    canRerun = false,
    qos = ActionQos(),
) {
    override fun execute(processContext: ProcessContext): ActionStatus {
        // The goal type should already be on the blackboard
        val result = processContext.blackboard.objects.find { goalType.isInstance(it) }
        return if (result != null) {
            logger.debug("Goal action found {} on blackboard", goalType.simpleName)
            ActionStatus(Duration.ZERO, ActionStatusCode.SUCCEEDED)
        } else {
            logger.warn("Goal action: {} not found on blackboard", goalType.simpleName)
            ActionStatus(Duration.ZERO, ActionStatusCode.FAILED)
        }
    }

    override fun referencedInputProperties(variable: String): Set<String> = emptySet()
}
