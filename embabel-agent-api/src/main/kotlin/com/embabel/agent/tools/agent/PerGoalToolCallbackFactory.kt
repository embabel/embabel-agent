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
import com.embabel.agent.core.Verbosity
import com.embabel.agent.event.AgenticEventListener
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
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
 * Each invocation will result in a distinct AgentProcess being executed.
 * Multiple instances of this class can be created, each with different configuration,
 * for different purposes.
 * Tools can be exposed to actions or via an MCP server etc.
 * Return a tool callback for each goal taking user input.
 * If the goal specifies startingInputTypes,
 * add a tool for each of those input types.
 * Add a continue tool for any process that requires user input
 * and is waiting for a form submission.
 */
class PerGoalToolCallbackFactory(
    private val autonomy: Autonomy,
    private val objectMapper: ObjectMapper,
    applicationName: String,
    private val textCommunicator: TextCommunicator = PromptedTextCommunicator,
    private val goalToolNamingStrategy: GoalToolNamingStrategy = ApplicationNameGoalToolNamingStrategy(
        applicationName
    ),
) {

    private val logger = LoggerFactory.getLogger(PerGoalToolCallbackFactory::class.java)

    /**
     * Generic tools
     */
    val platformTools: List<ToolCallback> = ToolCallbacks.from(
        DefaultProcessCallbackTools(
            autonomy = autonomy,
            textCommunicator = textCommunicator,
        )
    ).toList()


    /**
     * Tools associated with goals.
     * @param remoteOnly if true, only include tools that are remote.
     * @param listeners additional listeners to be notified of events relating to the created process
     */
    fun goalTools(
        remoteOnly: Boolean,
        listeners: List<AgenticEventListener>,
    ): List<GoalToolCallback<*>> {
        val goalTools = autonomy.agentPlatform.goals
            .filter { it.export.local }
            .filter { !remoteOnly || it.export.remote }
            .flatMap { goal ->
                toolsForGoal(goal, listeners)
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
    fun toolCallbacks(
        remoteOnly: Boolean,
        listeners: List<AgenticEventListener>,
    ): List<ToolCallback> {
        val goalTools = goalTools(remoteOnly, listeners)
        return if (goalTools.isEmpty()) {
            logger.warn("No goal tools found, no tool callbacks will be published")
            return emptyList()
        } else {
            goalTools + platformTools
        }
    }


    /**
     * Create tool callbacks for the given goal.
     * There will be one tool callback for each starting input type of the goal.
     */
    fun toolsForGoal(
        goal: Goal,
        listeners: List<AgenticEventListener>,
    ): List<GoalToolCallback<*>> {
        val goalName = goal.export.name ?: goalToolNamingStrategy.nameForGoal(goal)
        return goal.export.startingInputTypes.map { inputType ->
            GoalToolCallback(
                autonomy = autonomy,
                name = "${inputType.simpleName}_$goalName",
                description = goal.description,
                goal = goal,
                inputType = inputType,
                listeners = listeners,
                textCommunicator = textCommunicator,
                objectMapper = objectMapper,
            )
        }
    }

}

/**
 * Spring AI ToolCallback implementation for a specific goal.
 */
data class GoalToolCallback<I : Any>(
    val autonomy: Autonomy,
    val textCommunicator: TextCommunicator,
    val objectMapper: ObjectMapper,
    val name: String,
    val description: String,
    val goal: Goal,
    val inputType: Class<I>,
    val listeners: List<AgenticEventListener>,
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

private data class TypeWrappingToolDefinition(
    private val name: String,
    private val description: String,
    private val type: Class<*>,
) : ToolDefinition {

    override fun name(): String = name
    override fun description(): String = description

    override fun inputSchema(): String = JsonSchemaGenerator.generateForType(type)
}
