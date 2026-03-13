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

import java.net.URI
import org.slf4j.LoggerFactory
import org.springframework.util.AntPathMatcher

/**
 * A [ContentFetcher] that routes URIs to specific fetchers based on Ant-style pattern matching,
 * falling back to a default fetcher for unmatched URIs.
 *
 * Uses Spring's [AntPathMatcher] which supports `?`, `*`, and `**` wildcards.
 * Patterns are matched against the full URI string.
 *
 * @param default the fetcher to use when no route matches
 * @param routes list of (Ant-style pattern, fetcher) pairs checked in order
 */
class RoutingContentFetcher(
    private val default: ContentFetcher,
    private val routes: List<Pair<String, ContentFetcher>>,
) : ContentFetcher {

    /**
     * Java-friendly constructor accepting a Map of patterns to fetchers.
     */
    constructor(default: ContentFetcher, routes: Map<String, ContentFetcher>) :
        this(default, routes.map { (k, v) -> k to v })

    private val logger = LoggerFactory.getLogger(javaClass)
    private val pathMatcher = AntPathMatcher()

    override fun fetch(uri: URI): FetchResult {
        val uriString = uri.toString()
        val match = routes.firstOrNull { (pattern, _) -> pathMatcher.match(pattern, uriString) }
        val fetcher = match?.second ?: default
        if (match != null) {
            logger.debug("URI '{}' matched route '{}', using {}", uri, match.first, fetcher.javaClass.simpleName)
        }
        return fetcher.fetch(uri)
    }
}
