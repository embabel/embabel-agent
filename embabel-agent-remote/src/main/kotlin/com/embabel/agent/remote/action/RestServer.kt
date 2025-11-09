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
package com.embabel.agent.remote.action

import com.embabel.agent.core.*
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.converter.ByteArrayHttpMessageConverter
import org.springframework.http.converter.ResourceHttpMessageConverter
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter
import org.springframework.web.client.RestClient
import org.springframework.web.client.body


/**
 * Remote server that exposes actions over HTTP and REST
 */
class RestServer(
    val registration: RestServerRegistration,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
) {

    companion object {
        /**
         * Create a properly configured RestClient with JSON support.
         * Uses HTTP/1.1 only to avoid protocol upgrade issues with some servers.
         */
        fun createRestClient(objectMapper: ObjectMapper): RestClient {
            // Use JDK HttpClient with HTTP/1.1 only (no upgrade protocols)
            val jdkHttpClient = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build()
            val requestFactory = org.springframework.http.client.JdkClientHttpRequestFactory(jdkHttpClient)

            return RestClient.builder()
                .requestFactory(requestFactory)
                .messageConverters { converters ->
                    converters.add(ByteArrayHttpMessageConverter())
                    converters.add(StringHttpMessageConverter())
                    converters.add(ResourceHttpMessageConverter())
                    converters.add(AllEncompassingFormHttpMessageConverter())
                    converters.add(MappingJackson2HttpMessageConverter(objectMapper))
                }
                .build()
        }
    }

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
        return objectMapper.readValue(json, object : TypeReference<List<RestActionMetadata>>() {})
    }

    private fun serverTypes(): Collection<DynamicType> {
        val json = restClient
            .get()
            .uri("${registration.baseUrl}/api/v1/types")
            .retrieve()
            .body<String>()
        return objectMapper.readValue(json, object : TypeReference<List<DynamicType>>() {})
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
