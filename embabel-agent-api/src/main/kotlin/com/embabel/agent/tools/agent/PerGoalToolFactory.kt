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

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ToolNamingStrategy
import com.embabel.agent.core.support.distinctByNameReportingCollisions
import com.embabel.agent.core.support.sameTool
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
    private val toolNamingStrategy: ToolNamingStrategy = ToolNamingStrategy.LEGACY,
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
        val goalTools = goalsToPublish()
            .filter { it.goal.export.local }
            .filter { !remoteOnly || it.goal.export.remote }
            .flatMap { source ->
                toolsForGoal(source.goal, listeners, source.ownerHierarchy)
            }
        if (goalTools.isEmpty()) {
            logger.info("No goals found in agent platform, no tools will be published")
            return emptyList()
        }
        val distinctGoalTools = goalTools
            .distinctByNameReportingCollisions(
                kind = "goal tool",
                sameValue = { a, b -> a.goal == b.goal && a.inputType == b.inputType },
                context = "agent platform",
                describe = { "${it.goal.name} (${it.goal.description})" },
            ) { it.definition.name }
        logger.info("{} goal tools found in agent platform: {}", distinctGoalTools.size, distinctGoalTools)
        return distinctGoalTools
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
            (goalTools + platformTools)
                .distinctByNameReportingCollisions(
                    kind = "published tool",
                    sameValue = ::sameTool,
                    context = "agent platform",
                    describe = { "${it.definition.name} (${it::class.qualifiedName})" },
                ) { it.definition.name }
                .sortedBy { it.definition.name }
        }
    }


    /**
     * Create tools for the given goal.
     * There will be one tool for each starting input type of the goal.
     */
    fun toolsForGoal(
        goal: Goal,
        listeners: List<AgenticEventListener>,
        ownerHierarchy: String? = null,
    ): List<GoalTool<*>> {
        val goalName = goal.export.name ?: when (toolNamingStrategy) {
            ToolNamingStrategy.LEGACY -> goalToolNamingStrategy.nameForGoal(goal)
            ToolNamingStrategy.FULL_HIERARCHY -> toolNamingStrategy.nameFor(ownerHierarchy, goal.name)
        }
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

    private fun goalsToPublish(): List<GoalSource> = if (toolNamingStrategy == ToolNamingStrategy.FULL_HIERARCHY) {
        autonomy.agentPlatform.agents().flatMap { agent ->
            agent.goals.map { goal -> GoalSource(agent.name, goal) }
        }
    } else {
        autonomy.agentPlatform.goals.map { goal -> GoalSource(null, goal) }
    }

    private data class GoalSource(
        val ownerHierarchy: String?,
        val goal: Goal,
    )

}
