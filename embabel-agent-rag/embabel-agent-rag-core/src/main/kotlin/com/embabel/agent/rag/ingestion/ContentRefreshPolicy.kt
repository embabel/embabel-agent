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

import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.rag.store.ChunkingContentElementRepository
import com.embabel.common.util.loggerFor

/**
 * Policy to determine whether content should be refreshed
 * based on the state of the repository and the root uri or root document.
 */
interface ContentRefreshPolicy {

    /**
     * Should we reread the document at the given rootUri?
     */
    fun shouldReread(
        repository: ChunkingContentElementRepository,
        rootUri: String,
    ): Boolean

    /**
     * Should we refresh the given document we've read?
     */
    fun shouldRefreshDocument(
        repository: ChunkingContentElementRepository,
        root: NavigableDocument,
    ): Boolean

    /**
     * Ingest the document at the given rootUri if needed according to the policy.
     * @param repository the repository to check and write to
     * @param hierarchicalContentReader the reader to parse the document
     * @param rootUri the uri of the document to ingest
     * @return the ingested document if it was ingested, or null if no ingestion
     */
    fun ingestUriIfNeeded(
        repository: ChunkingContentElementRepository,
        hierarchicalContentReader: HierarchicalContentReader,
        rootUri: String,
    ): NavigableDocument? {
        if (shouldReread(repository, rootUri)) {
            val document = hierarchicalContentReader.parseUrl(rootUri)
            if (shouldRefreshDocument(repository, document)) {
                if (repository.existsRootWithUri(rootUri)) {
                    loggerFor<ContentRefreshPolicy>().info(
                        "Refreshing EXISTING document at uri {}: Deleting old content",
                        rootUri,
                    )
                    repository.deleteRootAndDescendants(rootUri)
                } else {
                    loggerFor<ContentRefreshPolicy>().info(
                        "Ingesting NEW document at uri {}",
                        rootUri,
                    )
                }
                repository.writeAndChunkDocument(document)
                return document
            }
        }
        return null
    }

}
