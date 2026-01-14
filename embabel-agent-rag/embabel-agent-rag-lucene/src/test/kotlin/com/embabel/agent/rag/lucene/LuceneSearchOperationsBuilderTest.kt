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
package com.embabel.agent.rag.lucene

import com.embabel.agent.rag.ingestion.AbstractChunkTransformer
import com.embabel.agent.rag.ingestion.ChunkTransformationContext
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for LuceneSearchOperationsBuilder.
 */
class LuceneSearchOperationsBuilderTest {

    private var luceneSearchOperations: LuceneSearchOperations? = null

    @AfterEach
    fun tearDown() {
        luceneSearchOperations?.close()
    }

    /**
     * A test transformer that prepends a marker to chunk text.
     */
    class TestChunkTransformer : AbstractChunkTransformer() {
        companion object {
            const val MARKER = "[TRANSFORMED]"
        }

        override fun newText(chunk: Chunk, context: ChunkTransformationContext): String {
            return "$MARKER ${chunk.text}"
        }
    }

    @Test
    fun `builder should pass chunkTransformer to LuceneSearchOperations`() {
        // Build using the builder with a custom chunk transformer
        luceneSearchOperations = LuceneSearchOperations
            .withName("test-rag")
            .withChunkTransformer(TestChunkTransformer())
            .build()

        // Create a simple document to chunk
        val leafSection = LeafSection(
            id = "leaf-1",
            title = "Test Section",
            text = "This is test content that should be transformed.",
            uri = "test://section",
            parentId = "doc-1",
        )

        val document = MaterializedDocument(
            id = "doc-1",
            title = "Test Document",
            uri = "test://document",
            children = listOf(leafSection),
        )

        // Write and chunk the document - this should apply the transformer
        luceneSearchOperations!!.writeAndChunkDocument(document)

        // Retrieve the chunks and verify they were transformed
        val chunks = luceneSearchOperations!!.findAll(Chunk::class.java)

        assertTrue(chunks.isNotEmpty(), "Should have created at least one chunk")

        // Verify that the transformer was applied - chunk text should start with the marker
        val transformedChunk = chunks.first()
        assertTrue(
            transformedChunk.text.contains(TestChunkTransformer.MARKER),
            "Chunk text should contain transformer marker. Actual text: '${transformedChunk.text}'"
        )
    }
}
