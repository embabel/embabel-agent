package com.embabel.agent.mcpserver.sync

import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema

/**
 * Convenience factory for creating [io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification] instances.
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