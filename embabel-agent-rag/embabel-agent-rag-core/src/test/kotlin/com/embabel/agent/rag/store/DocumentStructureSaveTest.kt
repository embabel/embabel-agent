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
package com.embabel.agent.rag.store

import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.ingestion.RetrievableEnhancer
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentElement
import com.embabel.agent.rag.model.ContentRoot
import com.embabel.agent.rag.model.DefaultMaterializedContainerSection
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.model.SectionPlace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How [AbstractChunkingContentElementRepository.writeAndChunkDocument] hands a store a document's structure
 * (embabel-agent#2110): by default one [ContentElement] at a time to `save`, as it always has; to a store
 * that overrides the steps, the sections once and in reading order with their places, and the chunks once.
 */
class DocumentStructureSaveTest {

    /* A chapter holding a leaf and a nested container, then a closing leaf: level order and reading order differ. */
    private val document = MaterializedDocument(
        id = "doc",
        uri = "test://doc",
        title = "Doc",
        children = listOf(
            DefaultMaterializedContainerSection(
                id = "chapter", title = "Chapter", parentId = "doc",
                children = listOf(
                    LeafSection(id = "intro", title = "Intro", text = "The chapter opens here.", parentId = "chapter"),
                    DefaultMaterializedContainerSection(
                        id = "part", title = "Part", parentId = "chapter",
                        children = listOf(LeafSection(id = "detail", title = "Detail", text = "The detail of the part.", parentId = "part")),
                    ),
                ),
            ),
            LeafSection(id = "close", title = "Close", text = "The document closes here.", parentId = "doc"),
        ),
    )

    /** Records what the template method asks of it; [batched] overrides the two structure steps. */
    private class Recording(private val batched: Boolean) :
        AbstractChunkingContentElementRepository(ContentChunker.Config(), ChunkTransformer.NO_OP) {

        val saved = mutableListOf<String>()
        val structures = mutableListOf<List<SectionPlace>>()
        val chunkBatches = mutableListOf<List<Chunk>>()
        val retrieved = mutableListOf<Chunk>()

        override val name = "recording"
        override val enhancers: List<RetrievableEnhancer> = emptyList()

        override fun saveStructure(root: NavigableDocument, sections: List<SectionPlace>) =
            if (batched) structures += sections else super.saveStructure(root, sections)

        override fun saveChunks(chunks: List<Chunk>) =
            if (batched) chunkBatches += chunks else super.saveChunks(chunks)

        override fun save(element: ContentElement): ContentElement = element.also { saved += it.id }
        override fun onNewRetrievables(retrievables: List<Retrievable>) {
            retrieved += retrievables.filterIsInstance<Chunk>()
        }
        override fun createInternalRelationships(root: NavigableDocument) {}
        override fun commit() {}
        override fun findById(id: String): ContentElement? = null
        override fun findChunksForEntity(entityId: String): List<Chunk> = emptyList()
        override fun deleteRootAndDescendants(uri: String): DocumentDeletionResult? = null
        override fun findContentRootByUri(uri: String): ContentRoot? = null
        override fun findAllChunksById(chunkIds: List<String>): Iterable<Chunk> = emptyList()
        override fun <C : ContentElement> findAll(clazz: Class<C>): Iterable<C> = emptyList()
        override fun info(): ContentElementRepositoryInfo = object : ContentElementRepositoryInfo {
            override val documentCount = 0
            override val chunkCount = 0
            override val contentElementCount = 0
            override val hasEmbeddings = false
            override val isPersistent = false
        }
    }

    @Test
    fun `sections in reading order put each section before what it holds and that before its next sibling`() {
        val places = document.sectionsInReadingOrder()

        assertEquals(listOf("chapter", "intro", "part", "detail", "close"), places.map { it.section.id })
        assertEquals(listOf(0, 1, 2, 3, 4), places.map { it.ordinal })
        assertEquals(listOf(1, 2, 2, 3, 1), places.map { it.depth })
        /* descendants() is level order: the closing leaf comes before anything inside the chapter */
        assertEquals(listOf("chapter", "close", "intro", "part", "detail"), document.descendants().map { it.id })
    }

    @Test
    fun `a store that overrides nothing is asked to save each element alone, as before`() {
        val store = Recording(batched = false)

        val chunkIds = store.writeAndChunkDocument(document)

        assertEquals(listOf("doc", "chapter", "close", "intro", "part", "detail"), store.saved.take(6))
        assertEquals(chunkIds, store.saved.drop(6))
        assertEquals(chunkIds, store.retrieved.map { it.id })
    }

    @Test
    fun `a store that saves the structure itself gets every section once, placed, and every chunk once`() {
        val store = Recording(batched = true)

        val chunkIds = store.writeAndChunkDocument(document)

        assertTrue(store.saved.isEmpty(), "nothing should reach save one element at a time: ${store.saved}")
        assertEquals(listOf(document.sectionsInReadingOrder()), store.structures)
        assertEquals(listOf(chunkIds), store.chunkBatches.map { batch -> batch.map { it.id } })
        assertEquals(chunkIds, store.retrieved.map { it.id })
    }
}
