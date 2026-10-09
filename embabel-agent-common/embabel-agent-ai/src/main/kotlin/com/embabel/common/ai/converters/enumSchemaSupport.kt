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

import com.fasterxml.classmate.ResolvedType
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Marks an enum whose constants carry [@JsonPropertyDescription][JsonPropertyDescription]
 * annotations that should reach the model as per-constant descriptions in the generated
 * JSON Schema.
 *
 * When present on an enum class, Embabel emits the enum as:
 * ```json
 * { "type": "string", "oneOf": [
 *     { "const": "URGENT", "description": "Needs same-day response" },
 *     { "const": "NORMAL", "description": "Standard turnaround" },
 *     { "const": "OTHER" }
 * ]}
 * ```
 * instead of the default `{ "type": "string", "enum": ["URGENT", "NORMAL", "OTHER"] }`.
 *
 * **Trade-off**: the `oneOf` keyword causes Embabel to skip the native structured-output
 * path (gated by [hasUnsupportedJsonSchemaKeywords]) and fall back to the prompt-based
 * path for that call. The descriptions still reach the model through the prompt schema
 * on all providers.
 *
 * Usage:
 * ```kotlin
 * @EnumAsOneOf
 * enum class Priority {
 *     @JsonPropertyDescription("Needs same-day response") URGENT,
 *     @JsonPropertyDescription("Standard turnaround")    NORMAL,
 *     OTHER,
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class EnumAsOneOf

/**
 * Installs [EnumConstantDescriptionProvider] on this [SchemaGeneratorConfigBuilder],
 * enabling per-constant descriptions for enums annotated with [@EnumAsOneOf][EnumAsOneOf].
 *
 * This is an extension function so callers can compose schema customisations without
 * subclassing [JacksonOutputConverter] or [FilteringJacksonOutputConverter].
 */
fun SchemaGeneratorConfigBuilder.withEnumConstantDescriptions(
    objectMapper: ObjectMapper,
): SchemaGeneratorConfigBuilder = this.with(EnumConstantDescriptionProvider(objectMapper))

/**
 * A victools [CustomDefinitionProviderV2] that emits per-constant descriptions from
 * [@JsonPropertyDescription][JsonPropertyDescription] annotations on enum constants.
 *
 * For an enum like:
 * ```kotlin
 * @EnumAsOneOf
 * enum class Priority {
 *     @JsonPropertyDescription("Needs same-day response") URGENT,
 *     @JsonPropertyDescription("Standard turnaround")    NORMAL,
 *     OTHER
 * }
 * ```
 * the generated schema node becomes:
 * ```json
 * { "type": "string", "oneOf": [
 *     { "const": "URGENT", "description": "Needs same-day response" },
 *     { "const": "NORMAL", "description": "Standard turnaround" },
 *     { "const": "OTHER" }
 * ]}
 * ```
 *
 * The serialised constant value is derived from the configured [ObjectMapper] so that
 * `@JsonValue`, `@JsonProperty`, and any custom serialiser are honoured — matching
 * exactly what the prompt example serialises.
 *
 * This provider is installed unconditionally on the [SchemaGeneratorConfigBuilder].
 * The [@EnumAsOneOf][EnumAsOneOf] annotation on the enum class is the opt-in signal —
 * victools calls this provider for every type it resolves (including enums nested inside
 * collections and nested records), and the annotation check here gates whether `oneOf`
 * is emitted. Unannotated enums are returned as `null`, deferring to victools default behaviour.
 *
 * Returns `null` (deferring to victools default behaviour) when:
 * - the type is not an enum,
 * - the enum is not annotated with [@EnumAsOneOf][EnumAsOneOf],
 * - no constant carries a non-empty [@JsonPropertyDescription][JsonPropertyDescription], or
 * - any constant's serialised value is non-textual (e.g. a number from a `@JsonValue`
 *   that returns `Int`) — in that case `type: string` would be incorrect.
 */
internal class EnumConstantDescriptionProvider(
    private val objectMapper: ObjectMapper,
) : CustomDefinitionProviderV2 {

    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext,
    ): CustomDefinition? {
        val rawType: Class<*> = javaType.erasedType
        if (!rawType.isEnum) return null
        if (!rawType.isAnnotationPresent(EnumAsOneOf::class.java)) return null

        val constants: Array<out Any> = rawType.enumConstants ?: return null

        data class ConstantMeta(val serializedName: JsonNode, val description: String?)

        val meta: Map<String, ConstantMeta> = constants.associate { constant ->
            val enumName = (constant as Enum<*>).name
            val description = runCatching {
                rawType.getDeclaredField(enumName)
                    .getAnnotation(JsonPropertyDescription::class.java)
                    ?.value
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            val serializedName: JsonNode = runCatching {
                objectMapper.valueToTree<JsonNode>(constant)
            }.getOrElse { objectMapper.nodeFactory.textNode(enumName) }
            enumName to ConstantMeta(serializedName, description)
        }

        if (meta.values.none { it.description != null }) return null
        if (meta.values.any { !it.serializedName.isTextual }) return null

        val node = context.generatorConfig.createObjectNode()
        node.put("type", "string")
        val oneOfArray = node.putArray("oneOf")
        constants.forEach { constant ->
            val enumName = (constant as Enum<*>).name
            val (serializedName, description) = meta[enumName] ?: return@forEach
            val entry = oneOfArray.addObject()
            entry.set("const", serializedName)
            description?.let { entry.put("description", it) }
        }
        return CustomDefinition(node)
    }
}
