package com.embabel.agent.remote.action

import com.embabel.agent.core.*
import com.embabel.agent.core.support.AbstractAction
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * Remote a remote REST endpoint described in the specs
 */
internal class RestAction(
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
    cost = spec.cost,
    value = spec.value,
    inputs = spec.inputs,
    outputs = spec.outputs,
    toolGroups = emptySet(),
    canRerun = spec.canRerun,
    qos = qos,
) {

    override fun execute(
        processContext: ProcessContext,
    ): ActionStatus = ActionRunner.execute(processContext) {
        val inputValues: List<Any> = inputs.map {
            processContext.getValue(variable = it.name, type = it.type)
                ?: throw IllegalArgumentException("Input ${it.name} of type ${it.type} not found in process context")
        }
        logger.debug("Resolved action {} inputs {}", name, inputValues)
        val outputBinding = outputs.singleOrNull() ?: error("Need a single output spec in action $name")
        val output = invokeRemoteAction(inputValues)
        processContext.blackboard[outputBinding.name] = output
    }

    override fun referencedInputProperties(variable: String): Set<String> {
        return emptySet()
    }

    private fun invokeRemoteAction(inputValues: List<Any>): Map<*, *> {
        val json = restClient
            .post()
            .uri(spec.url)
            .body(inputValues)
            .retrieve()
            .body<String>()
        val output = objectMapper.readValue(json, Map::class.java)
        logger.info("Raw output from action {} at {}: {}", name, spec.url, output)
        return output
    }
}