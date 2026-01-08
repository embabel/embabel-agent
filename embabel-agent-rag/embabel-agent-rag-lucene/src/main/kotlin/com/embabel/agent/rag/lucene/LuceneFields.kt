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

/**
 * Constants for Lucene document field names used in the RAG index.
 */
internal object LuceneFields {
    const val KEYWORDS_FIELD = "keywords"
    const val CONTENT_FIELD = "content"
    const val ID_FIELD = "id"
    const val EMBEDDING_FIELD = "embedding"
    const val ELEMENT_TYPE_FIELD = "_element_type"
    const val TITLE_FIELD = "title"
    const val URI_FIELD = "uri"
    const val PARENT_ID_FIELD = "parentId"
    const val TEXT_FIELD = "text"
    const val INGESTION_TIMESTAMP_FIELD = "ingestionTimestamp"

    // Element type values for deserialization
    const val TYPE_CHUNK = "Chunk"
    const val TYPE_LEAF_SECTION = "LeafSection"
    const val TYPE_CONTAINER_SECTION = "ContainerSection"
    const val TYPE_DOCUMENT = "Document"

    /** Fields that are reserved and should not be included in metadata */
    val RESERVED_FIELDS = setOf(
        ID_FIELD, CONTENT_FIELD, ELEMENT_TYPE_FIELD, TITLE_FIELD,
        URI_FIELD, PARENT_ID_FIELD, TEXT_FIELD, INGESTION_TIMESTAMP_FIELD,
        KEYWORDS_FIELD, EMBEDDING_FIELD
    )
}
