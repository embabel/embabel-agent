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

/**
 * Strategy for resolving an article URL to its RSS feed URL.
 */
fun interface FeedResolver {

    /**
     * Given an article URI, return the RSS feed URL that contains it.
     */
    fun resolve(articleUri: URI): String
}

/**
 * [ContentFetcher] that retrieves article content from RSS feeds.
 *
 * Delegates HTTP fetching to [delegate] and RSS parsing to [RssContentMapper],
 * cleanly separating transport from format concerns.
 *
 * Works with any site that publishes full content in RSS `content:encoded` or
 * `description` elements — including Medium, Substack, WordPress, Ghost, etc.
 *
 * @param feedResolver strategy to map an article URL to its RSS feed URL
 * @param delegate the fetcher used to retrieve the RSS feed over HTTP
 */
class RssContentFetcher(
    private val feedResolver: FeedResolver,
    private val delegate: ContentFetcher,
) : ContentFetcher {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val rssMapper = RssContentMapper()

    override fun fetch(uri: URI): FetchResult {
        val feedUrl = feedResolver.resolve(uri)
        logger.info("Fetching RSS feed: {} (for article: {})", feedUrl, uri)
        val feedResult = delegate.fetch(URI(feedUrl))
        val articleHtml = rssMapper.map(feedResult.content, uri)
        return FetchResult(
            content = articleHtml,
            contentType = "text/html",
            charset = Charsets.UTF_8,
        )
    }

    companion object {

        /**
         * Create a [FeedResolver] from a URL template.
         * Placeholders `{0}`, `{1}`, etc. are replaced with path segments from the article URL.
         */
        @JvmStatic
        fun templateResolver(template: String) = FeedResolver { articleUri ->
            val segments = articleUri.path.trimStart('/').split("/")
            var result = template
            segments.forEachIndexed { index, segment ->
                result = result.replace("{$index}", segment)
            }
            result
        }
    }
}
