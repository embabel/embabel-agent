package com.embabel.agent.remote.action

import com.embabel.agent.core.*
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.web.client.RestClient
import org.springframework.web.client.body


/**
 * Remote server that exposes actions over HTTP and REST
 */
class RestServer(
    val registration: RestServerRegistration,
    private val restClient: RestClient,
) {

    /**
     * Invoke server to get actions
     */
    private fun actions(): List<RestActionMetadata> {
        // TODO make more springy?
        val json = restClient
            .get()
            .uri("${registration.baseUrl}/api/v1/actions")
            .retrieve()
            .body<String>()
        return jacksonObjectMapper().readValue(json, object : TypeReference<List<RestActionMetadata>>() {})
    }

    private fun serverTypes(): Collection<DynamicType> {
        val json = restClient
            .get()
            .uri("${registration.baseUrl}/api/v1/types")
            .retrieve()
            .body<String>()
        return jacksonObjectMapper().readValue(json, object : TypeReference<List<DynamicType>>() {})
    }

    /**
     * Create an AgentScope respecting the given AgentPlatform
     * Platform types will be preferred to server-referenced dynamic types,
     * allowing types to be JVM types
     */
    fun agentScope(agentPlatform: AgentPlatform): AgentScope {
        val domainTypes =
            canonicalizedTypes(
                serverTypes = serverTypes(),
                agentPlatform = agentPlatform,
            )
        val actions = actions().map {
            toAction(
                actionMetadata = it,
                domainTypes = domainTypes,
                objectMapper = agentPlatform.platformServices.objectMapper,
            )
        }
        return AgentScope(
            name = registration.name,
            description = registration.description,
            actions = actions,
            goals = setOf(),
            conditions = setOf(),
            opaque = false,
        )
    }

    private fun toAction(
        actionMetadata: RestActionMetadata,
        domainTypes: Collection<DomainType>,
        objectMapper: ObjectMapper,
    ): Action {
        return RestAction(
            spec = actionMetadata,
            domainTypes = domainTypes,
            restClient = restClient,
            objectMapper = objectMapper,
            serverRegistration = registration,
        )
    }

    /**
     * Return a single list of types
     */
    private fun canonicalizedTypes(
        serverTypes: Collection<DynamicType>,
        agentPlatform: AgentPlatform,
    ): Set<DomainType> {
        val platformTypes = agentPlatform.domainTypes
        return serverTypes.map { serverType ->
            platformTypes.find { it.name == serverType.name } ?: serverType
        }.toSet()
    }
}