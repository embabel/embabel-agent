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

import tools.jackson.core.JacksonException
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import com.fasterxml.classmate.ResolvedType
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.victools.jsonschema.generator.*
import com.github.victools.jsonschema.module.jackson.JacksonModule
import com.github.victools.jsonschema.module.jackson.JacksonOption
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.converter.StructuredOutputConverter
import org.slf4j.MarkerFactory
import org.springframework.core.ParameterizedTypeReference
import java.lang.reflect.Type

/**
 * Exposes a raw JSON Schema for converters that can describe their target type.
 *
 * This is separate from [StructuredOutputConverter.getFormat], which is prompt
 * text for LLMs. Native structured-output payloads need the schema itself.
 */
interface JsonSchemaProvider {
    fun getJsonSchema(): String
}

/**
 * Controls whether generated JSON schemas are normalized from trusted type metadata.
 */
enum class RequiredFieldNormalization {
    ENABLED,
    DISABLED,
}

/**
 * Optional behaviours for [JacksonOutputConverter] schema generation.
 *
 * Pass one or more values to the converter constructor to enable the corresponding behaviour.
 * For the platform path, prefer placing [@ForceOneOfEnum][ForceOneOfEnum] directly on the enum class.
 */
enum class JacksonOutputConverterOption {
    /**
     * Emit per-constant descriptions from [@JsonPropertyDescription][JsonPropertyDescription] on enum constants.
     *
     * When enabled, an enum whose constants carry `@JsonPropertyDescription` is emitted as
     * `{ "type": "string", "oneOf": [ { "const": "…", "description": "…" }, … ] }` instead of a
     * bare `enum` array, so the model receives the prose written for each option.
     *
     * **Trade-off**: `oneOf` causes Embabel's own [hasUnsupportedJsonSchemaKeywords] gate to skip
     * the native structured-output path and fall back to injecting the schema via the prompt
     * ([JacksonOutputConverter.getFormat]). The descriptions still reach the model on all providers
     * through that prompt path — this is not an OpenAI-only limitation.
     *
     * Enums whose constants carry no `@JsonPropertyDescription` are unaffected regardless of this option.
     * Prefer [@ForceOneOfEnum][ForceOneOfEnum] on the enum class for the Embabel platform path.
     */
    ENUM_CONSTANT_DESCRIPTIONS,
}

/**
 * A victools [CustomDefinitionProviderV2] that emits per-constant descriptions from
 * [@JsonPropertyDescription][JsonPropertyDescription] annotations on enum constants.
 *
 * For an enum like:
 * ```kotlin
 * @ForceOneOfEnum
 * enum class Priority {
 *     @JsonPropertyDescription("Needs same-day response") URGENT,
 *     @JsonPropertyDescription("Standard turnaround")    NORMAL,
 *     OTHER
 * }
 * ```
 * the generated schema becomes:
 * ```json
 * { "type": "string", "oneOf": [
 *     { "const": "URGENT", "description": "Needs same-day response" },
 *     { "const": "NORMAL", "description": "Standard turnaround" },
 *     { "const": "OTHER" }
 * ]}
 * ```
 * instead of the default `{ "type": "string", "enum": ["URGENT", "NORMAL", "OTHER"] }`.
 *
 * The serialised constant value is derived from the configured [ObjectMapper] so that
 * `@JsonValue`, `@JsonProperty`, and any custom serialiser are honoured automatically —
 * matching exactly what [WithExampleConverter] serialises in the prompt example.
 *
 * Returns `null` (deferring to the default victools behaviour) when:
 * - the type is not an enum,
 * - the enum class does not carry [@ForceOneOfEnum][ForceOneOfEnum] (when activated via that path), or
 * - no constant on the enum carries `@JsonPropertyDescription`.
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

        // Pre-compute field metadata once per enum type to avoid repeated reflection per constant.
        val constants: Array<out Any> = rawType.enumConstants ?: return null
        data class ConstantMeta(val serializedName: JsonNode, val description: String?)
        val meta: Map<String, ConstantMeta> = constants.associate { constant ->
            val name = (constant as Enum<*>).name
            val description = runCatching {
                rawType.getDeclaredField(name)
                    .getAnnotation(JsonPropertyDescription::class.java)
                    ?.value
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            // Derive wire value via ObjectMapper — honours @JsonValue, @JsonProperty, custom serialisers.
            val serializedName = runCatching {
                objectMapper.valueToTree<JsonNode>(constant)
            }.getOrElse { objectMapper.nodeFactory.textNode(name) }
            name to ConstantMeta(serializedName, description)
        }

        // Only activate when at least one constant carries a non-empty description.
        if (meta.values.none { it.description != null }) return null

        // Build: { "type": "string", "oneOf": [ { "const": <value>, "description": "…" }, … ] }
        // "type": "string" is required — a custom definition replaces the standard one wholesale,
        // so the type field must be added explicitly. Victools' own enum output always carries it,
        // and provider schema bridges (e.g. Gemini responseSchema) require it.
        val node = context.generatorConfig.createObjectNode()
        node.put("type", "string")
        val oneOfArray = node.putArray("oneOf")
        constants.forEach { constant ->
            val name = (constant as Enum<*>).name
            val (serializedName, description) = meta[name] ?: return@forEach
            val entry = oneOfArray.addObject()
            entry.set("const", serializedName)
            description?.let { entry.put("description", it) }
        }
        return CustomDefinition(node)
    }
}

/**
 * Returns `true` when the given [outputClass] has any field whose enum type carries
 * [@ForceOneOfEnum][ForceOneOfEnum], indicating that the platform should enable
 * [JacksonOutputConverterOption.ENUM_CONSTANT_DESCRIPTIONS] for this converter.
 */
