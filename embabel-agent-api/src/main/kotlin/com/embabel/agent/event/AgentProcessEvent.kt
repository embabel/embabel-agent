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
package com.embabel.agent.event

import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionStatus
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ToolGroupMetadata
import com.embabel.agent.spi.LlmInteraction
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.Timed
import com.embabel.common.util.VisualizableTask
import com.embabel.plan.Goal
import com.embabel.plan.Plan
import com.embabel.plan.WorldState
import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.ai.chat.prompt.Prompt
import java.time.Duration
import java.time.Instant

/**
 * Event relating to a specific process. Most events are related to a process.
 */
interface AgentProcessEvent : AgenticEvent {

    val processId: String

}

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
}

class AgentProcessCreationEvent(
    agentProcess: AgentProcess,
) : AbstractAgentProcessEvent(agentProcess)

class AgentProcessReadyToPlanEvent(
    agentProcess: AgentProcess,
    val worldState: WorldState,
) : AbstractAgentProcessEvent(agentProcess)

class AgentProcessPlanFormulatedEvent(
    agentProcess: AgentProcess,
    val worldState: WorldState,
    val plan: Plan,
) : AbstractAgentProcessEvent(agentProcess)

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

/**
 * Call to a function from an LLM
 */
class ToolCallRequestEvent(
    agentProcess: AgentProcess,
    val action: Action?,
    val function: String,
    val toolGroupMetadata: ToolGroupMetadata?,
    val toolInput: String,
    val llmOptions: LlmOptions,
) : AbstractAgentProcessEvent(agentProcess) {

    fun responseEvent(result: Result<String>, runningTime: Duration): ToolCallResponseEvent {
        return ToolCallResponseEvent(
            agentProcess = agentProcess,
            action = action,
            function = function,
            toolInput = toolInput,
            llmOptions = llmOptions,
            result = result,
            runningTime = runningTime
        )
    }
}

/**
 * Response from a tool call, whether successful or not.
 */
class ToolCallResponseEvent internal constructor(
    agentProcess: AgentProcess,
    val action: Action?,
    val function: String,
    val toolInput: String,
    val llmOptions: LlmOptions,
    val result: Result<String>,
    override val runningTime: Duration,
) : AbstractAgentProcessEvent(agentProcess), Timed

class AgentProcessFinishedEvent(
    agentProcess: AgentProcess,
) : AbstractAgentProcessEvent(agentProcess)

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
    val outputClass: Class<O>,
    val interaction: LlmInteraction,
    val prompt: String,
) : AbstractAgentProcessEvent(agentProcess) {

    fun callEvent(springAiPrompt: Prompt): ChatModelCallEvent<O> {
        return ChatModelCallEvent(
            agentProcess = agentProcess,
            outputClass = outputClass,
            interaction = interaction,
            prompt = prompt,
            springAiPrompt = springAiPrompt
        )
    }

    fun responseEvent(response: O, runningTime: Duration): LlmResponseEvent<O> {
        return LlmResponseEvent(
            agentProcess = agentProcess,
            interaction = interaction,
            outputClass = outputClass,
            prompt = prompt,
            response = response,
            runningTime = runningTime
        )
    }

    fun maybeResponseEvent(response: Result<O>, runningTime: Duration): LlmResponseEvent<Result<O>> {
        return LlmResponseEvent<Result<O>>(
            agentProcess = agentProcess,
            outputClass = outputClass,
            interaction = interaction,
            prompt = prompt,
            response = response,
            runningTime = runningTime
        )
    }

    override fun toString(): String {
        return "LlmRequestEvent(outputClass=$outputClass, interaction=$interaction, prompt='$prompt')"
    }
}


/**
 * Spring AI low level event
 */
class ChatModelCallEvent<O> internal constructor(
    agentProcess: AgentProcess,
    val outputClass: Class<O>,
    val interaction: LlmInteraction,
    val prompt: String,
    val springAiPrompt: Prompt,
) : AbstractAgentProcessEvent(agentProcess)

/**
 * Response from an LLM
 * @param outputClass normally O, except if this is a maybe response
 * in which case it will be Result<O>
 */
class LlmResponseEvent<O> internal constructor(
    agentProcess: AgentProcess,
    val outputClass: Class<*>,
    val interaction: LlmInteraction,
    val prompt: String,
    val response: O,
    override val runningTime: Duration,
) : AbstractAgentProcessEvent(agentProcess), Timed {

    override fun toString(): String {
        return "LlmResponseEvent(outputClass=$outputClass, interaction=$interaction, prompt=$prompt, response=$response, runningTime=$runningTime)"
    }
}

/**
 * Binding to context
 */
class ObjectAddedEvent(
    agentProcess: AgentProcess,
    val value: Any,
) : AbstractAgentProcessEvent(agentProcess)

class ObjectBoundEvent(
    agentProcess: AgentProcess,
    val name: String,
    val value: Any,
) : AbstractAgentProcessEvent(agentProcess)

/**
 * Progress update
 */
class ProgressUpdateEvent(
    agentProcess: AgentProcess,
    override val name: String,
    override val current: Int,
    override val total: Int,
) : AbstractAgentProcessEvent(agentProcess), VisualizableTask
