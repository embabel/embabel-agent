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
package com.embabel.agent.web.rest

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessStatusReport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException


/**
 * Provides endpoints to retrieve general platform information, including agents, goals, actions, and conditions.
 */
@RestController
@RequestMapping("/api/v1/process")
@Tag(
    name = "Process status",
    description = "Endpoints for retrieving process status and killing processes."
)
class AgentProcessController(
    private val agentPlatform: AgentPlatform,
) {

    @GetMapping("/{id}")
    @Operation(
        summary = "Get process status",
        description = "Returns the status of a process."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Process status returned successfully"),
            ApiResponse(responseCode = "404", description = "Process not found")
        ]
    )
    fun getStatusReport(@PathVariable id: String): AgentProcessStatusReport =
        agentPlatform.getAgentProcess(id)?.statusReport() ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No process found with id: $id",
        )


    @DeleteMapping("/{id}")
    @Operation(
        summary = "Kill the given process",
        description = "Returns the status of a process if it could be killed"
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Process status killed successfully"),
            ApiResponse(responseCode = "404", description = "Process not found")
        ]
    )
    fun killAgentProcess(@PathVariable id: String): AgentProcessStatusReport {
        val killedProcess = agentPlatform.killAgentProcess(id)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No process found with id: $id",
            )
        return killedProcess.statusReport()
    }
}
