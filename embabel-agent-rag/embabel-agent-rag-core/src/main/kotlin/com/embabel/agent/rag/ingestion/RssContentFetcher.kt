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

import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Strategy for resolving an article URL to its RSS feed URL.
 */
fun interface FeedResolver {

    /**
     * Given an article URL, return the RSS feed URL that contains it.
     */
    fun resolve(articleUrl: String): String
}

/**
 * [ContentFetcher] that retrieves article content from RSS feeds.
 *
 * Works with any site that publishes full content in RSS `<content:encoded>` or
 * `<description>` elements — including Medium, Substack, WordPress, Ghost, etc.
 *
 * @param feedResolver strategy to map an article URL to its RSS feed URL
 * @param connectTimeout connection timeout in milliseconds
 * @param readTimeout read timeout in milliseconds
 */
class RssContentFetcher @JvmOverloads constructor(
    private val feedResolver: FeedResolver,
    private val connectTimeout: Int = 15_000,
    private val readTimeout: Int = 15_000,
) : ContentFetcher {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun fetch(url: String): FetchResult {
        val feedUrl = feedResolver.resolve(url)
        logger.info("Fetching RSS feed: {} (for article: {})", feedUrl, url)

        val feedXml = fetchFeed(feedUrl)
        val content = extractArticleContent(feedXml, url)
            ?: throw IOException("Article not found in RSS feed $feedUrl for URL: $url")

        logger.info("Extracted {} chars of article content from RSS", content.length)

        return FetchResult(
            inputStream = ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)),
            contentType = "text/html",
            charset = "UTF-8",
        )
    }

    private fun fetchFeed(feedUrl: String): String {
        val connection = URI(feedUrl).toURL().openConnection()
        connection.connectTimeout = connectTimeout
        connection.readTimeout = readTimeout
        return connection.getInputStream().bufferedReader().use { it.readText() }
    }

    private fun extractArticleContent(feedXml: String, articleUrl: String): String? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(feedXml.toByteArray(StandardCharsets.UTF_8)))

        val items: NodeList = doc.getElementsByTagName("item")

        // Extract the slug (last path segment) for fuzzy matching
        val articleSlug = URI(articleUrl).path.trimEnd('/').substringAfterLast('/')

        for (i in 0 until items.length) {
            val item = items.item(i) as Element

            val link = item.getElementsByTagName("link").item(0)?.textContent.orEmpty()
            val guid = item.getElementsByTagName("guid").item(0)?.textContent.orEmpty()

            if (link.contains(articleSlug) || guid.contains(articleSlug)) {
                val title = item.getElementsByTagName("title").item(0)?.textContent ?: "Untitled"

                // Prefer content:encoded (full HTML), fall back to description
                val html = getContentEncoded(item)
                    ?: item.getElementsByTagName("description").item(0)?.textContent

                if (html != null) {
                    return """
                        <html><head><title>$title</title></head>
                        <body>
                        <h1>$title</h1>
                        $html
                        </body></html>
                    """.trimIndent()
                }
            }
        }
        return null
    }

    private fun getContentEncoded(item: Element): String? {
        val nodes = item.getElementsByTagNameNS(CONTENT_NS, "encoded")
        return if (nodes.length > 0) nodes.item(0).textContent else null
    }

    companion object {
        private const val CONTENT_NS = "http://purl.org/rss/1.0/modules/content/"

        /**
         * Create a [FeedResolver] from a URL template.
         * Placeholders `{0}`, `{1}`, etc. are replaced with path segments from the article URL.
         *
         * Examples:
         * - Template `"https://medium.com/feed/{0}"` with URL `medium.com/embabel/my-article`
         *   resolves to `https://medium.com/feed/embabel`
         * - Template `"https://myblog.com/feed"` (no placeholders) always resolves to that URL
         */
        @JvmStatic
        fun templateResolver(template: String) = FeedResolver { articleUrl ->
            val segments = URI(articleUrl).path.trimStart('/').split("/")
            var result = template
            segments.forEachIndexed { index, segment ->
                result = result.replace("{$index}", segment)
            }
            result
        }
    }
}
