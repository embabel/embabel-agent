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
package com.embabel.agent.rag.model

import com.embabel.common.util.indent
import java.util.*

/**
 * Traditional RAG. Text chunk.
 */
interface Chunk : Source, HierarchicalContentElement {

    /**
     * Text content
     */
    val text: String

    /**
     * Parent must be non-null. It must a physical content element.
     */
    override val parentId: String

    /**
     * If available, this is the path from the root document as ids,
     * with the root id first and this element's id as the last element.
     *
     * Default implementation computes from metadata for backward compatibility.
     * For a more robust solution that traverses actual parentId relationships,
     * use ContentElementRepository.pathFromRoot(element) instead.
     *
     * Return null if not available or any part of the path is missing.
     */
    val pathFromRoot: List<String>?
        get() {
            // Get root document ID from metadata
            val rootId = metadata["root_document_id"] as? String ?: return null
            val containerId = metadata["container_section_id"] as? String
            val leafId = metadata["leaf_section_id"] as? String

            // Build path: root -> container -> leaf (if exists) -> chunk
            val path = mutableListOf<String>()
            path.add(rootId)

            // Only add container if it's different from root
            if (containerId != null && containerId != rootId) {
                path.add(containerId)
            }

            // Add leaf section if it exists and is different from container
            if (leafId != null && leafId != containerId) {
                path.add(leafId)
            }

            // Add chunk itself if it's not already in the path
            if (id != path.lastOrNull()) {
                path.add(id)
            }

            return path
        }


    override val uri: String? get() = metadata["url"] as? String

    override fun embeddableValue(): String = text

    override fun propertiesToPersist(): Map<String, Any?> {
        return super<HierarchicalContentElement>.propertiesToPersist() + mapOf(
            "text" to text,
        )
    }

    override fun labels(): Set<String> {
        return super<Source>.labels() + super<HierarchicalContentElement>.labels() + setOf("Chunk")
    }

    /**
     * Transform the content of this chunk
     */
    fun transform(transformed: String): Chunk =
        ChunkImpl(
            id = this.id,
            text = transformed,
            metadata = this.metadata,
            parentId = this.parentId,
        )

    companion object {

        operator fun invoke(
            id: String,
            text: String,
            metadata: Map<String, Any?>,
            parentId: String,
        ): Chunk {
            return ChunkImpl(
                id = id,
                text = text,
                metadata = metadata,
                parentId = parentId,
            )
        }

        @JvmOverloads
        @JvmStatic
        fun create(
            text: String,
            parentId: String,
            metadata: Map<String, Any?> = emptyMap(),
            id: String = UUID.randomUUID().toString(),
        ): Chunk {
            return ChunkImpl(
                id = id,
                text = text,
                metadata = metadata,
                parentId = parentId,
            )
        }

    }

    /**
     * Return the chunk with additional metadata.
     * May be a new instance or the same instance,
     * but must have the same id and text.
     * All existing metadata will be replaced:
     * callers are responsible for ensuring that any
     * they want to keep is preserved.
     */
    fun withMetadata(metadata: Map<String, Any?>): Chunk

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "chunk: $text".indent(indent)
}

private data class ChunkImpl(
    override val id: String,
    override val text: String,
    override val parentId: String,
    override val metadata: Map<String, Any?>,
) : Chunk {

    override fun withMetadata(metadata: Map<String, Any?>): Chunk =
        this.copy(metadata = metadata)
}
