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
package com.embabel.agent.spi.support

import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.event.ToolCallRequestEvent
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.ToolGroupResolver
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.time
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import java.time.Duration

/**
 * Decorate tools with metadata and publish events.
 */
class DefaultToolDecorator(
    private val toolGroupResolver: ToolGroupResolver? = null,
    private val observationRegistry: ObservationRegistry? = null,
) : ToolDecorator {

    override fun decorate(
        tool: ToolCallback,
        agentProcess: AgentProcess,
        action: Action?,
        llmOptions: LlmOptions,
    ): ToolCallback {
        val toolGroup = toolGroupResolver?.findToolGroupForTool(toolName = tool.toolDefinition.name())
        return ObservabilityToolCallback(
            delegate = MetadataEnrichedToolCallback(
                toolGroupMetadata = toolGroup?.resolvedToolGroup?.metadata,
                delegate = tool,
            )
                .withEventPublication(
                    agentProcess = agentProcess,
                    action = action,
                    llmOptions = llmOptions,
                ),
            observationRegistry = observationRegistry,
        )
    }
}

/**
 * HOF to decorate a ToolCallback to time the call and emit events.
 */
fun ToolCallback.withEventPublication(
    agentProcess: AgentProcess,
    action: Action?,
    llmOptions: LlmOptions
): ToolCallback =
    this as? EventPublishingToolCallback ?: EventPublishingToolCallback(
        delegate = this,
        agentProcess = agentProcess,
        action = action,
        llmOptions = llmOptions,
    )

class EventPublishingToolCallback(
    private val delegate: ToolCallback,
    private val agentProcess: AgentProcess,
    private val action: Action?,
    private val llmOptions: LlmOptions,
) : ToolCallback {

    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun call(toolInput: String): String {
        val functionCallRequestEvent = ToolCallRequestEvent(
            agentProcess = agentProcess,
            action = action,
            llmOptions = llmOptions,
            function = delegate.toolDefinition.name(),
            toolGroupMetadata = (delegate as? MetadataEnrichedToolCallback)?.toolGroupMetadata,
            toolInput = toolInput,
        )
        val toolCallSchedule =
            agentProcess.processContext.platformServices.operationScheduler.scheduleToolCall(functionCallRequestEvent)
        Thread.sleep(toolCallSchedule.delay.toMillis())
        agentProcess.processContext.onProcessEvent(functionCallRequestEvent)
        val (result: Result<String>, millis) = time {
            try {
                Result.success(delegate.call(toolInput))
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
        agentProcess.processContext.onProcessEvent(
            functionCallRequestEvent.responseEvent(
                result = result,
                runningTime = Duration.ofMillis(millis),
            )
        )
        return if (result.isFailure) {
            throw result.exceptionOrNull() ?: IllegalStateException("Unknown error")
        } else {
            result.getOrThrow()
        }
    }
}
