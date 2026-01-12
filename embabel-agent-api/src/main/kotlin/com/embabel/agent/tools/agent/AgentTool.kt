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
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.TypeBasedInputSchema
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Tool that can be used to execute an agent.
 * Supports "Subagent" or "handoff" style usage.
 *
 * This is the framework-agnostic version that implements Embabel's [Tool] interface.
 */
data class AgentTool<I : Any>(
    private val autonomy: Autonomy,
    val agent: Agent,
    val textCommunicator: TextCommunicator,
    val objectMapper: ObjectMapper,
    val inputType: Class<I>,
    val processOptionsCreator: (
        parentAgentProcess: AgentProcess,
    ) -> ProcessOptions,
) : Tool {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val definition: Tool.Definition = object : Tool.Definition {
        override val name: String = agent.name
        override val description: String = agent.description
        override val inputSchema: Tool.InputSchema = TypeBasedInputSchema.of(inputType)
    }

    override val metadata: Tool.Metadata = Tool.Metadata.DEFAULT

    override fun call(input: String): Tool.Result {
        val parentAgentProcess = AgentProcess.get()
        logger.info("Calling tool {} with input {}", this.agent.name, input)

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

        val processOptions = parentAgentProcess?.let {
            logger.info("Found parent agent process: {} and creating ProcessOptions based on it", it)
            processOptionsCreator(parentAgentProcess)
        } ?: run {
            logger.warn(
                "No parent agent process found in tool context, using default process options."
            )
            ProcessOptions(
                verbosity = Verbosity(showPrompts = true),
            )
        }

        return try {
            val agentProcessExecution = autonomy.runAgent(
                inputObject = inputObject,
                processOptions = processOptions,
                agent = agent,
            )
            logger.info("Agent response: {}", agentProcessExecution)
            Tool.Result.text(textCommunicator.communicateResult(agentProcessExecution))
        } catch (pwe: ProcessWaitingException) {
            val response = textCommunicator.communicateAwaitable(agent, pwe)
            logger.info("Returning waiting response:\n$response")
            Tool.Result.text(response)
        }
    }

    override fun toString() =
        "${javaClass.simpleName}(agent=${agent.name}, description=${agent.description})"
}
