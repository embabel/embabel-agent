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
package com.embabel.agent.mcpserver.sync

import com.embabel.agent.mcpserver.support.argumentsFromType
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import com.embabel.common.core.types.Timestamped
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema

/**
 * Create Prompt specifications for the MCP server.
 *
 * @param excludedInterfaces Set of interfaces whose fields should be excluded from the prompt arguments.
 */
class McpPromptFactory(
    val excludedInterfaces: Set<Class<*>> = setOf(
        Timestamped::class.java,
    ),
) {

    /**
     * Creates a synchronous prompt specification for a given type.
     *
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
                argumentsFromType(excludedInterfaces, inputType),
            )
        ) { syncServerExchange, getPromptRequest ->
            McpSchema.GetPromptResult(
                "$name-result",
                listOf(
                    McpSchema.PromptMessage(
                        McpSchema.Role.USER,
                        McpSchema.TextContent(
                            """
                            Use the following information to achieve goal $name" - <$description>:
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

}
