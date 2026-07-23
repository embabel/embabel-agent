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
package com.embabel.common.ai.converters

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.validation.constraints.NotNull
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.Metadata
import kotlin.reflect.full.memberProperties

private val jsonSchemaObjectMapper = ObjectMapper()

private val unsupportedJsonSchemaKeywords = setOf(
    "\$ref",
    "oneOf",
    "anyOf",
    "allOf",
    "not",
    "if",
    "then",
    "else",
    "dependentSchemas",
    "patternProperties",
    "unevaluatedProperties",
    "prefixItems",
    "contains",
)

private val schemaPropertyMetadataCache = ConcurrentHashMap<Class<*>, Map<String, SchemaPropertyMetadata>>()

/**
 * Parse JSON Schema text into a [JsonNode].
 *
 * Returns `null` when the input is not valid JSON.
 */
fun parseJsonSchema(schema: String): JsonNode? =
    runCatching { jsonSchemaObjectMapper.readTree(schema) }.getOrNull()

/**
 * Return the schema `type` value if present.
 */
fun JsonNode.schemaType(): String? = get("type")?.asText()

/**
 * Return the `properties` node if present.
 *
 * This is a raw JSON Schema access helper. It does not interpret provider rules.
 */
fun JsonNode.propertiesNode(): JsonNode? = get("properties")

/**
 * Return the `required` property names declared by the schema.
 *
 * Non-text entries are ignored.
 */
fun JsonNode.requiredFieldNames(): Set<String> =
    get("required")
        ?.takeIf { it.isArray }
        ?.mapNotNull { if (it.isTextual) it.asText() else null }
        ?.toSet()
        .orEmpty()

/**
 * Return the `additionalProperties` node if present.
 */
fun JsonNode.additionalPropertiesNode(): JsonNode? = get("additionalProperties")

/**
 * Return the `items` node if present.
 */
fun JsonNode.itemsNode(): JsonNode? = get("items")

/**
 * Normalize the schema's `required` arrays from trusted type metadata.
 *
 * This is deliberately conservative. It uses only signals Embabel already trusts:
 * Kotlin nullability, explicit `@JsonProperty(required = true)`, bean validation
 * `@NotNull`, and Java primitives.
 */
fun JsonNode.normalizeRequiredFields(type: java.lang.reflect.Type, objectMapper: ObjectMapper): JsonNode =
    apply {
        val javaType = objectMapper.typeFactory.constructType(type)
        normalizeRequiredFields(this, javaType, objectMapper, this)
    }

/**
 * Walk the generated schema tree and normalize object `required` arrays from trusted type metadata.
 *
 * Traversal is conservative and order-aware:
 * - local `$ref` targets are resolved first, so referenced object definitions are normalized in place
 * - object nodes are then matched against the runtime type's property metadata
 * - array `items` and object `additionalProperties` are traversed recursively when they contain object schemas
 *
 * This keeps the schema-compatible subset small and predictable while avoiding repeated reflection work.
 */
private fun normalizeRequiredFields(
    schemaNode: JsonNode,
    javaType: JavaType,
    objectMapper: ObjectMapper,
    rootSchemaNode: JsonNode,
) {
    val objectNode = schemaNode as? ObjectNode ?: return

    resolveLocalSchemaRef(rootSchemaNode, objectNode)?.let { resolvedNode ->
        normalizeRequiredFields(resolvedNode, javaType, objectMapper, rootSchemaNode)
        return
    }

    when {
        objectNode.schemaType() == "array" || objectNode.itemsNode() != null -> {
            normalizeArraySchema(objectNode, javaType, objectMapper, rootSchemaNode)
        }

        objectNode.schemaType() == "object" ||
            objectNode.propertiesNode() != null ||
            objectNode.additionalPropertiesNode() != null -> {
            normalizeObjectSchema(objectNode, javaType, objectMapper, rootSchemaNode)
        }
    }
}

private fun normalizeObjectSchema(
    objectNode: ObjectNode,
    javaType: JavaType,
    objectMapper: ObjectMapper,
    rootSchemaNode: JsonNode,
) {
    val required = linkedSetOf<String>()
    val propertiesByName = resolveSchemaPropertyMetadata(javaType, objectMapper)
    val propertiesNode = objectNode.propertiesNode() as? ObjectNode

    propertiesNode?.fieldNames()?.forEach { propertyName ->
        val propertySchema = propertiesNode.get(propertyName) as? ObjectNode ?: return@forEach
        val property = propertiesByName[propertyName] ?: return@forEach

        if (property.required) {
            required.add(propertyName)
        }

        property.childType?.let { childType ->
            normalizeRequiredFields(propertySchema, childType, objectMapper, rootSchemaNode)
        }
    }

    if (required.isEmpty()) {
        objectNode.remove("required")
    } else {
        objectNode.set<JsonNode>("required", objectMapper.valueToTree(required))
    }

    objectNode.additionalPropertiesNode()?.let { additionalProperties ->
        if (additionalProperties is ObjectNode) {
            javaType.contentType?.let { contentType ->
                normalizeRequiredFields(additionalProperties, contentType, objectMapper, rootSchemaNode)
            }
        }
    }
}

