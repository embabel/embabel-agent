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

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.AgentProcessStatusReport
import com.embabel.agent.core.OperationStatus
import com.embabel.common.core.types.Timed
import com.embabel.common.core.types.Timestamped
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant

/**
 * Return status of the process and URLs for status and SSE streaming.
 * @param id Unique identifier of the agent process.
 * @param status Current status of the agent process.
 * @param result The last result of the agent process, if available.
 * @param statusUrl URL to check the status of the process.
 * @param sseUrl URL to request Server-Sent Events (SSE) streaming of the process status.
 */
data class AgentProcessStatus(
    val id: String,
    override val status: AgentProcessStatusCode,
    override val timestamp: Instant,
    override val runningTime: Duration,
    val result: Any?,
    val statusUrl: String = "/api/v1/process/$id",
    val sseUrl: String = "/events/process/$id/status",
) : Timestamped, Timed, OperationStatus<AgentProcessStatusCode>

@RestController
@RequestMapping("/api/v1/process")
@Tag(
    name = "AgentProcess information and control",
    description = "Endpoints for retrieving AgentProcess information, including status and results."
)
class AgentProcessController(
    private val agentPlatform: AgentPlatform,
) {

    private val logger = LoggerFactory.getLogger(AgentProcessController::class.java)

    @Operation(
        summary = "Get the status of a process",
        description = "Returns the status of the process with the given ID, including its current status, running time, and last result.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Process status returned"),
            ApiResponse(responseCode = "404", description = "Process not found")
        ]
    )
    @GetMapping("/{processId}")
    fun checkProcessStatus(@PathVariable processId: String): AgentProcessStatus {
        val agentProcess = agentPlatform.getAgentProcess(processId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Process not found")

        return AgentProcessStatus(
            id = agentProcess.id,
            status = agentProcess.status,
            timestamp = agentProcess.timestamp,
            runningTime = agentProcess.runningTime,
            result = agentProcess.lastResult(),
        )
    }

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
