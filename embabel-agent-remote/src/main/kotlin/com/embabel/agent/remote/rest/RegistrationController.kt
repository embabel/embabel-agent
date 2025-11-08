package com.embabel.agent.remote.rest

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.remote.action.RestAction
import com.embabel.agent.remote.action.RestActionMetadata
import com.embabel.agent.remote.action.RestServer
import com.embabel.agent.remote.action.RestServerRegistration
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
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
class RegistrationController(
    private val agentPlatform: AgentPlatform,
    private val restClient: RestClient,
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
        val restServer = RestServer(remoteServerRegistration, restClient)
        val agentScope = restServer.agentScope(agentPlatform)
        agentPlatform.deploy(agentScope)
    }
}