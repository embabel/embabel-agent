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

import java.io.InputStream
import org.slf4j.LoggerFactory

/**
 * A [ContentFetcher] that routes URLs to specific fetchers based on pattern matching,
 * falling back to a default fetcher for unmatched URLs.
 *
 * Example usage:
 * ```
 * RoutingContentFetcher(
 *     default = HttpContentFetcher(),
 *     routes = listOf(
 *         "medium.com" to seleniumFetcher,
 *         "substack.com" to seleniumFetcher,
 *     )
 * )
 * ```
 *
 * @param default the fetcher to use when no route matches
 * @param routes list of (substring pattern, fetcher) pairs checked in order
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

    override fun <T> fetch(url: String, mapper: (InputStream) -> T): FetchResult<T> {
        val match = routes.firstOrNull { (pattern, _) -> url.contains(pattern) }
        val fetcher = match?.second ?: default
        if (match != null) {
            logger.debug("URL '{}' matched route '{}', using {}", url, match.first, fetcher.javaClass.simpleName)
        }
        return fetcher.fetch(url, mapper)
    }
}
