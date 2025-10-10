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
package com.embabel.agent.mcpserver.async.support

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.core.Goal
import com.embabel.agent.mcpserver.async.McpAsyncPromptFactory
import com.embabel.agent.mcpserver.async.McpAsyncPromptPublisher
import com.embabel.agent.mcpserver.sync.McpPromptPublisher
import com.embabel.agent.mcpserver.sync.McpPromptFactory
import com.embabel.common.util.indent
import io.modelcontextprotocol.server.McpServerFeatures
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Publish MCP prompts for each goal's starting input types, if specified.
 * This allows the MCP server to provide prompts based on the specific input types required by each goal.
 */
@Service
@ConditionalOnProperty(
    value = ["embabel.agent.mcpserver.type"],
    havingValue = "ASYNC",
    matchIfMissing = true,
)
class PerGoalAsyncMcpStartingInputTypesPromptPublisher(
    private val autonomy: Autonomy,
) : McpAsyncPromptPublisher {

    override fun prompts(): List<McpServerFeatures.AsyncPromptSpecification> {
        return autonomy.agentPlatform.goals.flatMap { goal ->
            promptsForGoal(goal)
        }
    }

    fun promptsForGoal(goal: Goal): List<McpServerFeatures.AsyncPromptSpecification> {
        val mcpPromptFactory = McpAsyncPromptFactory()
        return goal.export.startingInputTypes.map { inputType ->
            mcpPromptFactory.asyncPromptSpecificationForType(
                goal = goal,
                inputType,
            )
        }
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "${javaClass.simpleName}(prompts=${prompts().size})".indent(indent)

}
