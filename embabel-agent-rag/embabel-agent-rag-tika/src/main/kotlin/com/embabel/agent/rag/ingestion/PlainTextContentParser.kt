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

import com.embabel.agent.rag.model.MaterializedDocument
import org.apache.tika.metadata.Metadata
import org.slf4j.Logger
import java.time.Instant
import java.util.*

/**
 * Parser for plain text content that creates a single section document.
 */
internal class PlainTextContentParser(
    private val logger: Logger?,
) : ContentFormatParser {

    override fun parse(content: String, metadata: Metadata, uri: String): MaterializedDocument {
        if (content.isBlank()) {
            return ContentFormatParserUtils.createEmptyContentRoot(metadata, uri)
        }

        val rootId = UUID.randomUUID().toString()
        val title = ContentFormatParserUtils.extractTitle(content.lines(), metadata) ?: "Document"
        val leafSection = ContentFormatParserUtils.createLeafSection(
            id = UUID.randomUUID().toString(),
            title = title,
            content = content.trim(),
            parentId = rootId,
            url = uri,
            metadata = metadata,
            rootId = rootId
        )

        return MaterializedDocument(
            id = rootId,
            uri = uri,
            title = title,
            ingestionTimestamp = Instant.now(),
            children = listOf(leafSection),
            metadata = ContentFormatParserUtils.extractMetadataMap(metadata)
        )
    }
}
