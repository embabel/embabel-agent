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

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.slf4j.LoggerFactory
import org.springframework.web.util.HtmlUtils
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * [ContentMapper] that extracts a single article's HTML from RSS/Atom feed XML.
 *
 * This mapper is specifically designed for article content (blog posts, news items, etc.)
 * and is not intended for other RSS content types such as podcasts or video feeds.
 *
 * Given the raw bytes of an RSS feed and the target article URI, this mapper
 * locates the matching `<item>` by slug and returns the article content
 * wrapped in a minimal HTML document.
 *
 * Prefers `content:encoded` (full HTML) over `description` (summary).
 */
class RssArticleContentMapper : ContentMapper {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }

    override fun map(content: ByteArray, uri: URI): ByteArray {
        val html = extractArticleContent(content, uri)
            ?: throw IOException("Article not found in RSS feed for URI: $uri")
        logger.info("Extracted {} chars of article content from RSS", html.length)
        return html.toByteArray(StandardCharsets.UTF_8)
    }

    private fun extractArticleContent(feedBytes: ByteArray, articleUri: URI): String? {
        val doc = documentBuilderFactory.newDocumentBuilder()
            .parse(ByteArrayInputStream(feedBytes))
        val items: NodeList = doc.getElementsByTagName("item")
        val articleSlug = articleUri.path.trimEnd('/').substringAfterLast('/')
        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            val link = item.getElementsByTagName("link").item(0)?.textContent.orEmpty()
            val guid = item.getElementsByTagName("guid").item(0)?.textContent.orEmpty()
            if (link.contains(articleSlug) || guid.contains(articleSlug)) {
                val rawTitle = item.getElementsByTagName("title").item(0)?.textContent ?: "Untitled"
                val title = HtmlUtils.htmlEscape(rawTitle)
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
    }
}
