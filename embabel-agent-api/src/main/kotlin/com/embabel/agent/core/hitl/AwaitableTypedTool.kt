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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.TypedTool
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Abstract typed tool that supports Human-in-the-Loop (HITL) interactions.
 *
 * Before executing the main tool logic, this tool checks if human input is required
 * via [createAwaitable]. If an [Awaitable] is returned, the tool throws
 * [AwaitableResponseException] to pause execution and wait for user response.
 *
 * Example usage:
 * ```kotlin
 * data class DeleteRequest(val path: String, val force: Boolean = false)
 * data class DeleteResult(val deleted: Boolean, val path: String)
 *
 * class ConfirmingDeleteTool : AwaitableTypedTool<DeleteRequest, DeleteResult, DeleteRequest>(
 *     name = "delete_file",
 *     description = "Delete a file with confirmation",
 *     inputType = DeleteRequest::class.java,
 *     outputType = DeleteResult::class.java,
 * ) {
 *     override fun createAwaitable(input: DeleteRequest): Awaitable<DeleteRequest, *>? {
 *         // Only confirm for non-force deletes
 *         return if (!input.force) {
 *             ConfirmationRequest(input, "Delete ${input.path}?")
 *         } else null
 *     }
 *
 *     override fun execute(input: DeleteRequest): DeleteResult {
 *         val success = Files.deleteIfExists(Path.of(input.path))
 *         return DeleteResult(deleted = success, path = input.path)
 *     }
 * }
 * ```
 *
 * @param I Input type - will be deserialized from JSON
 * @param O Output type - will be serialized to JSON
 * @param P Payload type for the awaitable (often same as I)
 * @param name Tool name for LLM consumption
 * @param description Tool description for LLM consumption
 * @param inputType Class of the input type for JSON deserialization
 * @param outputType Class of the output type
 * @param metadata Optional tool metadata
 * @param objectMapper ObjectMapper for JSON serialization/deserialization
 */
abstract class AwaitableTypedTool<I : Any, O : Any, P : Any> @JvmOverloads constructor(
    name: String,
    description: String,
    inputType: Class<I>,
    outputType: Class<O>,
    metadata: Tool.Metadata = Tool.Metadata.DEFAULT,
    objectMapper: ObjectMapper = jacksonObjectMapper(),
) : TypedTool<I, O>(
    name = name,
    description = description,
    inputType = inputType,
    outputType = outputType,
    metadata = metadata,
    objectMapper = objectMapper,
) {

    /**
     * Check if this tool invocation requires human input.
     *
     * @param input The parsed input
     * @return An [Awaitable] if human input is required, null to proceed normally
     */
    abstract fun createAwaitable(input: I): Awaitable<P, *>?

    /**
     * Execute the tool logic after any awaitable has been resolved.
     *
     * @param input The parsed input
     * @return The tool output
     */
    abstract fun execute(input: I): O

    final override fun typedCall(input: I): O {
        createAwaitable(input)?.let { awaitable ->
            throw AwaitableResponseException(awaitable)
        }
        return execute(input)
    }
}

/**
 * Factory for creating awaitable decisions based on tool execution context.
 */
fun interface AwaitableFactory<I : Any, P : Any> {
    /**
     * Create an awaitable based on the input, or null to proceed normally.
     */
    fun create(input: I): Awaitable<P, *>?
}

/**
 * Simple implementation of [AwaitableTypedTool] using functional factories.
 *
 * Example:
 * ```kotlin
 * val tool = SimpleAwaitableTypedTool(
 *     name = "delete_file",
 *     description = "Delete a file",
 *     inputType = DeleteRequest::class.java,
 *     outputType = DeleteResult::class.java,
 *     awaitableFactory = { input ->
 *         ConfirmationRequest(input, "Delete ${input.path}?")
 *     },
 *     executor = { input ->
 *         DeleteResult(deleted = true, path = input.path)
 *     }
 * )
 * ```
 */
class SimpleAwaitableTypedTool<I : Any, O : Any, P : Any> @JvmOverloads constructor(
    name: String,
    description: String,
    inputType: Class<I>,
    outputType: Class<O>,
    private val awaitableFactory: AwaitableFactory<I, P>,
    private val executor: (I) -> O,
    metadata: Tool.Metadata = Tool.Metadata.DEFAULT,
    objectMapper: ObjectMapper = jacksonObjectMapper(),
) : AwaitableTypedTool<I, O, P>(
    name = name,
    description = description,
    inputType = inputType,
    outputType = outputType,
    metadata = metadata,
    objectMapper = objectMapper,
) {

    override fun createAwaitable(input: I): Awaitable<P, *>? = awaitableFactory.create(input)

    override fun execute(input: I): O = executor(input)
}
