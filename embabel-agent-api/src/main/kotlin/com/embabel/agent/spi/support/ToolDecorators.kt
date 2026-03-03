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
package com.embabel.agent.spi.support

import com.embabel.agent.api.event.ToolCallRequestEvent
import com.embabel.agent.api.tool.DelegatingTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.api.tool.ToolControlFlowSignal
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ToolGroupMetadata
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.StringTransformer
import com.embabel.common.util.loggerFor
import com.embabel.common.util.time
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import java.time.Duration


/**
 * Unwrap a tool to find a specific type, or return null if not found.
 */
inline fun <reified T : Tool> Tool.unwrapAs(): T? {
    var current = this
    while (true) {
        if (current is T) return current
        if (current is DelegatingTool) {
            current = current.delegate
        } else {
            return null
        }
    }
}

/**
 * Extension to get the content string from any Tool.Result variant.
 */
private val Tool.Result.content: String
    get() = when (this) {
        is Tool.Result.Text -> content
        is Tool.Result.WithArtifact -> content
        is Tool.Result.Error -> message
    }

/**
 * Tool decorator that adds Micrometer Observability.
 */
class ObservabilityTool(
    override val delegate: Tool,
    private val observationRegistry: ObservationRegistry? = null,
) : DelegatingTool {

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result =
        callWithObservation(input) { delegate.call(input) }

    override fun call(input: String, context: ToolCallContext): Tool.Result =
        callWithObservation(input) { delegate.call(input, context) }

    private inline fun callWithObservation(input: String, action: () -> Tool.Result): Tool.Result {
        if (observationRegistry == null) {
            return action()
        }
        val currentObservation = observationRegistry.currentObservation
        if (currentObservation == null) {
            loggerFor<ObservabilityTool>().warn(
                "No parent observation for tool call {} with input: {}, observation registry: {}",
                delegate.definition.name,
                input,
                observationRegistry,
            )
        }
        val observation = Observation.createNotStarted("tool call", observationRegistry)
            .lowCardinalityKeyValue("toolName", delegate.definition.name)
            .highCardinalityKeyValue("payload", input)
            .parentObservation(currentObservation)
            .start()
        return try {
            val result = action()
            observation.lowCardinalityKeyValue("status", "success")
            observation.highCardinalityKeyValue("result", result.content)
            result
        } catch (ex: Exception) {
            observation.lowCardinalityKeyValue("status", "error")
            observation.highCardinalityKeyValue("error_type", ex::class.simpleName ?: "Unknown")
            observation.highCardinalityKeyValue("error_message", ex.message ?: "No message")
            observation.error(ex)
            throw ex
        } finally {
            observation.stop()
        }
    }

    override fun toString(): String = "ObservabilityTool(delegate=${delegate.definition.name})"
}

/**
 * Tool decorator that transforms the output using a provided [StringTransformer].
 */
class OutputTransformingTool(
    override val delegate: Tool,
    private val outputTransformer: StringTransformer,
) : DelegatingTool {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result =
        transformOutput(input) { delegate.call(input) }

    override fun call(input: String, context: ToolCallContext): Tool.Result =
        transformOutput(input) { delegate.call(input, context) }

    private inline fun transformOutput(input: String, action: () -> Tool.Result): Tool.Result {
        val rawResult = action()
        val transformed = outputTransformer.transform(rawResult.content)
        logger.debug(
            "Tool {} called with input: {}, raw output: {}, transformed output: {}",
            delegate.definition.name,
            input,
            rawResult.content,
            transformed
        )
        val saving = rawResult.content.length - transformed.length
        logger.debug("Saved {} bytes from {}", saving, rawResult.content.length)
        return Tool.Result.text(transformed)
    }
}

/**
 * Tool decorator that adds metadata about the tool group.
 */