private fun normalizeArraySchema(
    objectNode: ObjectNode,
    javaType: JavaType,
    objectMapper: ObjectMapper,
    rootSchemaNode: JsonNode,
) {
    val itemsNode = objectNode.itemsNode()
    if (itemsNode is ObjectNode) {
        javaType.contentType?.let { contentType ->
            normalizeRequiredFields(itemsNode, contentType, objectMapper, rootSchemaNode)
        }
    }
}

private fun resolveLocalSchemaRef(rootSchemaNode: JsonNode, schemaNode: ObjectNode): ObjectNode? {
    val ref = schemaNode.get("\$ref")?.takeIf { it.isTextual }?.asText() ?: return null
    if (!ref.startsWith("#/")) {
        return null
    }

    val target = ref
        .removePrefix("#/")
        .split('/')
        .fold(rootSchemaNode) { current, token ->
            current.get(token) ?: return null
        }

    return target as? ObjectNode
}

private fun resolveSchemaPropertyMetadata(
    javaType: JavaType,
    objectMapper: ObjectMapper,
): Map<String, SchemaPropertyMetadata> {
    return schemaPropertyMetadataCache.computeIfAbsent(javaType.rawClass) { rawType ->
        if (rawType.isAnnotationPresent(Metadata::class.java)) {
            resolveKotlinSchemaPropertyMetadata(javaType, objectMapper)
        } else {
            resolveJavaSchemaPropertyMetadata(rawType, objectMapper)
        }
    }
}

private fun resolveKotlinSchemaPropertyMetadata(
    javaType: JavaType,
    objectMapper: ObjectMapper,
): Map<String, SchemaPropertyMetadata> {
    val rawType = javaType.rawClass
    val beanDescription = objectMapper.serializationConfig.introspect(javaType)
    return beanDescription.findProperties().associate { property ->
        property.name to property.toSchemaPropertyMetadata(rawType)
    }
}

private fun resolveJavaSchemaPropertyMetadata(
    rawType: Class<*>,
    objectMapper: ObjectMapper,
): Map<String, SchemaPropertyMetadata> {
    return rawType.declaredFields
        .asSequence()
        .filterNot { Modifier.isStatic(it.modifiers) }
        .associate { field ->
            field.name to field.toSchemaPropertyMetadata(objectMapper)
        }
}

private fun BeanPropertyDefinition.toSchemaPropertyMetadata(
    ownerType: Class<*>,
): SchemaPropertyMetadata {
    val required = isRequired ||
        hasTrustedRequiredAnnotation(primaryMember) ||
        hasTrustedRequiredAnnotation(field) ||
        hasTrustedRequiredAnnotation(getter) ||
        isKotlinNonNull(ownerType, name)

    return SchemaPropertyMetadata(
        required = required,
        childType = primaryMember?.type,
    )
}

private fun Field.toSchemaPropertyMetadata(objectMapper: ObjectMapper): SchemaPropertyMetadata {
    val required = type.isPrimitive ||
        getAnnotation(JsonProperty::class.java)?.required == true ||
        isAnnotationPresent(NotNull::class.java)

    return SchemaPropertyMetadata(
        required = required,
        childType = objectMapper.typeFactory.constructType(genericType),
    )
}

private fun hasTrustedRequiredAnnotation(member: com.fasterxml.jackson.databind.introspect.AnnotatedMember?): Boolean {
    return member != null && (
        member.getAnnotation(JsonProperty::class.java)?.required == true ||
            member.hasAnnotation(NotNull::class.java)
        )
}

private fun isKotlinNonNull(ownerType: Class<*>, propertyName: String): Boolean {
    if (!ownerType.isAnnotationPresent(Metadata::class.java)) {
        return false
    }

    val kotlinProperty = ownerType.kotlin.memberProperties.firstOrNull { it.name == propertyName }
    return kotlinProperty != null && !kotlinProperty.returnType.isMarkedNullable
}

private data class SchemaPropertyMetadata(
    val required: Boolean,
    val childType: JavaType?,
)

/**
 * True when the schema node contains keywords that are not part of the conservative
 * native-structured-output subset currently supported by Embabel.
 *
 * This is a recursive walk over the schema tree. It is intentionally stricter than
 * generic JSON Schema validity because the goal is to answer a narrower question:
 * "is this safe to try as provider-native structured output?"
 */
fun JsonNode.hasUnsupportedJsonSchemaKeywords(): Boolean {
    if (unsupportedJsonSchemaKeywords.any(::has)) {
        return true
    }

    return when {
        isObject -> fieldNames().asSequence().any { fieldName ->
            get(fieldName)?.hasUnsupportedJsonSchemaKeywords() == true
        }
        isArray -> elements().asSequence().any { it.hasUnsupportedJsonSchemaKeywords() }
        else -> false
    }
}
