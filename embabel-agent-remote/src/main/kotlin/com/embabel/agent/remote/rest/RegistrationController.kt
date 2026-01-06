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
package com.embabel.agent.remote.rest

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.remote.action.RestAction
import com.embabel.agent.remote.action.RestActionMetadata
import com.embabel.agent.remote.action.RestServer
import com.embabel.agent.remote.action.RestServerRegistration
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.jetbrains.annotations.ApiStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient

@RestController
@RequestMapping("/api/v1/remote")
@Tag(
    name = "Remote action registration controller",
    description = "Endpoints for registering remote actions"
)
@ApiStatus.Experimental
class RegistrationController(
    private val agentPlatform: AgentPlatform,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
) {

    @Operation(
        summary = "List remote actions",
        description = "List remote actions registered on this server",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Remote actions listed"),
        ]
    )
    @GetMapping
    fun remoteActions(): List<RestActionMetadata> {
        return agentPlatform.actions
            .filterIsInstance<RestAction>()
            .map { it.spec }
    }

    @Operation(
        summary = "Register a remote server",
        description = "Register a remote server",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Remote actions listed"),
        ]
    )
    @PostMapping("register")
    fun register(
        remoteServerRegistration: RestServerRegistration,
    ) {
        val restServer = RestServer(remoteServerRegistration, restClient, objectMapper)
        val agentScope = restServer.agentScope(agentPlatform)
        agentPlatform.deploy(agentScope)
    }
}
