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
package com.embabel.agent.api.event

import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionInvocation
import com.embabel.agent.core.ActionStatus
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusReport
import com.embabel.agent.core.InProcess
import com.embabel.agent.core.ToolGroupMetadata
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.Timed
import com.embabel.common.util.VisualizableTask
import com.embabel.plan.Goal
import com.embabel.plan.Plan
import com.embabel.plan.WorldState
import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Duration
import java.time.Instant

/**
 * Event relating to a specific process. Most events are related to a process.
 */
interface AgentProcessEvent : AgenticEvent, InProcess

/**
 * Convenient superclass for AgentProcessEvent implementations
 */
abstract class AbstractAgentProcessEvent(
    @JsonIgnore
    val agentProcess: AgentProcess,
) : AgentProcessEvent {

    override val timestamp: Instant = Instant.now()

    override val processId: String
        get() = agentProcess.id

    val history: List<ActionInvocation>
        get() = agentProcess.history

    val status: AgentProcessStatusReport get() = agentProcess.statusReport()
}

class AgentProcessCreationEvent(
    agentProcess: AgentProcess,
) : AbstractAgentProcessEvent(agentProcess)

class AgentProcessReadyToPlanEvent(
    agentProcess: AgentProcess,
    val worldState: WorldState,
) : AbstractAgentProcessEvent(agentProcess)

class ReplanRequestedEvent(
    agentProcess: AgentProcess,
    val reason: String,
) : AbstractAgentProcessEvent(agentProcess)

class AgentProcessPlanFormulatedEvent(
    agentProcess: AgentProcess,
    val worldState: WorldState,
    val plan: Plan,
) : AbstractAgentProcessEvent(agentProcess)

/**
 * The agent process has transitioned to a new state.
 * @param newState the new state instance
 * @param previousState the previous state instance, or null if this is the initial state
 */
class StateTransitionEvent(
    agentProcess: AgentProcess,
    val newState: Any,
    val previousState: Any? = null,
) : AbstractAgentProcessEvent(agentProcess) {

    /** True if this is the initial state entry (no previous state) */
    val isInitialState: Boolean get() = previousState == null

    /** True if staying in the same state instance (return this) */
    val isSameInstance: Boolean get() = previousState === newState

    /** True if transitioning to a new instance of the same state type */
    val isSameType: Boolean get() = previousState != null && previousState.javaClass == newState.javaClass
}

class GoalAchievedEvent(
    agentProcess: AgentProcess,
    val worldState: WorldState,
    val goal: Goal,
) : AbstractAgentProcessEvent(agentProcess)

class ActionExecutionStartEvent(
    agentProcess: AgentProcess,
    val action: Action,
) : AbstractAgentProcessEvent(agentProcess) {

    fun resultEvent(
        actionStatus: ActionStatus,
    ): ActionExecutionResultEvent {
        return ActionExecutionResultEvent(
            agentProcess = agentProcess,
            action = action,
            actionStatus = actionStatus,
            runningTime = Duration.between(timestamp, Instant.now())
        )
    }
}

class ActionExecutionResultEvent internal constructor(
    agentProcess: AgentProcess,
    val action: Action,
    val actionStatus: ActionStatus,
    override val runningTime: Duration,
) : AbstractAgentProcessEvent(agentProcess), Timed

class ToolLoopStartEvent(
    agentProcess: AgentProcess,
    val action: Action?,
    val toolNames: List<String>,
    val maxIterations: Int,
    val interactionId: String,
    val outputClass: Class<*>,
) : AbstractAgentProcessEvent(agentProcess) {

    fun completedEvent(
        totalIterations: Int,
        replanRequested: Boolean,
    ): ToolLoopCompletedEvent {
        return ToolLoopCompletedEvent(
            agentProcess = agentProcess,
            action = action,
            toolNames = toolNames,
            maxIterations = maxIterations,
            interactionId = interactionId,
            outputClass = outputClass,
            totalIterations = totalIterations,
            replanRequested = replanRequested,
            runningTime = Duration.between(timestamp, Instant.now()),
        )
    }
}

class ToolLoopCompletedEvent internal constructor(
    agentProcess: AgentProcess,
    val action: Action?,
    val toolNames: List<String>,
    val maxIterations: Int,
    val interactionId: String,
    val outputClass: Class<*>,
    val totalIterations: Int,
    val replanRequested: Boolean,
    override val runningTime: Duration,
) : AbstractAgentProcessEvent(agentProcess), Timed

/**
 * Call to a function from an LLM
 * @param correlationId correlation ID for this tool call, useful for UI
 */
