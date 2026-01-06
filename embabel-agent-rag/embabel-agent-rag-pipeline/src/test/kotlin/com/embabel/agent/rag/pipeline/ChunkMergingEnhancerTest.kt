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
package com.embabel.agent.rag.pipeline

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.service.RagRequest
import com.embabel.agent.rag.service.RagResponse
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import kotlin.test.assertEquals

class ChunkMergingEnhancerTest {

    @Nested
    inner class MergesSuccessiveChunks {

        @Test
        fun `test no chunks to merge`() {
            val response = RagResponse(
                request = RagRequest("test query"),
                service = "test",
                results = listOf(
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk1",
                            text = "First chunk",
                            metadata = mapOf("root_document_id" to "doc1", "sequence_number" to 1),
                            parentId = "doc1"
                        ),
                        score = 0.9
                    ),
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk3",
                            text = "Third chunk",
                            metadata = mapOf("root_document_id" to "doc1", "sequence_number" to 3),
                            parentId = "doc1"
                        ),
                        score = 0.8
                    )
                )
            )

            val enhanced = ChunkMergingEnhancer.enhance(response)
            assertEquals(2, enhanced.results.size)
        }

        @Test
        fun `test merge two successive chunks`() {
            val response = RagResponse(
                request = RagRequest("test query"),
                service = "test",
                results = listOf(
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk1",
                            text = "First chunk",
                            metadata = mapOf("root_document_id" to "doc1", "sequence_number" to 1),
                            parentId = "doc1"
                        ),
                        score = 0.9
                    ),
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk2",
                            text = "Second chunk",
                            metadata = mapOf("root_document_id" to "doc1", "sequence_number" to 2),
                            parentId = "doc1"
                        ),
                        score = 0.85
                    )
                )
            )

            val enhanced = ChunkMergingEnhancer.enhance(response)
            assertEquals(1, enhanced.results.size)
            val mergedChunk = enhanced.results[0].match as Chunk
            assertEquals("First chunk Second chunk", mergedChunk.text)
            assertEquals(0.9, enhanced.results[0].score)
        }

        @Test
        fun `test merge three successive chunks`() {
            val response = RagResponse(
                request = RagRequest("test query"),
                service = "test",
                results = listOf(
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk1",
                            text = "First chunk",
                            metadata = mapOf("root_document_id" to "doc1", "sequence_number" to 1),
                            parentId = "doc1"
                        ),
                        score = 0.9
                    ),
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk2",
                            text = "Second chunk",
                            metadata = mapOf("root_document_id" to "doc1", "sequence_number" to 2),
                            parentId = "doc1"
                        ),
                        score = 0.85
                    ),
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk3",
                            text = "Third chunk",
                            metadata = mapOf("root_document_id" to "doc1", "sequence_number" to 3),
                            parentId = "doc1"
                        ),
                        score = 0.8
                    )
                )
            )

            val enhanced = ChunkMergingEnhancer.enhance(response)
            assertEquals(1, enhanced.results.size)
            val mergedChunk = enhanced.results[0].match as Chunk
            assertEquals("First chunk Second chunk Third chunk", mergedChunk.text)
            assertEquals(0.9, enhanced.results[0].score)
        }

        @Test
        fun `test merge chunks from different documents separately`() {
            val response = RagResponse(
                request = RagRequest("test query"),
                service = "test",
                results = listOf(
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk1",
                            text = "Doc1 chunk1",
                            metadata = mapOf("root_document_id" to "doc1", "sequence_number" to 1),
                            parentId = "doc1"
                        ),
                        score = 0.9
                    ),
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk2",
                            text = "Doc1 chunk2",
                            metadata = mapOf("root_document_id" to "doc1", "sequence_number" to 2),
                            parentId = "doc1"
                        ),
                        score = 0.85
                    ),
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk3",
                            text = "Doc2 chunk1",
                            metadata = mapOf("root_document_id" to "doc2", "sequence_number" to 1),
                            parentId = "doc2"
                        ),
                        score = 0.8
                    ),
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk4",
                            text = "Doc2 chunk2",
                            metadata = mapOf("root_document_id" to "doc2", "sequence_number" to 2),
                            parentId = "doc2"
                        ),
                        score = 0.75
                    )
                )
            )

            val enhanced = ChunkMergingEnhancer.enhance(response)
            assertEquals(2, enhanced.results.size)
            val firstMergedChunk = enhanced.results[0].match as Chunk
            assertEquals("Doc1 chunk1 Doc1 chunk2", firstMergedChunk.text)
            val secondMergedChunk = enhanced.results[1].match as Chunk
            assertEquals("Doc2 chunk1 Doc2 chunk2", secondMergedChunk.text)
        }

        @Test
        fun `test handles missing metadata gracefully`() {
            val response = RagResponse(
                request = RagRequest("test query"),
                service = "test",
                results = listOf(
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk1",
                            text = "First chunk",
                            metadata = mapOf("root_document_id" to "doc1"),
                            parentId = "doc1"
                        ),
                        score = 0.9
                    ),
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk2",
                            text = "Second chunk",
                            metadata = mapOf("sequence_number" to 2),
                            parentId = "doc1"
                        ),
                        score = 0.85
                    )
                )
            )

            val enhanced = ChunkMergingEnhancer.enhance(response)
            assertEquals(2, enhanced.results.size)
        }

        @Test
        fun `test logging when merging occurs`() {
            val mockLogger = mockk<Logger>(relaxed = true)
            val response = RagResponse(
                request = RagRequest("test query"),
                service = "test",
                results = listOf(
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk1",
                            text = "First chunk",
                            metadata = mapOf("root_document_id" to "doc1", "sequence_number" to 1),
                            parentId = "doc1"
                        ),
                        score = 0.9
                    ),
                    SimpleSimilaritySearchResult(
                        match = Chunk(
                            id = "chunk2",
                            text = "Second chunk",
                            metadata = mapOf("root_document_id" to "doc1", "sequence_number" to 2),
                            parentId = "doc1"
                        ),
                        score = 0.85
                    )
                )
            )

            ChunkMergingEnhancer.logger = mockLogger
            ChunkMergingEnhancer.enhance(response)

            verify { mockLogger.info(any<String>(), any<Int>(), any<String>()) }
        }
    }
}
