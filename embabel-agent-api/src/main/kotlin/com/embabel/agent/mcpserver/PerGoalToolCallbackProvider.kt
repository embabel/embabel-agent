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
package com.embabel.agent.mcpserver

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.loggerFor
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.util.json.schema.JsonSchemaGenerator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * Return a tool callback for each goal.
 */
@Service
@Profile("!test")
class PerGoalToolCallbackProvider(
    private val autonomy: Autonomy,
    private val objectMapper: ObjectMapper,
) : McpToolExportCallbackPublisher {

    private val logger = LoggerFactory.getLogger(PerGoalToolCallbackProvider::class.java)

    override val toolCallbacks: List<ToolCallback>
        get() {
            return autonomy.agentPlatform.goals.map { goal ->
                toolForGoal(goal)
            }
        }

    fun toolForGoal(goal: Goal): ToolCallback {
        class GoalToolCallback : ToolCallback {
            override fun getToolDefinition(): ToolDefinition {
                return object : ToolDefinition {
                    override fun name(): String {
                        val parts: List<String> = goal.name.split(".")
                        return parts.takeLast(2).joinToString("_")
                    }

                    override fun description(): String {
                        return goal.description
                    }

                    override fun inputSchema(): String {
                        val js = JsonSchemaGenerator.generateForType(UserInput::class.java)
                        loggerFor<PerGoalToolCallbackProvider>().debug("Generated schema for ${goal.name}: $js")
                        return js
                    }
                }
            }

            override fun call(
                toolInput: String,
                tooContext: ToolContext?
            ): String {
                return call(toolInput)
            }

            override fun call(toolInput: String): String {
                val verbosity = Verbosity(
                    showPrompts = true,
                )
                val userInput = objectMapper.readValue(toolInput, UserInput::class.java)
                val processOptions = ProcessOptions(verbosity = verbosity)
                val agent = autonomy.createGoalAgent(
                    userInput = userInput,
                    goal = goal,
                    agentScope = autonomy.agentPlatform,
                )
                val dynamicExecutionResult = autonomy.runAgent(
                    userInput = UserInput(toolInput),
                    processOptions = processOptions,
                    agent = agent,
                )
                logger.info("Goal response: {}", dynamicExecutionResult)

                return when (val output = dynamicExecutionResult.output) {
                    is String -> output
                    is HasInfoString -> dynamicExecutionResult.output.infoString(verbose = true)
                    is HasContent -> output.content
                    else -> output.toString()
                }
            }
        }
        return GoalToolCallback()

    }
}
