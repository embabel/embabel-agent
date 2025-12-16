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
package com.embabel.agent.test.integration

import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.LlmOperations
import com.embabel.chat.Message
import org.slf4j.LoggerFactory

/**
 * A scripted LLM operations implementation for testing.
 *
 * This allows tests to script exact sequences of:
 * - Tool calls (with specific arguments)
 * - Text responses
 * - Object creation
 *
 * The scripted operations track what was called for verification.
 */
class ScriptedLlmOperations : LlmOperations {

    private val logger = LoggerFactory.getLogger(ScriptedLlmOperations::class.java)

    /**
     * Represents a scripted action to take.
     */
    sealed class ScriptedAction {
        /**
         * Call a tool with the given name and input JSON.
         */
        data class CallTool(val toolName: String, val inputJson: String = "{}") : ScriptedAction()

        /**
         * Return a text response without calling tools.
         */
        data class Respond(val text: String) : ScriptedAction()

        /**
         * Return an object (for createObject calls).
         */
        data class ReturnObject<T>(val obj: T) : ScriptedAction()
    }

    private val scriptedActions = mutableListOf<ScriptedAction>()
    private var actionIndex = 0

    // Tracking for test verification
    private val _toolCallsMade = mutableListOf<ToolCallRecord>()
    val toolCallsMade: List<ToolCallRecord> get() = _toolCallsMade.toList()

    private val _promptsReceived = mutableListOf<String>()
    val promptsReceived: List<String> get() = _promptsReceived.toList()

    data class ToolCallRecord(
        val toolName: String,
        val input: String,
        val result: String,
    )

    /**
     * Add a scripted action to the sequence.
     */
    fun script(action: ScriptedAction): ScriptedLlmOperations {
        scriptedActions.add(action)
        return this
    }

    /**
     * Script a tool call.
     */
    fun callTool(toolName: String, inputJson: String = "{}"): ScriptedLlmOperations =
        script(ScriptedAction.CallTool(toolName, inputJson))

    /**
     * Script a text response.
     */
    fun respond(text: String): ScriptedLlmOperations =
        script(ScriptedAction.Respond(text))

    /**
     * Script returning an object.
     */
    fun <T> returnObject(obj: T): ScriptedLlmOperations =
        script(ScriptedAction.ReturnObject(obj))

    /**
     * Reset the script to start from the beginning.
     */
    fun reset() {
        actionIndex = 0
        _toolCallsMade.clear()
        _promptsReceived.clear()
    }

    private fun nextAction(): ScriptedAction? {
        return if (actionIndex < scriptedActions.size) {
            scriptedActions[actionIndex++]
        } else {
            null
        }
    }

    override fun generate(
        prompt: String,
        interaction: LlmInteraction,
        agentProcess: AgentProcess,
        action: Action?,
    ): String {
        logger.info("ScriptedLlmOperations.generate called")
        _promptsReceived.add(prompt)

        // Process scripted actions, calling tools as needed
        while (true) {
            val nextAction = nextAction()
                ?: return "No more scripted actions"

            when (nextAction) {
                is ScriptedAction.CallTool -> {
                    // Find and call the tool
                    val tool = interaction.toolCallbacks.find {
                        it.toolDefinition.name() == nextAction.toolName
                    }
                    if (tool != null) {
                        logger.info(
                            "Scripted: Calling tool '{}' with input: {}",
                            nextAction.toolName,
                            nextAction.inputJson
                        )
                        val result = tool.call(nextAction.inputJson)
                        logger.info("Tool '{}' returned: {}", nextAction.toolName, result)
                        _toolCallsMade.add(
                            ToolCallRecord(
                                toolName = nextAction.toolName,
                                input = nextAction.inputJson,
                                result = result,
                            )
                        )
                        // Continue to next action (may call more tools or respond)
                    } else {
                        logger.warn(
                            "Scripted tool '{}' not found. Available: {}",
                            nextAction.toolName,
                            interaction.toolCallbacks.map { it.toolDefinition.name() }
                        )
                        return "ERROR: Tool '${nextAction.toolName}' not found"
                    }
                }

                is ScriptedAction.Respond -> {
                    logger.info("Scripted: Responding with: {}", nextAction.text)
                    return nextAction.text
                }

                is ScriptedAction.ReturnObject<*> -> {
                    // For generate(), just convert to string
                    return nextAction.obj.toString()
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <O> createObject(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): O {
        logger.info("ScriptedLlmOperations.createObject called for class: {}", outputClass.name)

        // Record the prompt
        val prompt = messages.joinToString("\n") { it.content }
        _promptsReceived.add(prompt)

        // Process scripted actions
        while (true) {
            val nextAction = nextAction()
                ?: throw IllegalStateException("No more scripted actions for createObject")

            when (nextAction) {
                is ScriptedAction.CallTool -> {
                    val tool = interaction.toolCallbacks.find {
                        it.toolDefinition.name() == nextAction.toolName
                    }
                    if (tool != null) {
                        logger.info(
                            "Scripted: Calling tool '{}' with input: {}",
                            nextAction.toolName,
                            nextAction.inputJson
                        )
                        val result = tool.call(nextAction.inputJson)
                        _toolCallsMade.add(
                            ToolCallRecord(
                                toolName = nextAction.toolName,
                                input = nextAction.inputJson,
                                result = result,
                            )
                        )
                    } else {
                        throw IllegalStateException("Tool '${nextAction.toolName}' not found")
                    }
                }

                is ScriptedAction.Respond -> {
                    // Try to convert text response to the output class
                    throw IllegalStateException(
                        "Cannot use Respond for createObject - use ReturnObject instead"
                    )
                }

                is ScriptedAction.ReturnObject<*> -> {
                    if (outputClass.isInstance(nextAction.obj)) {
                        return nextAction.obj as O
                    }
                    throw IllegalStateException(
                        "Scripted object ${nextAction.obj} is not of type ${outputClass.name}"
                    )
                }
            }
        }
    }

    override fun <O> createObjectIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Result<O> {
        return try {
            Result.success(createObject(messages, interaction, outputClass, agentProcess, action))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun <O> doTransform(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O {
        val event = llmRequestEvent
            ?: throw IllegalStateException("No llmRequestEvent")
        return createObject(
            messages = messages,
            interaction = interaction,
            outputClass = outputClass,
            agentProcess = event.agentProcess,
            action = null,
        )
    }
}