class MetadataEnrichingTool(
    override val delegate: Tool,
    val toolGroupMetadata: ToolGroupMetadata?,
) : DelegatingTool {

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result =
        callWithMetadata(input) { delegate.call(input) }

    override fun call(input: String, context: ToolCallContext): Tool.Result =
        callWithMetadata(input) { delegate.call(input, context) }

    private inline fun callWithMetadata(input: String, action: () -> Tool.Result): Tool.Result {
        try {
            return action()
        } catch (t: Throwable) {
            if (t is ToolControlFlowSignal) {
                throw t
            }
            loggerFor<MetadataEnrichingTool>().warn(
                "Tool call failure on ${delegate.definition.name}: input from LLM was <$input>",
                t,
            )
            throw t
        }
    }

    override fun toString(): String =
        "MetadataEnrichedTool(toolGroupMetadata=$toolGroupMetadata, delegate=${delegate.definition.name})"
}

/**
 * Tool decorator that publishes events for tool calls.
 */
class EventPublishingTool(
    override val delegate: Tool,
    private val agentProcess: AgentProcess,
    private val action: Action?,
    private val llmOptions: LlmOptions,
) : DelegatingTool {

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result =
        callWithEvents(input) { delegate.call(input) }

    override fun call(input: String, context: ToolCallContext): Tool.Result =
        callWithEvents(input) { delegate.call(input, context) }

    private inline fun callWithEvents(input: String, crossinline action: () -> Tool.Result): Tool.Result {
        val functionCallRequestEvent = ToolCallRequestEvent(
            agentProcess = agentProcess,
            action = this.action,
            llmOptions = llmOptions,
            tool = delegate.definition.name,
            toolGroupMetadata = (delegate as? MetadataEnrichingTool)?.toolGroupMetadata,
            toolInput = input,
        )
        val toolCallSchedule =
            agentProcess.processContext.platformServices.operationScheduler.scheduleToolCall(functionCallRequestEvent)
        Thread.sleep(toolCallSchedule.delay.toMillis())
        agentProcess.processContext.onProcessEvent(functionCallRequestEvent)
        val (result: Result<Tool.Result>, millis) = time {
            try {
                Result.success(action())
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
        agentProcess.processContext.onProcessEvent(
            functionCallRequestEvent.responseEvent(
                result = result.map { it.content },
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

/**
 * Extension function to wrap a Tool with event publication.
 */
fun Tool.withEventPublication(
    agentProcess: AgentProcess,
    action: Action?,
    llmOptions: LlmOptions,
): Tool = this as? EventPublishingTool ?: EventPublishingTool(
    delegate = this,
    agentProcess = agentProcess,
    action = action,
    llmOptions = llmOptions,
)

/**
 * Tool decorator that suppresses exceptions and returns a warning message instead.
 *
 * Note: [ToolControlFlowSignal] exceptions are NOT suppressed - they are control flow signals
 * that must propagate (e.g., ReplanRequestedException, UserInputRequiredException).
 */
class ExceptionSuppressingTool(
    override val delegate: Tool,
) : DelegatingTool {

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result =
        callSuppressing { delegate.call(input) }

    override fun call(input: String, context: ToolCallContext): Tool.Result =
        callSuppressing { delegate.call(input, context) }

    private inline fun callSuppressing(action: () -> Tool.Result): Tool.Result {
        return try {
            action()
        } catch (t: Throwable) {
            if (t is ToolControlFlowSignal) throw t
            Tool.Result.text("WARNING: Tool '${delegate.definition.name}' failed with exception: ${t.message ?: "No message"}")
        }
    }
}

/**
 * Tool decorator that binds AgentProcess to thread-local for tool execution.
 */
class AgentProcessBindingTool(
    override val delegate: Tool,
    private val agentProcess: AgentProcess,
) : DelegatingTool {

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result =
        callWithBinding { delegate.call(input) }

    override fun call(input: String, context: ToolCallContext): Tool.Result =
        callWithBinding { delegate.call(input, context) }

    private inline fun callWithBinding(action: () -> Tool.Result): Tool.Result {
        val previousValue = AgentProcess.get()
        try {
            AgentProcess.set(agentProcess)
            return action()
        } finally {
            if (previousValue != null) {
                AgentProcess.set(previousValue)
            } else {
                AgentProcess.remove()
            }
        }
    }
}
