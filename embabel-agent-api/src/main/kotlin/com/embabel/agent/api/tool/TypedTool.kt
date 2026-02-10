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
package com.embabel.agent.api.tool

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.function.Function

/**
 * Tool with strongly typed input and output.
 * Handles JSON marshaling automatically, allowing you to work with domain objects directly.
 *
 * Example usage:
 * ```kotlin
 * data class AddRequest(val a: Int, val b: Int)
 * data class AddResult(val sum: Int)
 *
 * val addTool = TypedTool(
 *     name = "add",
 *     description = "Add two numbers",
 *     inputType = AddRequest::class.java,
 *     outputType = AddResult::class.java,
 * ) { input -> AddResult(input.a + input.b) }
 * ```
 *
 * @param I Input type - will be deserialized from JSON
 * @param O Output type - will be serialized to JSON (unless it's already a Tool.Result)
 * @param name Tool name for LLM consumption
 * @param description Tool description for LLM consumption
 * @param inputType Class of the input type for JSON deserialization
 * @param outputType Class of the output type (used for documentation)
 * @param metadata Optional tool metadata
 * @param objectMapper ObjectMapper for JSON serialization/deserialization
 * @param function Function that processes typed input and returns typed output
 */
open class TypedTool<I : Any, O : Any>(
    name: String,
    description: String,
    protected val inputType: Class<I>,
    protected val outputType: Class<O>,
    override val metadata: Tool.Metadata = Tool.Metadata.DEFAULT,
    protected val objectMapper: ObjectMapper = jacksonObjectMapper(),
    private val function: Function<I, O>,
) : Tool {

    override val definition: Tool.Definition = Tool.Definition(
        name = name,
        description = description,
        inputSchema = Tool.InputSchema.of(inputType),
    )

    /**
     * Execute the tool with strongly typed input.
     * Calls the function provided at construction time.
     * Can be overridden in subclasses for custom behavior.
     *
     * @param input Deserialized input object
     * @return Output object (will be serialized to JSON)
     */
    open fun typedCall(input: I): O = function.apply(input)

    /**
     * Executes the tool by deserializing input JSON, calling [typedCall],
     * and serializing the result.
     */
    override fun call(input: String): Tool.Result {
        return try {
            val parsed = parseInput(input)
            val result = typedCall(parsed)
            convertResult(result)
        } catch (e: Exception) {
            // Allow control flow exceptions to propagate
            if (e is ToolControlFlowSignal) {
                throw e
            }
            val message = e.message ?: "Tool invocation failed"
            Tool.Result.error(message, e)
        }
    }

    /**
     * Parse the input JSON string to the input type.
     * Can be overridden for custom parsing logic.
     */
    protected open fun parseInput(input: String): I {
        return if (input.isBlank()) {
            // Try to create instance with no-arg constructor for empty input
            try {
                inputType.getDeclaredConstructor().newInstance()
            } catch (e: Exception) {
                throw IllegalArgumentException("Empty input provided but ${inputType.simpleName} has no no-arg constructor")
            }
        } else {
            objectMapper.readValue(input, inputType)
        }
    }

    /**
     * Convert the output to a Tool.Result.
     * Can be overridden for custom result handling.
     */
    protected open fun convertResult(result: O): Tool.Result {
        return when (result) {
            is Tool.Result -> result
            is String -> Tool.Result.text(result)
            else -> Tool.Result.text(objectMapper.writeValueAsString(result))
        }
    }
}
