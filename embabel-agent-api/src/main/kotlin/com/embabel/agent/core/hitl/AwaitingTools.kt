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
package com.embabel.agent.core.hitl

import com.embabel.agent.api.tool.DelegatingTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess

/**
 * Context provided to awaiting decision functions.
 *
 * @param input The raw JSON input to the tool
 * @param agentProcess The current agent process
 * @param tool Info about the tool being called
 */
data class AwaitContext(
    val input: String,
    val agentProcess: AgentProcess,
    val tool: Tool,
)

/**
 * Functional interface for deciding whether to await before tool execution.
 */
fun interface AwaitDecider {
    /**
     * Evaluate whether awaiting is needed before tool execution.
     *
     * @param context The context containing input and process state
     * @return An [Awaitable] to pause execution, or null to proceed normally
     */
    fun evaluate(context: AwaitContext): Awaitable<*, *>?
}

/**
 * Tool decorator that always requires confirmation before executing the delegate.
 *
 * When called, this tool throws [AwaitableResponseException] with a [ConfirmationRequest].
 * The framework handles the pause, the UX presents the confirmation, and if accepted,
 * the tool is re-invoked (this time the confirmation is already satisfied via blackboard state).
 *
 * @param delegate The tool to wrap
 * @param messageProvider Function to generate the confirmation message from input
 */
class ConfirmingTool(
    override val delegate: Tool,
    private val messageProvider: (String) -> String,
) : DelegatingTool {

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result {
        val message = messageProvider(input)
        throw AwaitableResponseException(
            ConfirmationRequest(
                payload = input,
                message = message,
            )
        )
    }
}

/**
 * Tool decorator that conditionally requires awaiting before execution.
 *
 * The [decider] inspects the input context and returns either:
 * - An [Awaitable] to pause execution and wait for user input
 * - `null` to proceed with normal tool execution
 *
 * @param delegate The tool to wrap
 * @param decider Function that decides whether to await based on input context
 */
class ConditionalAwaitingTool(
    override val delegate: Tool,
    private val decider: AwaitDecider,
) : DelegatingTool {

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result {
        val agentProcess = AgentProcess.get()
            ?: throw IllegalStateException("No AgentProcess available for ConditionalAwaitingTool")

        val context = AwaitContext(
            input = input,
            agentProcess = agentProcess,
            tool = delegate,
        )

        decider.evaluate(context)?.let { awaitable ->
            throw AwaitableResponseException(awaitable)
        }

        return delegate.call(input)
    }
}

/**
 * Tool decorator that requires a value of type [T] from the user before execution.
 *
 * When called, this tool throws [AwaitableResponseException] with a [TypeRequest].
 * The UX presents a form for the type, and when the user provides the value,
 * it's added to the blackboard and the tool is re-invoked.
 *
 * @param T The type of value to request
 * @param delegate The tool to wrap
 * @param type The class of the requested type
 * @param messageProvider Function to generate the request message from input
 */
class TypeRequestingTool<T : Any>(
    override val delegate: Tool,
    private val type: Class<T>,
    private val messageProvider: (String) -> String? = { null },
) : DelegatingTool {

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result {
        throw AwaitableResponseException(
            TypeRequest(
                type = type,
                message = messageProvider(input),
            )
        )
    }
}

// Extension functions for fluent tool decoration

/**
 * Wrap this tool to always require confirmation before execution.
 *
 * @param messageProvider Function to generate the confirmation message from input
 */
fun Tool.withConfirmation(messageProvider: (String) -> String): Tool =
    ConfirmingTool(this, messageProvider)

/**
 * Wrap this tool to always require confirmation before execution.
 *
 * @param message Static confirmation message
 */
fun Tool.withConfirmation(message: String): Tool =
    ConfirmingTool(this) { message }

/**
 * Wrap this tool to conditionally await before execution.
 *
 * @param decider Function that decides whether to await based on input context
 */
fun Tool.withAwaiting(decider: AwaitDecider): Tool =
    ConditionalAwaitingTool(this, decider)

/**
 * Wrap this tool to require a value of type [T] before execution.
 *
 * @param T The type of value to request
 * @param type The class of the requested type
 * @param messageProvider Function to generate the request message from input
 */
fun <T : Any> Tool.requireType(
    type: Class<T>,
    messageProvider: (String) -> String? = { null },
): Tool = TypeRequestingTool(this, type, messageProvider)

/**
 * Wrap this tool to require a value of type [T] before execution (reified).
 *
 * @param T The type of value to request
 * @param message Optional message explaining what's needed
 */
inline fun <reified T : Any> Tool.requireType(message: String? = null): Tool =
    TypeRequestingTool(this, T::class.java) { message }
