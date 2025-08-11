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
package com.embabel.agent.tools.agent

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import com.embabel.agent.event.AgenticEventListener
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Spring AI ToolCallback implementation for a specific goal.
 */
data class GoalToolCallback<I : Any>(
    val autonomy: Autonomy,
    val textCommunicator: TextCommunicator,
    val objectMapper: ObjectMapper,
    val name: String,
    val description: String = goal.description,
    val goal: Goal,
    val inputType: Class<I>,
    val listeners: List<AgenticEventListener> = emptyList(),
) : ToolCallback {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun withListener(listener: AgenticEventListener) = copy(
        listeners = listeners + listener,
    )

    override fun getToolDefinition(): ToolDefinition {
        return TypeWrappingToolDefinition(
            name = name,
            description = description,
            type = inputType,
        )
    }

    override fun call(
        toolInput: String,
    ): String {
        return call(toolInput, null)
    }

    override fun call(
        toolInput: String,
        toolContext: ToolContext?,
    ): String {
        logger.info("Calling tool {} with input {}", this.name, toolInput)
        val verbosity = Verbosity(
            showPrompts = true,
        )
        val inputObject = try {
            val o = objectMapper.readValue(toolInput, inputType)
            logger.info("Successfully parsed tool input to an instance of {}:\n{}", o::class.java.name, o)
            o
        } catch (e: Exception) {
            val errorReturn =
                "BAD INPUT ERROR parsing tool input: ${e.message}: Try again and see if you can get the format right"
            logger.warn("Error $errorReturn parsing tool input: $toolInput", e)
            return errorReturn
        }
        val processOptions = ProcessOptions(
            verbosity = verbosity,
            listeners = listeners,
        )
        val agent = autonomy.createGoalAgent(
            inputObject = inputObject,
            goal = goal,
            agentScope = autonomy.agentPlatform,
            // TODO Bug workaround
            prune = false,
        )
        try {
            val agentProcessExecution = autonomy.runAgent(
                inputObject = inputObject,
                processOptions = processOptions,
                agent = agent,
            )
            logger.info("Goal response: {}", agentProcessExecution)
            return textCommunicator.communicateResult(agentProcessExecution)
        } catch (pwe: ProcessWaitingException) {
            val response = textCommunicator.communicateAwaitable(goal, pwe)
            logger.info("Returning waiting response:\n$response")
            return response
        }
    }

    override fun toString() =
        "${javaClass.simpleName}(goal=${goal.name}, description=${goal.description})"

}
