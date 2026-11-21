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

import com.embabel.agent.rag.ingestion.DirectoryParsingConfig
import com.embabel.agent.rag.ingestion.DirectoryParsingResult
import com.embabel.agent.rag.ingestion.HierarchicalContentReader
import com.embabel.agent.rag.ingestion.policy.NeverRefreshExistingDocumentContentPolicy
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.model.NavigableDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import com.embabel.agent.tools.file.FileReadTools
import java.io.File
import java.io.InputStream
import java.nio.file.Path

class LuceneColdStartTest {

    @TempDir
    lateinit var indexPath: Path

    private val document = MaterializedDocument(
        id = "document", uri = "test://cold-start", title = "Original",
        children = listOf(LeafSection(id = "section", title = "Section", text = "Searchable content", parentId = "document")),
    )
    private lateinit var chunkIds: List<String>
    private var elementCount = 0

    private fun store() = LuceneSearchOperations(name = "cold-start", indexPath = indexPath)

    @BeforeEach
    fun writeIndex() {
        store().use { repository ->
            chunkIds = repository.writeAndChunkDocument(document)
            elementCount = repository.info().contentElementCount
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["id", "chunks", "uri", "exists"])
    fun `point lookups load persisted content on first access`(lookup: String) {
        store().use { repository ->
            when (lookup) {
                "id" -> {
                    // A cold id lookup finds the persisted document.
                    assertEquals(document.id, repository.findById(document.id)?.id)
                }
                "chunks" -> {
                    // A cold chunk lookup finds every requested chunk.
                    assertEquals(chunkIds, repository.findAllChunksById(chunkIds).map { it.id })
                }
                "uri" -> {
                    // A cold URI lookup finds the persisted root.
                    assertEquals(document.id, repository.findContentRootByUri(document.uri)?.id)
                }
                "exists" -> {
                    // Existence checks do not depend on a prior search.
                    assertTrue(repository.existsRootWithUri(document.uri))
                }
            }
        }
    }

    @Test
    fun `ingest if needed skips a document persisted before the restart`() {
        // Returns the same document the index already holds, as rereading an unchanged file would.
        val reader = object : HierarchicalContentReader {
            override fun parseResource(resourcePath: String): NavigableDocument = document
            override fun parseFile(file: File, url: String?): NavigableDocument = error("not used")
            override fun parseContent(inputStream: InputStream, uri: String): NavigableDocument = error("not used")
            override fun parseFromDirectory(fileTools: FileReadTools, config: DirectoryParsingConfig): DirectoryParsingResult =
                error("not used")
        }
        store().use { repository ->
            // The document from before the restart counts as existing, so nothing is ingested.
            assertNull(NeverRefreshExistingDocumentContentPolicy.ingestUriIfNeeded(repository, reader, document.uri))
            // The index still holds one set of chunks, not a second copy.
            assertEquals(chunkIds.size, repository.findAll(Chunk::class.java).size)
        }
    }

    @Test
    fun `delete loads persisted roots on first access`() {
        store().use { repository ->
            // A cold delete recognizes an existing root instead of returning null.
            assertNotNull(repository.deleteRootAndDescendants(document.uri))
        }
        store().use { repository ->
            // The root deletion is durable across another restart.
            assertTrue(repository.findAll(NavigableDocument::class.java).isEmpty())
        }
    }

    @Test
    fun `keyword updates load persisted chunks on first access`() {
        store().use { repository ->
            repository.updateKeywords(mapOf(chunkIds.single() to listOf("topic")))
            // A cold keyword update indexes the existing chunk instead of skipping it.
            assertEquals(chunkIds, repository.findChunkIdsByKeywords(setOf("topic")).map { it.first })
        }
    }

    @Test
    fun `reembedding loads persisted chunks on first access`() {
        store().use { repository ->
            // The retry API finds the persisted chunks without a warm-up search.
            assertEquals(chunkIds, repository.reembedChunks(chunkIds))
        }
    }

    @Test
    fun `saving before first read does not reload an older copy`() {
        store().use { repository ->
            repository.save(document.copy(title = "Updated"))
            // Loading other elements must not overwrite a just-saved document in memory.
            assertEquals("Updated", repository.findAll(NavigableDocument::class.java).single().title)
        }
    }

    @Test
    fun `clear reports the persisted content count on first access`() {
        store().use { repository ->
            // The returned count includes elements that had not yet been loaded.
            assertEquals(elementCount, repository.clear())
            // Cleared chunks remain absent when the memory cache is initialized.
            assertTrue(repository.findAll(Chunk::class.java).isEmpty())
        }
    }
}
