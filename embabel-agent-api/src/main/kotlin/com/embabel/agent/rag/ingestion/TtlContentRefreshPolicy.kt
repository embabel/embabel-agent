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
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * ContentRefreshPolicy that refreshes content based on a time-to-live (TTL).
 */
class TtlContentRefreshPolicy(
    private val ttl: Duration,
) : ContentRefreshPolicy {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun shouldReread(
        repository: ChunkingContentElementRepository,
        rootUri: String,
    ): Boolean {
        val existing = repository.findContentRootByUri(rootUri)
        return existing == null ||
                run {
                    logger.info(
                        "Checking whether to reread existing content at uri={} with ingestionTimestamp={} and ttl={}",
                        rootUri,
                        existing.ingestionTimestamp,
                        ttl,
                    )
                    Duration.between(existing.ingestionTimestamp, Instant.now()) > ttl
                }
    }

    override fun shouldRefreshDocument(
        repository: ChunkingContentElementRepository,
        root: NavigableDocument,
    ): Boolean = true

    companion object {

        @JvmStatic
        fun of(ttl: Duration): ContentRefreshPolicy = TtlContentRefreshPolicy(ttl)
    }
}
