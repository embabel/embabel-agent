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
package com.embabel.agent.spi.support

import com.embabel.common.textio.template.CompiledTemplate
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.type.TypeFactory

/**
 * Common reusable artifacts for LlmOperations implementations.
 * These are framework-agnostic utilities shared by both the base tool loop
 * implementation and Spring AI-specific implementations.
 */

/**
 * Structure to be returned by the LLM for "if possible" operations.
 * Allows the LLM to return a result structure under success, or an error message.
 * One of success or failure must be set, but not both.
 */
@JsonDeserialize(using = MaybeReturnDeserializer::class)
internal data class MaybeReturn<T>(
    val success: T? = null,
    val failure: String? = null,
) {
    fun toResult(): Result<T> {
        return if (success != null) {
            Result.success(success)
        } else {
            Result.failure(Exception(failure))
        }
    }

    companion object {
        private const val NO_OUTPUT_MESSAGE = "No output available"

        /**
         * Create a failure MaybeReturn for cases where no output is available
         * (e.g., empty LLM response, replan requested before output produced).
         */
        fun <T> noOutput(): MaybeReturn<T> = MaybeReturn(failure = NO_OUTPUT_MESSAGE)

        /**
         * Create a failure MaybeReturn with a custom message.
         */
        fun <T> failure(message: String): MaybeReturn<T> = MaybeReturn(failure = message)
    }
}

/**
 * Custom deserializer for [MaybeReturn] that gracefully handles deserialization failures
 * and edge cases from LLM responses. Instead of throwing exceptions, it converts invalid
 * inputs into [MaybeReturn.failure] with descriptive error messages.
 *
 * ## Handled Cases
 *
 * ### Valid Success Cases
 * - **`{"success": <valid_object>}`** → `MaybeReturn(success = object)`
 *
 * ### Valid Failure Cases
 * - **`{"failure": "error message"}`** → `MaybeReturn(failure = "error message")`
 *
 * ### Edge Cases (all converted to failures)
 *
 * | Input | Result |
 * |-------|--------|
 * | `{"success": null}` | `failure("Success value was null")` |
 * | `{"failure": null}` | `failure("Failure indicated but no message provided")` |
 * | `{"success": {}}` (missing required fields) | `failure("Missing required field: <name>")` |
 * | `{"success": <invalid_structure>}` | `failure("Invalid data format for expected type")` |
 * | `{"success": X, "failure": Y}` (both non-null) | `failure("Both success and failure provided")` |
 * | `{"success": null, "failure": null}` (both null) | `failure("Both success and failure provided")` |
 * | `{"success": X, "failure": null}` (one value) | deserialize success (ignore null failure) |
 * | `{"success": null, "failure": "msg"}` (one value) | deserialize failure (ignore null success) |
 * | `{"failure": 123}` (non-string) | `failure("Failure message must be a string")` |
 * | `{"failure": {}}` (object/array) | `failure("Failure message must be a string")` |
 * | `{"failure": ""}` (blank string) | `failure("Failure message was empty")` |
 * | `{}` (neither field) | `failure("Neither success nor failure field provided")` |
 *
 * ## Note on Empty String Input
 * Empty string `""` is not valid JSON and causes [com.fasterxml.jackson.core.JsonParseException]
 * before this deserializer is invoked. Empty string handling must be done at the outputParser
 * level using [MaybeReturn.noOutput].
 *
 * @param T the type of the success value
 */
