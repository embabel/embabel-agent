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
import com.fasterxml.jackson.databind.JsonNode

/**
 * Provider-neutral policy for deciding whether native structured output should be used.
 *
 * The decision is intentionally conservative:
 * 1. the provider must advertise support
 * 2. the request must carry native structured-output metadata
 * 3. the caller must not disable native output explicitly
 * 4. the schema must fit the conservative native-output shape policy
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
        NativeStructuredOutputMode.ENABLED,
        NativeStructuredOutputMode.DEFAULT ->
            nativeStructuredOutputRequest.structuredOutputRequest.schema.isConservativelyCompatibleWithNativeOutput()
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

    val propertyNames = properties.fieldNames().asSequence().toSet()
    return requiredFieldNames().containsAll(propertyNames) &&
        propertyNames.all { propertyName ->
            properties.get(propertyName)?.isConservativelyCompatibleSchemaNode() == true
        }
}

private fun JsonNode.isConservativelyCompatibleSchemaNode(): Boolean {
    if (!isObject || hasUnsupportedJsonSchemaKeywords()) {
        return false
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

private fun JsonNode.isConservativelyCompatibleArray(): Boolean {
    val items = itemsNode() ?: return false
    if (!items.isObject || items.hasUnsupportedJsonSchemaKeywords()) {
        return false
    }

    val itemType = items.schemaType()
    if (itemType == "object" || items.propertiesNode() != null) {
        return false
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
