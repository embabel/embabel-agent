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

import com.embabel.agent.rag.service.support.VectorMath
import com.embabel.agent.rag.model.*
import com.embabel.common.util.loggerFor
import org.apache.lucene.document.*
import org.apache.lucene.index.VectorSimilarityFunction

/**
 * Handles conversion between Lucene Documents and ContentElement types.
 */
internal object LuceneDocumentMapper {

    private val logger = loggerFor<LuceneDocumentMapper>()

    /**
     * Create a Chunk from a Lucene document.
     */
    fun createChunkFromLuceneDocument(luceneDocument: Document): Chunk {
        val keywords = luceneDocument.getValues(LuceneFields.KEYWORDS_FIELD)?.toList() ?: emptyList()

        val metadata = luceneDocument.fields
            .filter { field ->
                field.name() !in setOf(
                    LuceneFields.ID_FIELD,
                    LuceneFields.CONTENT_FIELD,
                    LuceneFields.EMBEDDING_FIELD,
                    LuceneFields.KEYWORDS_FIELD
                )
            }
            .associate { field -> field.name() to field.stringValue() as Any? }
            .toMutableMap()

        if (keywords.isNotEmpty()) {
            metadata[LuceneFields.KEYWORDS_FIELD] = keywords as Any?
        }

        return Chunk(
            id = luceneDocument.get(LuceneFields.ID_FIELD),
            text = luceneDocument.get(LuceneFields.CONTENT_FIELD),
            parentId = luceneDocument.get(LuceneFields.ID_FIELD),
            metadata = metadata,
        )
    }

    /**
     * Create a Lucene document from a Chunk with optional embedding.
     */
    fun createLuceneDocument(chunk: Chunk, embedding: FloatArray?): Document {
        val keywords = when (val keywordsMeta = chunk.metadata[LuceneFields.KEYWORDS_FIELD]) {
            is Collection<*> -> keywordsMeta.filterIsInstance<String>()
            is String -> listOf(keywordsMeta)
            else -> emptyList()
        }

        return Document().apply {
            add(StringField(LuceneFields.ID_FIELD, chunk.id, Field.Store.YES))
            add(TextField(LuceneFields.CONTENT_FIELD, chunk.embeddableValue(), Field.Store.YES))

            keywords.forEach { keyword ->
                add(TextField(LuceneFields.KEYWORDS_FIELD, keyword.lowercase(), Field.Store.YES))
            }

            if (embedding != null) {
                add(KnnFloatVectorField(LuceneFields.EMBEDDING_FIELD, embedding, VectorSimilarityFunction.COSINE))
                add(StoredField(LuceneFields.EMBEDDING_FIELD, VectorMath.floatArrayToBytes(embedding)))
            }

            chunk.metadata.forEach { (key, value) ->
                if (key != LuceneFields.KEYWORDS_FIELD) {
                    add(StringField(key, value.toString(), Field.Store.YES))
                }
            }
        }
    }

    /**
     * Create the appropriate ContentElement type from a Lucene document based on stored type.
     */
    fun createContentElementFromLuceneDocument(
        luceneDocument: Document,
        elementType: String?,
    ): ContentElement? {
        val id = luceneDocument.get(LuceneFields.ID_FIELD) ?: return null

        val metadata = luceneDocument.fields
            .filter { field -> field.name() !in LuceneFields.RESERVED_FIELDS }
            .associate { field -> field.name() to (field.stringValue() as Any?) }

        return when (elementType) {
            LuceneFields.TYPE_DOCUMENT -> {
                MaterializedDocument(
                    id = id,
                    uri = luceneDocument.get(LuceneFields.URI_FIELD) ?: "",
                    title = luceneDocument.get(LuceneFields.TITLE_FIELD) ?: "",
                    ingestionTimestamp = luceneDocument.get(LuceneFields.INGESTION_TIMESTAMP_FIELD)?.let {
                        java.time.Instant.parse(it)
                    } ?: java.time.Instant.now(),
                    children = emptyList(),
                    metadata = metadata
                )
            }

            LuceneFields.TYPE_LEAF_SECTION -> {
                LeafSection(
                    id = id,
                    title = luceneDocument.get(LuceneFields.TITLE_FIELD) ?: "",
                    text = luceneDocument.get(LuceneFields.TEXT_FIELD) ?: "",
                    parentId = luceneDocument.get(LuceneFields.PARENT_ID_FIELD),
                    metadata = metadata
                )
            }

            LuceneFields.TYPE_CONTAINER_SECTION -> {
                DefaultMaterializedContainerSection(
                    id = id,
                    title = luceneDocument.get(LuceneFields.TITLE_FIELD) ?: "",
                    children = emptyList(),
                    parentId = luceneDocument.get(LuceneFields.PARENT_ID_FIELD),
                    metadata = metadata
                )
            }

            null, LuceneFields.TYPE_CHUNK -> {
                createChunkFromLuceneDocument(luceneDocument)
            }

            else -> {
                logger.warn("Unknown element type '{}' for id='{}', treating as Chunk", elementType, id)
                createChunkFromLuceneDocument(luceneDocument)
            }
        }
    }

    /**
     * Create a Lucene document for a structural element (Document, Section, etc.).
     */
    fun createStructuralElementDocument(element: ContentElement): Document {
        return Document().apply {
            add(StringField(LuceneFields.ID_FIELD, element.id, Field.Store.YES))

            val elementType = when (element) {
                is NavigableDocument -> LuceneFields.TYPE_DOCUMENT
                is LeafSection -> LuceneFields.TYPE_LEAF_SECTION
                is ContainerSection -> LuceneFields.TYPE_CONTAINER_SECTION
                else -> element.javaClass.simpleName
            }
            add(StringField(LuceneFields.ELEMENT_TYPE_FIELD, elementType, Field.Store.YES))

            if (element is HierarchicalContentElement) {
                element.parentId?.let { add(StoredField(LuceneFields.PARENT_ID_FIELD, it)) }
            }

            when (element) {
                is Section -> add(StoredField(LuceneFields.TITLE_FIELD, element.title))
                is ContentRoot -> add(StoredField(LuceneFields.TITLE_FIELD, element.title))
            }

            if (element is ContentRoot) {
                add(StoredField(LuceneFields.URI_FIELD, element.uri))
                add(StoredField(LuceneFields.INGESTION_TIMESTAMP_FIELD, element.ingestionTimestamp.toString()))
            }

            if (element is LeafSection) {
                add(StoredField(LuceneFields.TEXT_FIELD, element.text))
            }

            element.metadata.forEach { (key, value) ->
                if (value != null) {
                    add(StringField(key, value.toString(), Field.Store.YES))
                }
            }
        }
    }
}
