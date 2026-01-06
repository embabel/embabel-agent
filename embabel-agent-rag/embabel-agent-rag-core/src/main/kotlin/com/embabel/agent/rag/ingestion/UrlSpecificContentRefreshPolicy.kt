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
import org.slf4j.LoggerFactory

/**
 * ContentRefreshPolicy that uses a custom function to determine
 * whether to refresh content based on the URI.
 * @param shouldRefreshUri function that returns true if the URI should be refreshed
 */
class UrlSpecificContentRefreshPolicy(
    private val shouldRefreshUri: (String) -> Boolean,
) : ContentRefreshPolicy {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun shouldReread(
        repository: ChunkingContentElementRepository,
        rootUri: String,
    ): Boolean {
        val shouldReread = !repository.existsRootWithUri(rootUri) || shouldRefreshUri(rootUri)
        logger.info(
            "Checking whether to reread content at uri={} : existsInRepository={}, shouldRefreshUri={} => shouldReread={}",
            rootUri,
            repository.existsRootWithUri(rootUri),
            shouldRefreshUri(rootUri),
            shouldReread,
        )
        return shouldReread
    }

    override fun shouldRefreshDocument(
        repository: ChunkingContentElementRepository,
        root: NavigableDocument,
    ): Boolean = true

    companion object {

        /**
         * Create a policy that refreshes URIs matching the given regex pattern.
         */
        @JvmStatic
        fun matchingPattern(pattern: Regex): UrlSpecificContentRefreshPolicy =
            UrlSpecificContentRefreshPolicy { uri -> pattern.containsMatchIn(uri) }

        /**
         * Create a policy that refreshes URIs containing any of the given substrings.
         */
        @JvmStatic
        fun containingAny(vararg substrings: String): UrlSpecificContentRefreshPolicy =
            UrlSpecificContentRefreshPolicy { uri -> substrings.any { uri.contains(it) } }
    }
}
