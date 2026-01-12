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

import com.embabel.agent.api.common.ToolObject
import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Goal
import com.embabel.agent.core.support.safelyGetToolsFrom
import com.embabel.common.core.types.NamedAndDescribed
import org.slf4j.LoggerFactory

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
        goal: NamedAndDescribed,
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
 * Factory that creates tools for each goal in the agent platform.
 * Each invocation will result in a distinct AgentProcess being executed.
 * Multiple instances of this class can be created, each with different configuration,
 * for different purposes.
 * Tools can be exposed to actions or via an MCP server etc.
 * Return a tool for each goal taking user input.
 * If the goal specifies startingInputTypes,
 * add a tool for each of those input types.
 * Add a continue tool for any process that requires user input
 * and is waiting for a form submission.
 */
class PerGoalToolFactory(
    private val autonomy: Autonomy,
    applicationName: String,
    private val textCommunicator: TextCommunicator = PromptedTextCommunicator,
    private val goalToolNamingStrategy: GoalToolNamingStrategy = ApplicationNameGoalToolNamingStrategy(
        applicationName
    ),
) {

    private val logger = LoggerFactory.getLogger(PerGoalToolFactory::class.java)

    /**
     * Generic platform tools
     */
    val platformTools: List<Tool> = safelyGetToolsFrom(
        ToolObject(
            DefaultProcessCallbackTools(
                autonomy = autonomy,
                textCommunicator = textCommunicator,
            )
        )
    )


    /**
     * Tools associated with goals.
     * @param remoteOnly if true, only include tools that are remote.
     * @param listeners additional listeners to be notified of events relating to the created process
     */
    fun goalTools(
        remoteOnly: Boolean,
        listeners: List<AgenticEventListener>,
    ): List<GoalTool<*>> {
        val goalTools = autonomy.agentPlatform.goals
            .filter { it.export.local }
            .filter { !remoteOnly || it.export.remote }
            .flatMap { goal ->
                toolsForGoal(goal, listeners)
            }
        if (goalTools.isEmpty()) {
            logger.info("No goals found in agent platform, no tools will be published")
            return emptyList()
        }
        logger.info("{} goal tools found in agent platform: {}", goalTools.size, goalTools)
        return goalTools
    }

    /**
     * All tools including goal tools and platform tools.
     * @param remoteOnly if true, only include tools that are remote.
     * @param listeners additional listeners to be notified of events relating to the created process
     */
    fun allTools(
        remoteOnly: Boolean,
        listeners: List<AgenticEventListener>,
    ): List<Tool> {
        val goalTools = goalTools(remoteOnly, listeners)
        return if (goalTools.isEmpty()) {
            logger.warn("No goal tools found, no tools will be published")
            emptyList()
        } else {
            goalTools + platformTools
        }
    }


    /**
     * Create tools for the given goal.
     * There will be one tool for each starting input type of the goal.
     */
    fun toolsForGoal(
        goal: Goal,
        listeners: List<AgenticEventListener>,
    ): List<GoalTool<*>> {
        val goalName = goal.export.name ?: goalToolNamingStrategy.nameForGoal(goal)
        return goal.export.startingInputTypes.map { inputType ->
            GoalTool(
                autonomy = autonomy,
                name = goalName,
                description = goal.description,
                goal = goal,
                inputType = inputType,
                listeners = listeners,
                textCommunicator = textCommunicator,
            )
        }
    }

}
