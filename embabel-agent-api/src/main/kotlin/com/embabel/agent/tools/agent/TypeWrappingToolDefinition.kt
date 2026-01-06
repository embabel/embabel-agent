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
package com.embabel.agent.tools.agent

import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.util.json.schema.JsonSchemaGenerator

/**
 * Tool definition that wraps an input type, generating a JSON schema for it.
 */
data class TypeWrappingToolDefinition(
    private val name: String,
    private val description: String,
    private val type: Class<*>,
) : ToolDefinition {

    override fun name(): String = name
    override fun description(): String = description

    override fun inputSchema(): String = JsonSchemaGenerator.generateForType(type)
}
