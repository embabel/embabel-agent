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
package com.embabel.agent.api.tool

import com.fasterxml.jackson.databind.ObjectMapper
import org.jetbrains.annotations.ApiStatus
import org.slf4j.Logger
import java.lang.reflect.Type

/**
 * Shared utility functions for [Tool] implementations that invoke methods via reflection.
 *
 * Centralises JSON argument parsing, return value serialisation, and parameter type
 * coercion so that [MethodTool] and other reflective tool implementations share a
 * single, tested implementation.
 */
@ApiStatus.Internal
object ToolCallSupport {

    /**
     * Parses a JSON input string into a name→value map.
     * Returns an empty map if [input] is blank or cannot be parsed.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseArguments(input: String, objectMapper: ObjectMapper, logger: Logger): Map<String, Any?> {
        if (input.isBlank()) return emptyMap()
        return try {
            objectMapper.readValue(input, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            logger.warn("Failed to parse tool input as JSON: {}", e.message)
            emptyMap()
        }
    }

    /**
     * Converts a method return value to a [Tool.Result].
     * - `null` → empty text result
     * - [String] → text result
     * - [Tool.Result] → returned as-is
     * - anything else → serialised to JSON with the original object as an artifact,
     *   so that artifact-sinking decorators can capture it for tool chaining
     */
    fun convertResult(result: Any?, objectMapper: ObjectMapper): Tool.Result =
        when (result) {
            null -> Tool.Result.text("")
            is String -> Tool.Result.text(result)
            is Tool.Result -> result
            // Convert to JSON string and preserve the original object as an artifact
            // so that ArtifactSinkingTool can capture it for tool chaining
            else -> try {
                Tool.Result.withArtifact(objectMapper.writeValueAsString(result), result)
            } catch (_: Exception) {
                Tool.Result.withArtifact(result.toString(), result)
            }
        }

    /**
     * Coerces [value] (typically from a JSON parse) to [targetType].
     *
     * Jackson deserialises JSON numbers as [Int] or [Double]; LLMs may also send
     * numbers as quoted strings (e.g. `"5"` instead of `5`). Both cases are handled.
     * For complex types the [ObjectMapper] is used as a fallback.
     */
    fun convertToType(value: Any, targetType: Type, objectMapper: ObjectMapper, logger: Logger): Any {
        if (targetType is Class<*> && targetType.isInstance(value)) return value
        return when (targetType) {
            Int::class.java, Integer::class.java ->
                (value as? Number)?.toInt() ?: (value as? String)?.toIntOrNull() ?: value
            Long::class.java, java.lang.Long::class.java ->
                (value as? Number)?.toLong() ?: (value as? String)?.toLongOrNull() ?: value
            Double::class.java, java.lang.Double::class.java ->
                (value as? Number)?.toDouble() ?: (value as? String)?.toDoubleOrNull() ?: value
            Float::class.java, java.lang.Float::class.java ->
                (value as? Number)?.toFloat() ?: (value as? String)?.toFloatOrNull() ?: value
            Boolean::class.java, java.lang.Boolean::class.java ->
                value as? Boolean ?: value.toString().toBoolean()
            String::class.java -> value.toString()
            else -> try {
                objectMapper.convertValue(value, objectMapper.constructType(targetType))
            } catch (e: Exception) {
                logger.warn("Failed to convert {} to {}: {}", value, targetType, e.message)
                value
            }
        }
    }
}
