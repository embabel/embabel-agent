/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.rag.store.ChunkingContentElementRepository

/**
 * Policy to determine whether content should be refreshed
 * based on the state of the repository and the root uri or root document.
 */
interface ContentRefreshPolicy {

    /**
     * Should we reread the document at the given rootUri
     */
    fun shouldReread(
        repository: ChunkingContentElementRepository,
        rootUri: String,
    ): Boolean

    /**
     * Should we refresh the given document we've read
     */
    fun shouldRefreshDocument(
        repository: ChunkingContentElementRepository,
        root: NavigableDocument,
    ): Boolean

    fun ingestUriIfNeeded(
        repository: ChunkingContentElementRepository,
        hierarchicalContentReader: HierarchicalContentReader,
        rootUri: String,
        ingestDocument: (NavigableDocument) -> Unit,
    ) {
        if (shouldReread(repository, rootUri)) {
            val document = hierarchicalContentReader.parseUrl(rootUri)
            if (shouldRefreshDocument(repository, document)) {
                ingestDocument(document)
            }
        }
    }

}

/**
 * Never refresh an existing document
 */
object NeverRefreshExistingDocumentContentPolicy : ContentRefreshPolicy {

    override fun shouldReread(
        repository: ChunkingContentElementRepository,
        rootUri: String,
    ): Boolean = !repository.existsRootWithUri(rootUri)

    override fun shouldRefreshDocument(
        repository: ChunkingContentElementRepository,
        root: NavigableDocument,
    ): Boolean = false
}
