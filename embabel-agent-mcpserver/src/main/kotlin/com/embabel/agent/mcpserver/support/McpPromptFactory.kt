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
package com.embabel.agent.mcpserver.support

import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import com.embabel.common.core.types.Timestamped
import com.embabel.common.util.NameUtils
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method

/**
 * Create Prompt specifications for the MCP server.
 * @param excludedInterfaces Set of interfaces whose fields should be excluded from the prompt arguments.
 */
class McpPromptFactory(
    val excludedInterfaces: Set<Class<*>> = setOf(
        Timestamped::class.java,
    ),
) {

    /**
     * Creates a synchronous prompt specification for a given type.
     * @param goal The goal for which the prompt is created.
     * @param inputType The class type of the
     * @param inputType The class type of the input for the prompt.
     * @param name The name of the prompt if we want to customize it
     * @param description A description of the prompt if we want to customize it
     */
    fun <G> syncPromptSpecificationForType(
        goal: G,
        inputType: Class<*>,
        name: String = goal.name,
        description: String = goal.description,
    ): McpServerFeatures.SyncPromptSpecification where G : Named, G : Described {
        return McpServerFeatures.SyncPromptSpecification(
            McpSchema.Prompt(
                "${inputType.simpleName}_$name",
                description,
                argumentsFromType(inputType),
            )
        ) { syncServerExchange, getPromptRequest ->
            McpSchema.GetPromptResult(
                "$name-result",
                listOf(
                    McpSchema.PromptMessage(
                        McpSchema.Role.USER,
                        McpSchema.TextContent(
                            """
                            Use the following information to achieve goal ${goal.name}" - <${goal.description}>:
                            ${
                                getPromptRequest.arguments.entries.joinToString(separator = "\n") { "${it.key}=${it.value}" }
                            }
                        """.trimIndent()
                        )
                    )
                ),
            )
        }
    }

    /**
     * Extracts MCP prompt arguments from a given type,
     * excluding fields that match methods in the excluded interfaces.
     */
    internal fun argumentsFromType(type: Class<*>): List<McpSchema.PromptArgument> {
        val excludedFields: Iterable<Method> = excludedInterfaces.flatMap {
            it.methods.toList()
        }
        val args = mutableListOf<McpSchema.PromptArgument>()
        ReflectionUtils.doWithFields(type) { field ->
            if (field.isSynthetic) {
                return@doWithFields
            }
            if (excludedFields.any { NameUtils.beanMethodToPropertyName(it.name) == field.name }) {
                return@doWithFields
            }
            val name = field.name
            val description = field.getAnnotation(JsonPropertyDescription::class.java)?.value ?: name
            val descriptionWithType = "$description: ${field.type.simpleName}"
            args.add(McpSchema.PromptArgument(name, descriptionWithType, true))
        }
        return args
    }
}
