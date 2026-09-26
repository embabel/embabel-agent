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
package com.embabel.agent.spi.support.nativeoutput

import com.embabel.agent.spi.loop.LlmMessageRequest
import com.embabel.common.ai.autoconfig.NativeSupport
import com.embabel.common.ai.converters.additionalPropertiesNode
import com.embabel.common.ai.converters.hasUnsupportedJsonSchemaKeywords
import com.embabel.common.ai.converters.itemsNode
import com.embabel.common.ai.converters.parseJsonSchema
import com.embabel.common.ai.converters.propertiesNode
import com.embabel.common.ai.converters.requiredFieldNames
import com.embabel.common.ai.converters.schemaType
import com.embabel.common.ai.model.NativeStructuredOutputMode
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode

private val logger = LoggerFactory.getLogger("com.embabel.agent.spi.support.nativeoutput")

/**
 * Provider-neutral policy for deciding whether native structured output should be used.
 *
 * The decision is intentionally conservative for DEFAULT mode:
 * 1. the model's metadata must declare native structured output as supported
 *    (`NativeSupport.structuredOutput.supported == true`); models that do not
 *    declare it are skipped even if the caller explicitly requests it
 * 2. the request must carry a [NativeStructuredOutputRequest] — i.e. the caller opted in via
 *    [withNativeStructuredOutput] or equivalent
 * 3. the caller must not disable native output explicitly ([NativeStructuredOutputMode.DISABLED])
 * 4. the schema must fit the conservative native-output shape policy
 *    ([NativeStructuredOutputMode.DEFAULT] only; [NativeStructuredOutputMode.ENABLED] bypasses this check)
 *
 * This helper intentionally does not encode provider-specific payload rules such as
 * OpenAI `response_format` details or DeepSeek/OpenAI-compatible transport quirks.
 * Those belong in provider adapters and model metadata, not in the SPI policy gate.
 */
internal fun NativeSupport?.shouldUseNativeStructuredOutput(request: LlmMessageRequest): Boolean {
    val capability = this?.structuredOutput ?: return false
    if (capability.supported != true) {
        return false
    }

    val nativeStructuredOutputRequest = request.nativeStructuredOutputRequest ?: return false
    val mode = nativeStructuredOutputRequest.nativeStructuredOutputMode
    return when (mode) {
        NativeStructuredOutputMode.DISABLED -> false
        NativeStructuredOutputMode.ENABLED -> true
        NativeStructuredOutputMode.DEFAULT -> {
            val compatible = nativeStructuredOutputRequest.structuredOutputRequest.schema
                .isConservativelyCompatibleWithNativeOutput()
            if (!compatible) {
                logger.warn(
                    "Native structured output requested but schema is not compatible; falling back to prompt-based extraction. " +
                        "Use NativeStructuredOutputMode.DISABLED to suppress this warning."
                )
            }
            compatible
        }
    }
}

/**
 * Conservative native-output policy.
 *
 * This is intentionally not a generic JSON Schema validator. It answers a narrower question:
 * can Embabel safely hand this schema to the provider-native structured-output path without
 * knowing provider-specific quirks up front?
 */
private fun String.isConservativelyCompatibleWithNativeOutput(): Boolean =
    parseJsonSchema(this)?.isConservativelyCompatibleWithNativeOutput() ?: false

private fun JsonNode.isConservativelyCompatibleWithNativeOutput(): Boolean {
    if (!isObject || hasUnsupportedJsonSchemaKeywords()) {
        return false
    }

    if (!hasObjectCompatibleType()) {
        return false
    }

    val properties = propertiesNode()
    if (schemaType() == null && properties == null) {
        return false
    }

    if (properties != null && !hasCompatibleObjectProperties(properties)) {
        return false
    }

    return additionalPropertiesNode()?.isObject != true
}

private fun JsonNode.hasObjectCompatibleType(): Boolean =
    schemaType().let { it == null || it == "object" }

private fun JsonNode.hasCompatibleObjectProperties(properties: JsonNode): Boolean {
    if (!properties.isObject) {
        return false
    }

    val propertyNames = properties.propertyNames().asSequence().toSet()
    return requiredFieldNames().containsAll(propertyNames) &&
        propertyNames.all { propertyName ->
            properties.get(propertyName)?.isConservativelyCompatibleSchemaNode() == true
        }
}

private fun JsonNode.isConservativelyCompatibleSchemaNode(): Boolean {
    if (!isObject || hasUnsupportedJsonSchemaKeywords()) {
        return false
    }

    if (get("type")?.isArray == true) {
        return isValidNullableUnion()
    }

    return when (schemaType()) {
        null -> true
        "object" -> isConservativelyCompatibleWithNativeOutput()
        "array" -> isConservativelyCompatibleArray()
        "string",
        "integer",
        "number",
        "boolean",
        "null" -> propertiesNode() == null && itemsNode() == null
        else -> false
    }
}

// Validates a nullable union type — the JSON Schema form of Optional<T> / T?:
//   { "type": ["string", "null"] }                              ← scalar nullable
//   { "type": ["object", "null"], "properties": {...} }         ← nullable object
private fun JsonNode.isValidNullableUnion(): Boolean {
    // "type" must be an array — ["<baseType>", "null"]
    val typeNode = get("type") ?: return false
    if (typeNode.size() != 2) return false
    // collect text values, discarding any non-text nodes (defensive — schema nodes are always text)
    val types = typeNode.mapNotNull { if (it.isTextual) it.asText() else null }.toSet()
    if ("null" !in types) return false
    // exactly one non-null type is guaranteed: size == 2 and "null" is present
    val nonNull = types.single { it != "null" }
    return when (nonNull) {
        "string", "integer", "number", "boolean" -> true
        "object" -> isConservativelyCompatibleWithNativeOutput()
        else -> false
    }
}

private fun JsonNode.isConservativelyCompatibleArray(): Boolean {
    val items = itemsNode() ?: return false
    if (!items.isObject || items.hasUnsupportedJsonSchemaKeywords()) {
        return false
    }

    val itemType = items.schemaType()
    if (itemType == "object" || items.propertiesNode() != null) {
        return items.isConservativelyCompatibleWithNativeOutput()
    }
    if (itemType == "array" || items.itemsNode() != null) {
        return false
    }

    return when (itemType) {
        null,
        "string",
        "integer",
        "number",
        "boolean",
        "null" -> true
        else -> false
    }
}
