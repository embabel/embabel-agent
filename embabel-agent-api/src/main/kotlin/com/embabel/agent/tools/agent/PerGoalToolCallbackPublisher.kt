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

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.ToolCallbackPublisher
import com.embabel.agent.core.Verbosity
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.core.hitl.ConfirmationResponse
import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.agent.core.hitl.ResponseImpact
import com.embabel.agent.spi.LlmInteraction
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.util.json.schema.JsonSchemaGenerator

const val CONFIRMATION_TOOL_NAME = "_confirm"

const val FORM_SUBMISSION_TOOL_NAME = "submitFormAndResumeProcess"


/**
 * Communicator for awaiting user input.
 */
interface TextCommunicator {

    /**
     * Produce a response string for the given goal and ProcessWaitingException.
     */
    fun communicateAwaitable(
        goal: Goal,
        pwe: ProcessWaitingException,
    ): String

    /**
     * Communicate the result of an agent process execution.
     */
    fun communicateResult(
        agentProcessExecution: AgentProcessExecution,
    ): String

}

/**
 * Generic tool callback provider that publishes a tool callback for each goal.
 * Multiple instances of this class can be created, each with different configuration,
 * for different purposes.
 * Tools can be exposed to actions or via an MCP server etc.
 * Return a tool callback for each goal taking user input.
 * If the goal specifies startingInputTypes,
 * add a tool for each of those input types.
 * Add a continue tool for any process that requires user input
 * and is waiting for a form submission.
 */
class PerGoalToolCallbackPublisher(
    private val autonomy: Autonomy,
    private val objectMapper: ObjectMapper,
    applicationName: String,
    private val textCommunicator: TextCommunicator = PromptedTextCommunicator,
    private val goalToolNamingStrategy: GoalToolNamingStrategy = ApplicationNameGoalToolNamingStrategy(
        applicationName
    ),
) : ToolCallbackPublisher {

    private val logger = LoggerFactory.getLogger(PerGoalToolCallbackPublisher::class.java)

    /**
     * Generic tools
     */
    val platformTools: List<ToolCallback> = ToolCallbacks.from(this).toList()

    /**
     * Return all tool callbacks for the agent platform.
     */
    override val toolCallbacks: List<ToolCallback>
        get() = toolCallbacks(remoteOnly = false)

    /**
     * Tools associated with goals.
     */
    fun goalTools(remoteOnly: Boolean): List<GoalToolCallback<*>> {
        val goalTools = autonomy.agentPlatform.goals
            .filter { it.export.local }
            .filter { !remoteOnly || it.export.remote }
            .flatMap { goal ->
                toolsForGoal(goal)
            }
        if (goalTools.isEmpty()) {
            logger.info("No goals found in agent platform, no tool callbacks will be published")
            return emptyList()
        }
        logger.info("{} goal tools found in agent platform: {}", goalTools.size, goalTools)
        return goalTools
    }

    /**
     * If remote is true, include only remote tools.
     */
    fun toolCallbacks(remoteOnly: Boolean): List<ToolCallback> {
        val goalTools = goalTools(remoteOnly)
        val allTools = goalTools + platformTools
        assert(allTools.size == goalTools.size + platformTools.size)
        return allTools
    }

    @Tool(
        name = FORM_SUBMISSION_TOOL_NAME,
        description = "Resume a process by providing the process ID and form content",
    )
    fun submitFormAndResumeProcess(
        processId: String,
        formData: String,
    ): String {
        logger.info("Form submission tool called with processId: {}, form input: {}", processId, formData)
        val agentProcess = autonomy.agentPlatform.getAgentProcess(processId)
            ?: return "No process found with ID $processId"
        val formBindingRequest = agentProcess.lastResult() as? FormBindingRequest<Any>
            ?: return "No form binding request found for process $processId"
        val prompt = """
            Given the content below, return the given object

            # Content
            $formData
        """.trimIndent()
        val formDataObject = autonomy.agentPlatform.platformServices.llmOperations.doTransform(
            prompt = prompt,
            LlmInteraction.using(LlmOptions(criteria = ModelSelectionCriteria.Auto)),
            outputClass = formBindingRequest.outputClass,
            null,
        )
        val responseImpact = formBindingRequest.bind(
            boundInstance = formDataObject,
            agentProcess
        )
        if (responseImpact != ResponseImpact.UPDATED) {
            TODO("Handle unchanged response impact")
        }
        // Resume the agent process with the form data
        agentProcess.run()
        val ape = AgentProcessExecution.fromProcessStatus(formData, agentProcess)
        return textCommunicator.communicateResult(ape)
    }

    @Tool(
        name = CONFIRMATION_TOOL_NAME,
        description = "Resume a process by providing the process ID and form content",
    )
    fun confirmation(
        processId: String,
        confirmed: Boolean,
    ): String {
        logger.info("Confirmation tool called with processId: {}, confirmed: {}", processId, confirmed)
        val agentProcess = autonomy.agentPlatform.getAgentProcess(processId)
            ?: return "No process found with ID $processId"
        val confirmationRequest = agentProcess.lastResult() as? ConfirmationRequest<Any>
            ?: return "No confirmation binding request found for process $processId"
        val confirmationResponse = ConfirmationResponse(
            awaitableId = confirmationRequest.id,
            accepted = confirmed,
        )
        if (confirmationResponse.accepted) {
            agentProcess += confirmationRequest.payload
        } else {
            logger.info("Confirmation request rejected: {}", confirmationRequest.payload)
            // If the confirmation is rejected, we do not update the agent process
            return "Confirmation request rejected: ${confirmationRequest.payload}"
        }
        // Resume the agent process with the form data
        agentProcess.run()
        val ape = AgentProcessExecution.fromProcessStatus(confirmationRequest.payload, agentProcess)
        return textCommunicator.communicateResult(ape)
    }

    /**
     * Create tool callbacks for the given goal.
     * There will be one tool callback for each starting input type of the goal.
     */
    fun toolsForGoal(goal: Goal): List<GoalToolCallback<*>> {
        val goalName = goal.export.name ?: goalToolNamingStrategy.nameForGoal(goal)
        return goal.export.startingInputTypes.map { inputType ->
            GoalToolCallback(
                name = "${inputType.simpleName}_$goalName",
                description = goal.description,
                goal = goal,
                inputType = inputType,
            )
        }
    }

    /**
     * Spring AI ToolCallback implementation for a specific goal.
     */
    inner class GoalToolCallback<I : Any>(
        val name: String,
        val description: String,
        val goal: Goal,
        val inputType: Class<I>,
    ) : ToolCallback {

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
            val processOptions = ProcessOptions(verbosity = verbosity)
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
}

private data class TypeWrappingToolDefinition(
    private val name: String,
    private val description: String,
    private val type: Class<*>,
) : ToolDefinition {

    override fun name(): String = name
    override fun description(): String = description

    override fun inputSchema(): String = JsonSchemaGenerator.generateForType(type)
}
