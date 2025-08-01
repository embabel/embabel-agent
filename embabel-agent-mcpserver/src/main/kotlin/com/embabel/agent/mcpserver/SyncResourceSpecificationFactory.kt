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
package com.embabel.agent.mcpserver

import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema

/**
 * Convenience factory for creating [McpServerFeatures.SyncResourceSpecification] instances.
 */
object SyncResourceSpecificationFactory {

    @JvmStatic
    fun staticSyncResourceSpecification(
        uri: String,
        name: String,
        description: String,
        content: String,
        mimeType: String = "text/plain",
    ): McpServerFeatures.SyncResourceSpecification = syncResourceSpecification(
        uri, name, description, { content }, mimeType,
    )

    @JvmStatic
    fun syncResourceSpecification(
        uri: String,
        name: String,
        description: String,
        resourceLoader: (exchange: McpSyncServerExchange) -> String,
        mimeType: String = "text/plain",
    ): McpServerFeatures.SyncResourceSpecification {

        return McpServerFeatures.SyncResourceSpecification(
            McpSchema.Resource(
                uri,
                name,
                description,
                mimeType,
                McpSchema.Annotations(
                    listOf(McpSchema.Role.ASSISTANT),
                    1.0,
                )
            )
        ) { exchange, readResourceRequest ->
            McpSchema.ReadResourceResult(
                listOf(
                    McpSchema.TextResourceContents(
                        uri, mimeType, resourceLoader(exchange),
                    )
                )
            )
        }
    }

}
