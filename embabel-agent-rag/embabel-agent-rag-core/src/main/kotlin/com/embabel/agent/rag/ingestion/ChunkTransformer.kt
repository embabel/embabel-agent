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
package com.embabel.agent.rag.ingestion

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentRoot
import com.embabel.agent.rag.model.Section

data class ChunkTransformationContext(
    val section: Section,
    val document: ContentRoot?,
)

/**
 * Transforms a Chunk by modifying its text and/or adding additional metadata.
 * Abstract class rather than an interface to preserve correct workflow
 */
interface ChunkTransformer {

    val name: String get() = this::class.java.simpleName

    /**
     * Transforms the given chunk by applying additional metadata and modifying its text.
     * @param chunk The chunk to transform.
     * @param context The context for the transformation, including the section and document.
     * @return The transformed chunk.
     */
    fun transform(
        chunk: Chunk,
        context: ChunkTransformationContext,
    ): Chunk

    companion object {

        @JvmField
        val NO_OP: ChunkTransformer = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk = chunk
        }
    }
}

/**
 * Convenient base implementation of ChunkTransformer.
 * that preserves workflow.
 */
abstract class AbstractChunkTransformer : ChunkTransformer {

    /**
     * Transforms the given chunk by applying additional metadata and modifying its text.
     */
    override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk {
        return chunk
            .withAdditionalMetadata(additionalMetadata(chunk, context))
            .withText(newText(chunk, context))
            .withAdditionalMetadata(mapOf("transform_${name}" to true))
    }

    open fun additionalMetadata(chunk: Chunk, context: ChunkTransformationContext): Map<String, Any> = emptyMap()

    open fun newText(chunk: Chunk, context: ChunkTransformationContext): String = chunk.text
}
