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
package com.embabel.agent.web.rest

import com.embabel.agent.core.*
import com.embabel.common.ai.model.ModelMetadata
import com.embabel.common.ai.model.ModelProvider
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


/**
 * Provides endpoints to retrieve general platform information, including agents, goals, actions, and conditions.
 */
@RestController
@RequestMapping("/api/v1/platform-info")
@Tag(
    name = "Platform Information",
    description = "Endpoints for retrieving platform, agents, goals, actions, and conditions information."
)
class PlatformInfoController(
    private val agentPlatform: AgentPlatform,
    private val modelProvider: ModelProvider,
) {
    /**
     * Returns a list of all agents deployed on the platform.
     *
     * @return List of agents with their details
     */
    @GetMapping("/agents")
    @Operation(
        summary = "Get all agents",
        description = "Returns a list of all agents deployed on the platform."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "List of agents returned successfully")
        ]
    )
    fun getAgents(): List<AgentMetadata> = agentPlatform.agents().map { AgentMetadata(it) }.sortedBy { it.name }

    /**
     * Returns a list of all goals known to the platform (across all agents).
     *
     * @return List of goals
     */
    @GetMapping("/goals")
    @Operation(
        summary = "Get all goals",
        description = "Returns a list of all goals known to the platform (across all agents)."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "List of goals returned successfully")
        ]
    )
    fun getGoals(): Set<Goal> = agentPlatform.goals

    /**
     * Returns a list of all actions available on the platform (across all agents).
     *
     * @return List of actions
     */
    @GetMapping("/actions")
    @Operation(
        summary = "Get all actions",
        description = "Returns a list of all actions available on the platform (across all agents)."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "List of actions returned successfully")
        ]
    )
    fun getActions(): List<ActionMetadata> = agentPlatform.actions.map { ActionMetadata(it) }.sortedBy { it.name }

    /**
     * Returns general platform information, including the number of agents, actions, goals, and conditions.
     *
     * @return Platform information summary
     */
    @GetMapping("")
    @Operation(
        summary = "Get platform information",
        description = "Returns general platform information, including the number of agents, actions, goals, and conditions."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Platform information returned successfully")
        ]
    )
    fun getPlatformInfo(): PlatformInfoSummary = PlatformInfoSummary(
        agentCount = agentPlatform.agents().size,
        agentNames = agentPlatform.agents().map { it.name }.toSet(),
        actionCount = agentPlatform.actions.size,
        goalCount = agentPlatform.goals.size,
        conditionCount = agentPlatform.conditions.size,
        name = agentPlatform.name,
        domainTypes = agentPlatform.domainTypes.map { it.name }.toSet(),
        models = modelProvider.listModels().sortedBy { it.name },
        toolGroups = agentPlatform.toolGroupResolver.availableToolGroups(),
    )

    /**
     * Returns a list of all conditions available on the platform (across all agents).
     *
     * @return List of conditions
     */
    @GetMapping("/conditions")
    @Operation(
        summary = "Get all conditions",
        description = "Returns a list of all conditions available on the platform (across all agents)."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "List of conditions returned successfully")
        ]
    )
    fun getConditions(): Set<ConditionMetadata> =
        agentPlatform.conditions.map { ConditionMetadata(it.name, it.cost) }.toSet()

    /**
     * Returns a list of all models available on the platform (across all agents).
     *
     * @return List of conditions
     */
    @GetMapping("/models")
    @Operation(
        summary = "Get all models",
        description = "Returns a list of all models available on the platform (across all agents)."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "List of conditions returned successfully")
        ]
    )
    fun getModels(): List<ModelMetadata> = modelProvider.listModels().sortedBy { it.name }

    @GetMapping("/tool-groups")
    @Operation(
        summary = "Get all tools",
        description = "Returns a list of all tool groups available on the platform (across all agents)."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "List of conditions returned successfully")
        ]
    )
    fun getToolGroups(): List<ToolGroupMetadata> = agentPlatform.toolGroupResolver.availableToolGroups()

}

/**
 * DTO for platform information summary.
 */
data class PlatformInfoSummary(
    val agentCount: Int,
    val agentNames: Set<String>,
    val actionCount: Int,
    val goalCount: Int,
    val conditionCount: Int,
    val name: String,
    val domainTypes: Set<String>,
    val models: List<ModelMetadata>,
    val toolGroups: List<ToolGroupMetadata>,
)