class ToolCallRequestEvent(
    agentProcess: AgentProcess,
    val action: Action?,
    val tool: String,
    val toolGroupMetadata: ToolGroupMetadata?,
    val toolInput: String,
    val llmOptions: LlmOptions,
    val correlationId: String = "${agentProcess.id}-$tool-${System.currentTimeMillis()}",
) : AbstractAgentProcessEvent(agentProcess) {

    fun responseEvent(
        result: Result<String>,
        runningTime: Duration,
    ): ToolCallResponseEvent {
        return ToolCallResponseEvent(
            request = this,
            result = result,
            runningTime = runningTime
        )
    }
}

/**
 * Response from a tool call, whether successful or not.
 */
class ToolCallResponseEvent internal constructor(
    val request: ToolCallRequestEvent,
    val result: Result<String>,
    override val runningTime: Duration,
) : AbstractAgentProcessEvent(request.agentProcess), Timed

/**
 * The agent process has finished.
 * It may have completed successfully or failed.
 * Check the status code to determine the outcome.
 */
sealed class AgentProcessFinishedEvent(
    agentProcess: AgentProcess,
) : AbstractAgentProcessEvent(agentProcess)

class AgentProcessCompletedEvent(
    agentProcess: AgentProcess,
) : AgentProcessFinishedEvent(agentProcess) {

    val result: Any
        get() =
            agentProcess.lastResult() ?: throw IllegalStateException("Agent process ${agentProcess.id} has no result")
}

class AgentProcessFailedEvent(
    agentProcess: AgentProcess,
) : AgentProcessFinishedEvent(agentProcess)

class AgentProcessWaitingEvent(
    agentProcess: AgentProcess,
) : AbstractAgentProcessEvent(agentProcess)

class AgentProcessPausedEvent(
    agentProcess: AgentProcess,
) : AbstractAgentProcessEvent(agentProcess)

/**
 * The AgentProcess is unable to plan from its present state.
 * @param agentProcess the agent process
 */
class AgentProcessStuckEvent(
    agentProcess: AgentProcess,
) : AbstractAgentProcessEvent(agentProcess)


class LlmRequestEvent<O>(
    agentProcess: AgentProcess,
    val action: Action?,
    val outputClass: Class<O>,
    val interaction: LlmInteraction,
    val llmMetadata: LlmMetadata,
    val messages: List<Message>,
) : AbstractAgentProcessEvent(agentProcess) {

    fun responseEvent(
        response: O,
        runningTime: Duration,
    ): LlmResponseEvent<O> {
        return LlmResponseEvent(
            request = this,
            outputClass = outputClass,
            response = response,
            runningTime = runningTime
        )
    }

    fun maybeResponseEvent(
        response: Result<O>,
        runningTime: Duration,
    ): LlmResponseEvent<Result<O>> {
        return LlmResponseEvent(
            request = this,
            outputClass = outputClass,
            response = response,
            runningTime = runningTime
        )
    }

    override fun toString(): String {
        return "LlmRequestEvent(outputClass=$outputClass, interaction=$interaction, messages=$messages)"
    }
}


/**
 * Response from an LLM
 * @param outputClass normally O, except if this is a maybe response
 * in which case it will be Result<O>
 */
class LlmResponseEvent<O> internal constructor(
    val request: LlmRequestEvent<*>,
    val outputClass: Class<*>,
    val response: O,
    override val runningTime: Duration,
) : AbstractAgentProcessEvent(request.agentProcess), Timed {

    override fun toString(): String {
        return "LlmResponseEvent(outputClass=$outputClass, request=$request, response=$response, runningTime=$runningTime)"
    }
}

/**
 * An object was bound to the process.
 * May or may not be found. See subclasses for details.
 */
interface ObjectBindingEvent : AgentProcessEvent {

    val value: Any

    val type: String
        get() = value::class.java.name
}

/**
 * Binding to context
 */
class ObjectAddedEvent(
    agentProcess: AgentProcess,
    override val value: Any,
) : AbstractAgentProcessEvent(agentProcess), ObjectBindingEvent

class ObjectBoundEvent(
    agentProcess: AgentProcess,
    val name: String,
    override val value: Any,
) : AbstractAgentProcessEvent(agentProcess), ObjectBindingEvent

/**
 * Progress update
 */
class ProgressUpdateEvent(
    agentProcess: AgentProcess,
    override val name: String,
    override val current: Int,
    override val total: Int,
) : AbstractAgentProcessEvent(agentProcess), VisualizableTask

class ProcessKilledEvent(
    agentProcess: AgentProcess,
) : AbstractAgentProcessEvent(agentProcess)
