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

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.victools.jsonschema.generator.*
import com.github.victools.jsonschema.module.jackson.JacksonModule
import com.github.victools.jsonschema.module.jackson.JacksonOption
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.converter.StructuredOutputConverter
import org.springframework.ai.util.LoggingMarkers
import org.springframework.core.ParameterizedTypeReference
import java.lang.reflect.Type

/**
 * A Kotlin version of [org.springframework.ai.converter.BeanOutputConverter] that allows for customization
 * of the used schema via [postProcessSchema]
 */
open class JacksonOutputConverter<T> protected constructor(
    private val type: Type,
    val objectMapper: ObjectMapper,
) : StructuredOutputConverter<T> {

    constructor(
        clazz: Class<T>,
        objectMapper: ObjectMapper,
    ) : this(clazz as Type, objectMapper)

    constructor(
        typeReference: ParameterizedTypeReference<T>,
        objectMapper: ObjectMapper,
    ) : this(typeReference.type, objectMapper)

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    /**
     * Lenient ObjectMapper for parsing LLM output.
     * Copies all configuration from the provided objectMapper and enables
     * additional features to handle common JSON formatting issues from LLMs:
     * - ALLOW_TRAILING_COMMA: `{"a": 1,}` is valid
     * - ALLOW_SINGLE_QUOTES: `{'a': 'b'}` is valid
     * - ALLOW_UNQUOTED_FIELD_NAMES: `{a: "b"}` is valid
     * - ALLOW_JAVA_COMMENTS: `{"a": 1 /* comment */}` is valid
     */
    private val lenientMapper: ObjectMapper by lazy {
        objectMapper.copy().apply {
            // Enable lenient JSON parsing features for LLM output
            enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature())
            enable(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature())
            enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature())
            enable(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature())
            enable(JsonReadFeature.ALLOW_YAML_COMMENTS.mappedFeature())
        }
    }

    val jsonSchema: String by lazy {
        val jacksonModule = JacksonModule(
            JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
            JacksonOption.RESPECT_JSONPROPERTY_ORDER
        )
        val configBuilder = SchemaGeneratorConfigBuilder(
            SchemaVersion.DRAFT_2020_12,
            OptionPreset.PLAIN_JSON
        )
            .with(jacksonModule)
            .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
        val config = configBuilder.build()
        val generator = SchemaGenerator(config)
        val jsonNode: JsonNode = generator.generateSchema(this.type)
        postProcessSchema(jsonNode)
        val objectWriter = this.objectMapper.writer(
            DefaultPrettyPrinter()
                .withObjectIndenter(DefaultIndenter().withLinefeed(System.lineSeparator()))
        )
        try {
            objectWriter.writeValueAsString(jsonNode)
        } catch (e: JsonProcessingException) {
            logger.error("Could not pretty print json schema for jsonNode: {}", jsonNode)
            throw RuntimeException("Could not pretty print json schema for " + this.type, e)
        }
    }

    /**
     * Empty template method that allows for customization of the JSON schema in subclasses.
     * @param jsonNode the JSON schema, in the form of a JSON node
     */
    protected open fun postProcessSchema(jsonNode: JsonNode) {
    }

    override fun convert(text: String): T? {
        val unwrapped = unwrapJson(text)
        try {
            return lenientMapper.readValue<Any?>(unwrapped, lenientMapper.constructType(this.type)) as T?
        } catch (e: JsonProcessingException) {
            logger.error(
                LoggingMarkers.SENSITIVE_DATA_MARKER,
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

        // Fix malformed escaped quotes - this is the one issue Jackson can't handle
        // because `"key": \"value\"` is fundamentally broken syntax
        result = fixMalformedEscapedQuotes(result)

        return result
    }

    /**
     * Fix malformed JSON where the LLM has incorrectly escaped quote characters
     * that should be JSON string delimiters.
     *
     * This fixes cases like: `"span": \"Glazunov's violin concerto\",`
     * where the LLM escapes the quotes that delimit the string value itself.
     *
     * Note: Jackson's lenient features can't handle this because the backslash
     * before the opening quote makes it syntactically invalid in a way no parser
     * can interpret correctly.
     */
    private fun fixMalformedEscapedQuotes(json: String): String {
        return json
            .replace(Regex(""":\s*\\""""), ": \"")   // Fix `: \"` -> `: "`
            .replace(Regex("""\\","""), "\",")       // Fix `\",` -> `",`
            .replace(Regex("""\\"(\s*})"""), "\"$1") // Fix `\" }` -> `" }`
            .replace(Regex("""\\"(\s*])"""), "\"$1") // Fix `\" ]` -> `" ]`
    }

    override fun getFormat(): String =
        """|
           |Your response should be in JSON format.
           |Do not include any explanations, only provide a RFC8259 compliant JSON response following this format without deviation.
           |Do not include markdown code blocks in your response.
           |Remove the ```json markdown from the output.
           |Here is the JSON Schema instance your output must adhere to:
           |```${jsonSchema}```
           |""".trimMargin()
}
