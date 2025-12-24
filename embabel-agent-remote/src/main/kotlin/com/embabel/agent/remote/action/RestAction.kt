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
import com.embabel.agent.core.support.AbstractAction
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

/**
 * Exposes a remote REST endpoint described in the specs
 */
internal class RestAction(
    val serverRegistration: RestServerRegistration,
    val spec: RestActionMetadata,
    qos: ActionQos = ActionQos(),
    override val domainTypes: Collection<DomainType>,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
) : AbstractAction(
    name = spec.name,
    description = spec.description,
    pre = spec.pre,
    post = spec.post,
    cost = { spec.cost },
    value = { spec.value },
    inputs = spec.inputs.map { it.toIoBinding() }.toSet(),
    outputs = spec.outputs.map { it.toIoBinding() }.toSet(),
    toolGroups = emptySet(),
    canRerun = spec.canRerun,
    qos = qos,
) {

    /**
     * Execute by invoking the remote REST endpoint
     */
    override fun execute(
        processContext: ProcessContext,
    ): ActionStatus = ActionRunner.execute(processContext) {
        val inputValues: Map<String, Any> = inputs.associate { input ->
            val value = processContext.agentProcess.getValue(variable = input.name, type = input.type)
                ?: throw IllegalArgumentException("Input ${input.name} of type ${input.type} not found in process context")
            input.name to value
        }
        logger.debug("Resolved action {} inputs {}", name, inputValues)
        val outputBinding = outputs.singleOrNull() ?: error("Need a single output spec in action $name")
        val output = invokeRemoteAction(inputValues)
        processContext.blackboard[outputBinding.name] = output
    }

    override fun referencedInputProperties(variable: String): Set<String> {
        return emptySet()
    }

    private fun invokeRemoteAction(inputValues: Map<String, Any>): Map<*, *> {
        val inputs = mapOf(
            "action_name" to spec.name,
            "parameters" to inputValues,
        )
        val uri = "${serverRegistration.baseUrl}/api/v1/actions/execute"
        logger.info("Sending request to remote action: {} at {}", objectMapper.writeValueAsString(inputs), uri)

        val output = restClient
            .post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(inputs)
            .retrieve()
            .body(object : org.springframework.core.ParameterizedTypeReference<Map<*, *>>() {}) as Map<*, *>
        logger.info("Raw output from action {}: {}", name, output)
        return output
    }
}
