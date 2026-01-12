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
package com.embabel.agent.tools.agent

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.TypeBasedInputSchema
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import org.slf4j.LoggerFactory

/**
 * Framework-agnostic Tool implementation for a specific goal.
 */
data class GoalTool<I : Any>(
    val autonomy: Autonomy,
    val textCommunicator: TextCommunicator,
    val name: String,
    val description: String = goal.description,
    val goal: Goal,
    val inputType: Class<I>,
    val listeners: List<AgenticEventListener> = emptyList(),
) : Tool {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val objectMapper = autonomy.agentPlatform.platformServices.objectMapper

    fun withListener(listener: AgenticEventListener) = copy(
        listeners = listeners + listener,
    )

    override val definition: Tool.Definition = object : Tool.Definition {
        override val name: String = this@GoalTool.name
        override val description: String = this@GoalTool.description
        override val inputSchema: Tool.InputSchema = TypeBasedInputSchema.of(inputType)
    }

    override val metadata: Tool.Metadata = Tool.Metadata.DEFAULT

    override fun call(input: String): Tool.Result {
        logger.info("Calling tool {} with input {}", this.name, input)
        val verbosity = Verbosity(
            showPrompts = true,
        )
        val inputObject = try {
            val o = objectMapper.readValue(input, inputType)
            logger.info("Successfully parsed tool input to an instance of {}:\n{}", o::class.java.name, o)
            o
        } catch (e: Exception) {
            val errorMessage =
                "BAD INPUT ERROR parsing tool input: ${e.message}: Try again and see if you can get the format right"
            logger.warn("Error $errorMessage parsing tool input: $input", e)
            return Tool.Result.error(errorMessage, e)
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
        return try {
            val agentProcessExecution = autonomy.runAgent(
                inputObject = inputObject,
                processOptions = processOptions,
                agent = agent,
            )
            logger.info("Goal response: {}", agentProcessExecution)
            Tool.Result.text(textCommunicator.communicateResult(agentProcessExecution))
        } catch (pwe: ProcessWaitingException) {
            val response = textCommunicator.communicateAwaitable(goal, pwe)
            logger.info("Returning waiting response:\n$response")
            Tool.Result.text(response)
        }
    }

    override fun toString() =
        "${javaClass.simpleName}(goal=${goal.name}, description=${goal.description})"
}
