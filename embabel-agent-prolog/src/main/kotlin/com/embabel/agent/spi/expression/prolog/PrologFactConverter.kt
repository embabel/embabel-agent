/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.spi.expression.prolog

import org.slf4j.LoggerFactory
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Converts objects annotated with @PrologFact to Prolog facts.
 */
class PrologFactConverter {

    private val logger = LoggerFactory.getLogger(PrologFactConverter::class.java)

    /**
     * Convert an object to Prolog facts.
     * Returns an empty list if the object is not annotated with @PrologFact.
     */
    fun convertToFacts(obj: Any): List<String> {
        val annotation = obj::class.java.getAnnotation(PrologFact::class.java)
            ?: return emptyList()

        val prefix = annotation.predicatePrefix.ifEmpty {
            obj::class.simpleName?.lowercase() ?: "object"
        }
        val excluded = annotation.exclude.toSet()

        val facts = mutableListOf<String>()
        val identifier = getIdentifier(obj)

        // Use Kotlin reflection for better property access
        obj::class.memberProperties.forEach { property ->
            if (property.name !in excluded) {
                try {
                    property.isAccessible = true
                    val value = property.call(obj)
                    val predicate = "${prefix}_${toSnakeCase(property.name)}"
                    facts.addAll(formatPrologFact(predicate, identifier, value))
                } catch (e: Exception) {
                    logger.warn("Failed to access property ${property.name} on ${obj::class.simpleName}", e)
                }
            }
        }

        return facts.filter { it.isNotBlank() }
    }

    /**
     * Get the identifier for an object.
     * Tries 'name' field first, then 'id', then uses the first field.
     */
    private fun getIdentifier(obj: Any): String {
        val properties = obj::class.memberProperties

        // Try 'name' field first
        properties.find { it.name == "name" }?.let { property ->
            property.isAccessible = true
            return sanitizePrologAtom(property.call(obj).toString())
        }

        // Try 'id' field
        properties.find { it.name == "id" }?.let { property ->
            property.isAccessible = true
            return sanitizePrologAtom(property.call(obj).toString())
        }

        // Use first field
        return properties.firstOrNull()?.let { property ->
            property.isAccessible = true
            sanitizePrologAtom(property.call(obj).toString())
        } ?: obj.hashCode().toString()
    }

    /**
     * Format a value as Prolog facts.
     * Returns a list because lists generate multiple facts.
     */
    private fun formatPrologFact(
        predicate: String,
        identifier: String,
        value: Any?,
    ): List<String> {
        return when (value) {
            null -> emptyList()
            is String -> listOf("$predicate('$identifier', '${sanitizePrologString(value)}')")
            is Number -> listOf("$predicate('$identifier', $value)")
            is Boolean -> if (value) listOf("$predicate('$identifier')") else emptyList()
            is List<*> -> value.filterNotNull().map { item ->
                when (item) {
                    is String -> "$predicate('$identifier', '${sanitizePrologString(item)}')"
                    is Number -> "$predicate('$identifier', $item)"
                    else -> "$predicate('$identifier', '${sanitizePrologString(item.toString())}')"
                }
            }

            else -> listOf("$predicate('$identifier', '${sanitizePrologString(value.toString())}')")
        }
    }

    /**
     * Convert camelCase to snake_case.
     */
    private fun toSnakeCase(camelCase: String): String {
        return camelCase
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase()
    }

    /**
     * Sanitize a string for use as a Prolog atom.
     * Removes single quotes and backslashes to prevent injection.
     */
    private fun sanitizePrologAtom(value: String): String {
        return value.replace("'", "").replace("\\", "")
    }

    /**
     * Sanitize a string value for inclusion in Prolog facts.
     * Escapes single quotes.
     */
    private fun sanitizePrologString(value: String): String {
        return value.replace("'", "\\'")
    }
}
