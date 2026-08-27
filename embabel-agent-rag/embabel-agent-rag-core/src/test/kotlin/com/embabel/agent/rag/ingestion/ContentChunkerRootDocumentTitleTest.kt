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

import com.embabel.agent.rag.model.ChunkStructure
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Every chunk must carry the title of the document it came from.
 *
 * `readSection` refuses to concatenate a section title that matches more than one document,
 * and the only way it can tell one document from another is this field. A chunker that stops
 * writing it turns that guard into a no-op — the model is handed two contracts' clauses as one
 * section, with nothing in the output to say so.
 */
class ContentChunkerRootDocumentTitleTest {

    private val chunker = ContentChunker(ContentChunker.Config(), ChunkTransformer.NO_OP)

    private fun document(title: String, sections: Int, textLength: Int): MaterializedDocument {
        val rootId = UUID.randomUUID().toString()
        return MaterializedDocument(
            id = rootId,
            uri = "test://doc",
            title = title,
            children = (1..sections).map {
                LeafSection(
                    id = UUID.randomUUID().toString(),
                    uri = "test://doc#$it",
                    title = "Section $it",
                    text = "word ".repeat(textLength),
                    parentId = rootId,
                )
            },
        )
    }

    @Test
    fun `a document small enough for one chunk carries its title`() {
        val chunks = chunker.chunk(document("Acme License", sections = 1, textLength = 5)).toList()

        assertTrue(chunks.isNotEmpty())
        chunks.forEach {
            assertEquals("Acme License", it.structure.rootDocumentTitle, "chunk ${it.id}")
        }
    }

    @Test
    fun `a document split across many chunks carries its title on every one`() {
        // Large enough to exercise leaf grouping and leaf splitting, not just the single-chunk path.
        val chunks = chunker.chunk(document("Widget License", sections = 12, textLength = 400)).toList()

        assertTrue(chunks.size > 1, "expected a split document, got ${chunks.size} chunk(s)")
        chunks.forEach {
            assertEquals("Widget License", it.structure.rootDocumentTitle, "chunk ${it.id}")
        }
    }

    @Test
    fun `the title is readable through the metadata compatibility view`() {
        val chunk = chunker.chunk(document("Acme License", sections = 1, textLength = 5)).first()

        assertEquals("Acme License", chunk.metadata[ChunkStructure.ROOT_DOCUMENT_TITLE])
    }

    @Test
    fun `two documents sharing a section name stay distinguishable`() {
        val acme = chunker.chunk(document("Acme License", sections = 1, textLength = 5)).toList()
        val widget = chunker.chunk(document("Widget License", sections = 1, textLength = 5)).toList()

        val titles = (acme + widget).map { it.structure.rootDocumentTitle }.distinct()
        assertEquals(listOf("Acme License", "Widget License"), titles)
    }
}