internal fun resolveConverterOptions(outputClass: Class<*>): Set<JacksonOutputConverterOption> {
    val hasForceOneOfEnum = outputClass.declaredFields.any { field ->
        field.type.isEnum && field.type.isAnnotationPresent(ForceOneOfEnum::class.java)
    }
    return if (hasForceOneOfEnum) setOf(JacksonOutputConverterOption.ENUM_CONSTANT_DESCRIPTIONS) else emptySet()
}

/**
 * A Kotlin version of [org.springframework.ai.converter.BeanOutputConverter] that allows for customization
 * of the used schema via [postProcessSchema]
 */
open class JacksonOutputConverter<T : Any> protected constructor(
    private val type: Type,
    val objectMapper: ObjectMapper,
    private val requiredFieldNormalization: RequiredFieldNormalization = RequiredFieldNormalization.ENABLED,
    private val options: Set<JacksonOutputConverterOption> = emptySet(),
) : StructuredOutputConverter<T>, JsonSchemaProvider {

    // Original binary-compatible constructors — unchanged signatures.
    constructor(
        clazz: Class<T>,
        objectMapper: ObjectMapper,
        requiredFieldNormalization: RequiredFieldNormalization = RequiredFieldNormalization.ENABLED,
    ) : this(clazz as Type, objectMapper, requiredFieldNormalization, emptySet())

    constructor(
        typeReference: ParameterizedTypeReference<T>,
        objectMapper: ObjectMapper,
        requiredFieldNormalization: RequiredFieldNormalization = RequiredFieldNormalization.ENABLED,
    ) : this(typeReference.type, objectMapper, requiredFieldNormalization, emptySet())

    // Protected Type constructor retained for subclass binary compatibility.
    // Subclasses compiled against the pre-options ABI call super(Type, ObjectMapper, RequiredFieldNormalization).
    protected constructor(
        type: Type,
        objectMapper: ObjectMapper,
        requiredFieldNormalization: RequiredFieldNormalization,
    ) : this(type, objectMapper, requiredFieldNormalization, emptySet())

    // Option-aware overloads — separate constructors to preserve JVM binary compatibility.
    constructor(
        clazz: Class<T>,
        objectMapper: ObjectMapper,
        options: Set<JacksonOutputConverterOption>,
    ) : this(clazz as Type, objectMapper, RequiredFieldNormalization.ENABLED, options)

    constructor(
        typeReference: ParameterizedTypeReference<T>,
        objectMapper: ObjectMapper,
        options: Set<JacksonOutputConverterOption>,
    ) : this(typeReference.type, objectMapper, RequiredFieldNormalization.ENABLED, options)

    // Full overloads combining normalization and options.
    constructor(
        clazz: Class<T>,
        objectMapper: ObjectMapper,
        requiredFieldNormalization: RequiredFieldNormalization,
        options: Set<JacksonOutputConverterOption>,
    ) : this(clazz as Type, objectMapper, requiredFieldNormalization, options)

    constructor(
        typeReference: ParameterizedTypeReference<T>,
        objectMapper: ObjectMapper,
        requiredFieldNormalization: RequiredFieldNormalization,
        options: Set<JacksonOutputConverterOption>,
    ) : this(typeReference.type, objectMapper, requiredFieldNormalization, options)

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    /**
     * Lenient ObjectMapper for parsing LLM output.
     * Copies all configuration from the provided objectMapper and enables
     * additional features to handle common JSON formatting issues from LLMs:
     * - ALLOW_TRAILING_COMMA: `{"a": 1,}` is valid
     * - ALLOW_SINGLE_QUOTES: `{'a': 'b'}` is valid
     * - ALLOW_UNQUOTED_FIELD_NAMES: `{a: "b"}` is valid
     * - ALLOW_JAVA_COMMENTS: `{"a": 1 /* comment */}` is valid
     * - ALLOW_UNESCAPED_CONTROL_CHARS: """{"name":"Hello
     * World"}""" is valid.
     */
    private val lenientMapper: ObjectMapper by lazy {
        // Jackson 3: ObjectMapper is immutable; reconfigure via rebuild() builder.
        // JsonReadFeature is JSON-specific and used directly (no mappedFeature() in Jackson 3).
        (objectMapper as JsonMapper).rebuild()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build()
    }

    private val jsonSchemaValue: String by lazy {
        val config = schemaGeneratorConfigBuilder().build()
        val generator = SchemaGenerator(config)
        val jsonNode: JsonNode = generator.generateSchema(this.type)
        if (requiredFieldNormalization == RequiredFieldNormalization.ENABLED) {
            jsonNode.normalizeRequiredFields(this.type, this.objectMapper)
        }
        postProcessSchema(jsonNode)
        val objectWriter = this.objectMapper.writer()
            .with(
                DefaultPrettyPrinter()
                    .withObjectIndenter(DefaultIndenter().withLinefeed(System.lineSeparator()))
            )
        try {
            objectWriter.writeValueAsString(jsonNode)
        } catch (e: JacksonException) {
            logger.error("Could not pretty print json schema for jsonNode: {}", jsonNode)
            throw RuntimeException("Could not pretty print json schema for " + this.type, e)
        }
    }

    /**
     * Template method that allows for customization of the JSON Schema generator.
     * By defaults, this method generates a configuration that uses [Draft 2020-12](https://json-schema.org/draft/2020-12#draft-2020-12)
     * of the specification, with the [JacksonModule] enabled.
     */
    protected open fun schemaGeneratorConfigBuilder(): SchemaGeneratorConfigBuilder {
        val builder = SchemaGeneratorConfigBuilder(
            SchemaVersion.DRAFT_2020_12,
            OptionPreset.PLAIN_JSON
        )
            .with(
                JacksonModule(
                    JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                    JacksonOption.RESPECT_JSONPROPERTY_ORDER
                )
            )
            .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)

        if (JacksonOutputConverterOption.ENUM_CONSTANT_DESCRIPTIONS in options) {
            builder.with(EnumConstantDescriptionProvider(objectMapper))
        }

        return builder
    }

    /**
     * Hook for subclasses to customize the generated JSON schema after the standard
     * schema normalization has run.
     *
     * @param jsonNode the JSON schema, in the form of a JSON node
     */
    protected open fun postProcessSchema(jsonNode: JsonNode) = Unit

    override fun convert(text: String): T {
        val unwrapped = unwrapJson(text)
        try {
            return lenientMapper.readValue<Any?>(unwrapped, lenientMapper.constructType(this.type)) as T
        } catch (e: JacksonException) {
            // Some LLMs escape the very quotes that delimit a string value (e.g. `"key": \"value\"`),
            // which Jackson cannot parse. Retry once with those delimiter quotes repaired. The repair
            // rewrites `\"` only at string delimiter positions, so valid JSON containing legitimately
            // escaped quotes (e.g. `["\"A\""]`) is never altered, even on this fallback path.
            val repaired = fixMalformedEscapedQuotes(unwrapped)
            if (repaired != unwrapped) {
                try {
                    return lenientMapper.readValue<Any?>(repaired, lenientMapper.constructType(this.type)) as T
                } catch (_: JacksonException) {
                    // fall through and report the original failure below
                }
            }
            logger.error(
                // Spring AI 2.0 removed org.springframework.ai.util.LoggingMarkers; reproduce the
                // same SLF4J marker ("SENSITIVE") so existing sensitive-data log filtering still applies.
                MarkerFactory.getMarker("SENSITIVE"),
                "Could not parse the given text to the desired target type: \"{}\" into {}", unwrapped, this.type
            )
            throw RuntimeException(e)
        }
    }

    private fun unwrapJson(text: String): String {
        var result = text.trim()

        // Remove markdown code blocks
        if (result.startsWith("```") && result.endsWith("```")) {
            result = result.removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }

        return result
    }

    override fun getJsonSchema(): String = jsonSchemaValue

    override fun getFormat(): String =
        """|
           |Your response should be in JSON format.
           |Do not include any explanations, only provide a RFC8259 compliant JSON response following this format without deviation.
           |Do not include markdown code blocks in your response.
           |Remove the ```json markdown from the output.
           |Here is the JSON Schema instance your output must adhere to:
           |```${getJsonSchema()}```
           |""".trimMargin()
}