internal class MaybeReturnDeserializer<T> private constructor(
    private val valueType: com.fasterxml.jackson.databind.JavaType,
) : StdDeserializer<MaybeReturn<T>>(MaybeReturn::class.java), ContextualDeserializer {

    /**
     * Required no-arg constructor for Jackson.
     */
    constructor() : this(TypeFactory.defaultInstance().constructType(Any::class.java))

    override fun createContextual(
        ctxt: DeserializationContext,
        property: BeanProperty?,
    ): JsonDeserializer<*> {
        val wrapperType = ctxt.contextualType
        val innerType = if (wrapperType.containedTypeCount() > 0) {
            wrapperType.containedType(0)
        } else {
            ctxt.typeFactory.constructType(Any::class.java)
        }
        return MaybeReturnDeserializer<T>(innerType)
    }

    override fun deserialize(
        parser: JsonParser,
        ctxt: DeserializationContext,
    ): MaybeReturn<T> {
        val node = parser.codec.readTree<JsonNode>(parser)

        // node.get("field") returns non-null if the field EXISTS in JSON (even if value is null)
        // node.isNull returns true if the JSON value is the literal `null`
        // Combined: hasValue = field exists AND value is not JSON null
        val successNode = node.get(FIELD_SUCCESS)
        val failureNode = node.get(FIELD_FAILURE)
        val bothFieldsExist = successNode != null && failureNode != null
        val hasSuccessValue = successNode != null && !successNode.isNull
        val hasFailureValue = failureNode != null && !failureNode.isNull

        return when {
            // Both fields have non-null values - ambiguous
            hasSuccessValue && hasFailureValue -> MaybeReturn.failure(ERROR_BOTH_FIELDS)
            // Both fields exist but both are null - ambiguous
            bothFieldsExist && !hasSuccessValue && !hasFailureValue -> MaybeReturn.failure(ERROR_BOTH_FIELDS)
            // Success has a value (failure may be null or absent)
            hasSuccessValue -> deserializeSuccess(successNode, ctxt)
            // Failure has a value (success may be null or absent)
            hasFailureValue -> deserializeFailure(failureNode)
            // Success field exists but is null (failure absent)
            successNode != null -> deserializeSuccess(successNode, ctxt)
            // Failure field exists but is null (success absent)
            failureNode != null -> deserializeFailure(failureNode)
            // Neither field exists
            else -> MaybeReturn.failure(ERROR_NEITHER_FIELD)
        }
    }

    private fun deserializeFailure(failureNode: JsonNode): MaybeReturn<T> = when {
        failureNode.isNull -> MaybeReturn.failure(ERROR_FAILURE_NULL)
        !failureNode.isTextual -> MaybeReturn.failure(ERROR_FAILURE_NOT_STRING)
        failureNode.asText().isBlank() -> MaybeReturn.failure(ERROR_FAILURE_EMPTY)
        else -> MaybeReturn.failure(failureNode.asText())
    }

    private fun deserializeSuccess(successNode: JsonNode, ctxt: DeserializationContext): MaybeReturn<T> {
        if (successNode.isNull) {
            return MaybeReturn.failure(ERROR_SUCCESS_NULL)
        }

        return try {
            @Suppress("UNCHECKED_CAST")
            val successValue = ctxt.readTreeAsValue(successNode, valueType) as T
            MaybeReturn(success = successValue)
        } catch (e: Exception) {
            val errorMessage = when (e) {
                is MismatchedInputException -> {
                    // Extract field name from path if available (covers missing required fields)
                    val fieldName = e.path.lastOrNull()?.fieldName
                    if (fieldName != null && e.message?.contains("missing") == true) {
                        "$ERROR_MISSING_FIELD$fieldName"
                    } else {
                        ERROR_INVALID_FORMAT
                    }
                }
                else -> "$ERROR_INVALID_SUCCESS${e.message}"
            }
            MaybeReturn.failure(errorMessage)
        }
    }

    companion object {
        // Field names
        private const val FIELD_SUCCESS = "success"
        private const val FIELD_FAILURE = "failure"

        // Error messages
        private const val ERROR_SUCCESS_NULL = "Success value was null"
        private const val ERROR_FAILURE_NULL = "Failure indicated but no message provided"
        private const val ERROR_FAILURE_EMPTY = "Failure message was empty"
        private const val ERROR_FAILURE_NOT_STRING = "Failure message must be a string"
        private const val ERROR_BOTH_FIELDS = "Both success and failure provided"
        private const val ERROR_NEITHER_FIELD = "Neither success nor failure field provided"
        private const val ERROR_MISSING_FIELD = "Missing required field: "
        private const val ERROR_INVALID_FORMAT = "Invalid data format for expected type"
        private const val ERROR_INVALID_SUCCESS = "Invalid success object: "
    }
}

/**
 * No-op TemplateRenderer that throws UnsupportedOperationException when used.
 * Used as default when no real TemplateRenderer is provided.
 * Operations requiring template rendering (e.g., doTransformIfPossible) will fail
 * clearly if this default is used.
 */
internal object NoOpTemplateRenderer : TemplateRenderer {
    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("TemplateRenderer not configured. Provide a real TemplateRenderer for operations that require template rendering.")

    override fun load(templateName: String): String = unsupported()
    override fun renderLoadedTemplate(templateName: String, model: Map<String, Any>): String = unsupported()
    override fun renderLiteralTemplate(template: String, model: Map<String, Any>): String = unsupported()
    override fun compileLoadedTemplate(templateName: String): CompiledTemplate = unsupported()
}
