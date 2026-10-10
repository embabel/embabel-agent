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
package com.embabel.agent.api.tool.hitl

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Puts tool arguments into a canonical form so two LLM emissions of the same call
 * compare equal. Key order, whitespace, explicit nulls and integral floats
 * (`1` vs `1.0`) are normalised. Array order is preserved.
 * Input that is not JSON is trimmed and otherwise left alone.
 */
internal object ToolArgumentsCanonicalizer {

    private val objectMapper = ObjectMapper()

    fun canonicalize(input: String): String {
        if (input.isBlank()) {
            return "{}"
        }
        val node = try {
            objectMapper.readTree(input)
        } catch (e: JacksonException) {
            return input.trim()
        }
        if (node == null || node.isMissingNode) {
            return input.trim()
        }
        return objectMapper.writeValueAsString(normalize(node))
    }

    private fun normalize(node: JsonNode): JsonNode = when {
        node.isObject -> {
            val out: ObjectNode = objectMapper.createObjectNode()
            node.properties()
                .filter { !it.value.isNull }
                .sortedBy { it.key }
                .forEach { out.set(it.key, normalize(it.value)) }
            out
        }

        node.isArray -> {
            val out: ArrayNode = objectMapper.createArrayNode()
            node.forEach { out.add(normalize(it)) }
            out
        }

        node.isNumber && !node.isIntegralNumber -> {
            val decimal = node.decimalValue().stripTrailingZeros()
            if (decimal.scale() <= 0) {
                objectMapper.nodeFactory.numberNode(decimal.toBigIntegerExact())
            } else {
                objectMapper.nodeFactory.numberNode(decimal)
            }
        }

        else -> node
    }
}
