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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Marks an enum class so that each constant's [@JsonPropertyDescription][JsonPropertyDescription]
 * reaches the model as a per-constant description in the generated JSON Schema.
 *
 * Without this annotation, Embabel emits the enum as a bare list of allowed values:
 * ```json
 * { "type": "string", "enum": ["URGENT", "NORMAL", "OTHER"] }
 * ```
 * With this annotation, each constant gets its own `const`+`description` entry inside `oneOf`:
 * ```json
 * { "type": "string", "oneOf": [
 *     { "const": "URGENT", "description": "Needs same-day response" },
 *     { "const": "NORMAL", "description": "Standard turnaround" },
 *     { "const": "OTHER" }
 * ]}
 * ```
 *
 * **Trade-off**: the `oneOf` keyword is not accepted by OpenAI's strict structured-output
 * mode. Embabel detects this and falls back to sending the schema as prompt text for that
 * call, rather than using native structured output. The descriptions still reach the model
 * via the prompt schema on all providers (OpenAI, Google GenAI, Anthropic, etc.).
 *
 * This annotation is processed by [EnumConstantDescriptionProvider], which is wired into
 * the schema generator by [ChatClientLlmOperations]. Annotating an enum without wiring the
 * provider has no effect.
 *
 * Usage (Kotlin):
 * ```kotlin
 * @EnumAsOneOf
 * enum class Priority {
 *     @JsonPropertyDescription("Needs same-day response") URGENT,
 *     @JsonPropertyDescription("Standard turnaround")    NORMAL,
 *     OTHER,
 * }
 * ```
 *
 * Usage (Java):
 * ```java
 * @EnumAsOneOf
 * public enum Priority {
 *     @JsonPropertyDescription("Needs same-day response") URGENT,
 *     @JsonPropertyDescription("Standard turnaround")    NORMAL,
 *     OTHER
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class EnumAsOneOf

/**
 * A victools [CustomDefinitionProviderV2] that builds a `oneOf` schema node for enum
 * classes annotated with [@EnumAsOneOf][EnumAsOneOf], embedding each constant's
 * [@JsonPropertyDescription][JsonPropertyDescription] as a `description` field.
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
 * this provider produces:
 * ```json
 * { "type": "string", "oneOf": [
 *     { "const": "URGENT", "description": "Needs same-day response" },
 *     { "const": "NORMAL", "description": "Standard turnaround" },
 *     { "const": "OTHER" }
 * ]}
 * ```
 *
 * The `const` value for each constant comes from serialising the constant through
 * the configured [ObjectMapper]. This means `@JsonValue`, `@JsonProperty`, and any
 * custom serialiser are respected — the value written to `const` is exactly what
 * Jackson would write when serialising that constant anywhere else in the application.
 *
 * This provider is installed unconditionally on the schema generator. Victools calls it
 * for every type it resolves, including enums nested inside collections and records at
 * any depth. The [@EnumAsOneOf][EnumAsOneOf] check inside [provideCustomSchemaDefinition]
 * is what decides whether `oneOf` is emitted — unannotated enums receive `null` and
 * fall through to victools' default behaviour.
 *
 * Returns `null` (letting victools handle the type normally) when:
 * - the type is not an enum class,
 * - the enum is not annotated with [@EnumAsOneOf][EnumAsOneOf],
 * - no constant carries a non-empty [@JsonPropertyDescription][JsonPropertyDescription], or
 * - any constant serialises to a non-string value (e.g. an `Int` from a `@JsonValue`
 *   method) — in that case writing `"type": "string"` would be wrong.
 *
 * This class is `public` so that callers outside this module can wire it directly:
 * ```kotlin
 * schemaGeneratorConfigBuilder().with(EnumConstantDescriptionProvider(objectMapper))
 * ```
 * Java callers can do the same with `new EnumConstantDescriptionProvider(objectMapper)`.
 */
class EnumConstantDescriptionProvider(
    private val objectMapper: ObjectMapper,
) : CustomDefinitionProviderV2 {

    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext,
    ): CustomDefinition? {
        val rawType: Class<*> = javaType.erasedType

        // Only handle enum types annotated with @EnumAsOneOf.
        if (!rawType.isEnum) return null
        if (!rawType.isAnnotationPresent(EnumAsOneOf::class.java)) return null

        // Class.enumConstants returns null for non-enum classes. The isEnum check
        // above guarantees we have an enum here, but the return type is nullable
        // so we guard anyway.
        val constants: Array<out Any> = rawType.enumConstants ?: return null

        // Holds the serialised constant value and its optional description.
        // Using a local data class keeps the association explicit without a Pair.
        data class ConstantMeta(val serializedName: JsonNode, val description: String?)

        // Build a lookup from each constant's Java field name to its metadata.
        // We use the field name (URGENT, NORMAL, …) as the key because that is
        // stable regardless of any @JsonValue or @JsonProperty renaming.
        val meta: Map<String, ConstantMeta> = constants.associate { constant ->
            val enumName = (constant as Enum<*>).name

            // Read @JsonPropertyDescription from the field declaration, not from
            // a method, because enum constants are declared as fields in bytecode.
            val description = runCatching {
                rawType.getDeclaredField(enumName)
                    .getAnnotation(JsonPropertyDescription::class.java)
                    ?.value
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()

            // Serialise the constant through the ObjectMapper so that @JsonValue,
            // @JsonProperty, and custom serialisers are honoured. Fall back to the
            // declared field name if serialisation fails.
            val serializedName: JsonNode = runCatching {
                objectMapper.valueToTree<JsonNode>(constant)
            }.getOrElse { objectMapper.nodeFactory.textNode(enumName) }

            enumName to ConstantMeta(serializedName, description)
        }

        // If no constant has a description there is nothing to gain from oneOf —
        // the output would be identical to the bare enum array.
        if (meta.values.none { it.description != null }) return null

        // A non-textual const (e.g. a number) would make "type": "string" incorrect.
        // Bail out and let victools emit the default schema for this enum.
        if (meta.values.any { !it.serializedName.isTextual }) return null

        // Build the schema node: { "type": "string", "oneOf": [...] }
        val node = context.generatorConfig.createObjectNode()
        node.put("type", "string")
        val oneOfArray = node.putArray("oneOf")

        // One entry per constant: { "const": "<value>", "description": "..." }
        // Constants without a description get only a "const" entry.
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
