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

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets

class RssArticleContentMapperTest {

    private val mapper = RssArticleContentMapper()

    private val feedWithContentEncoded = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
            <channel>
                <title>Test Blog</title>
                <item>
                    <title>First Article</title>
                    <link>https://blog.com/pub/first-article</link>
                    <guid>https://blog.com/pub/first-article</guid>
                    <description>Short description</description>
                    <content:encoded><![CDATA[<p>Full article content here</p>]]></content:encoded>
                </item>
                <item>
                    <title>Second Article</title>
                    <link>https://blog.com/pub/second-article</link>
                    <guid>https://blog.com/pub/second-article</guid>
                    <description>Second article description only</description>
                </item>
                <item>
                    <title>GUID Only Match</title>
                    <link>https://blog.com/other-path</link>
                    <guid>https://blog.com/pub/guid-article</guid>
                    <description>Found by guid</description>
                </item>
            </channel>
        </rss>
    """.trimIndent()

    private fun feedBytes() = feedWithContentEncoded.toByteArray(StandardCharsets.UTF_8)

    private fun mapToString(feedXml: ByteArray, articleUri: URI): String {
        return String(mapper.map(feedXml, articleUri), StandardCharsets.UTF_8)
    }

    @Nested
    inner class ArticleExtraction {

        @Test
        fun `extracts content encoded when available`() {
            val html = mapToString(feedBytes(), URI("https://blog.com/pub/first-article"))

            assertTrue(html.contains("Full article content here"))
            assertTrue(html.contains("First Article"))
        }

        @Test
        fun `falls back to description when content encoded is absent`() {
            val html = mapToString(feedBytes(), URI("https://blog.com/pub/second-article"))

            assertTrue(html.contains("Second article description only"))
            assertTrue(html.contains("Second Article"))
        }

        @Test
        fun `matches article by slug from URL path`() {
            val html = mapToString(feedBytes(), URI("https://blog.com/category/first-article"))

            assertTrue(html.contains("Full article content here"))
        }

        @Test
        fun `matches article by guid when link does not match`() {
            val html = mapToString(feedBytes(), URI("https://blog.com/pub/guid-article"))

            assertTrue(html.contains("Found by guid"))
        }

        @Test
        fun `wraps content in HTML document with title`() {
            val html = mapToString(feedBytes(), URI("https://blog.com/pub/first-article"))

            assertTrue(html.contains("<html>"))
            assertTrue(html.contains("<title>First Article</title>"))
            assertTrue(html.contains("<h1>First Article</h1>"))
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `throws IOException when article not found in feed`() {
            val exception = assertThrows<IOException> {
                mapper.map(feedBytes(), URI("https://blog.com/pub/nonexistent-article"))
            }
            assertTrue(exception.message!!.contains("Article not found"))
        }

        @Test
        fun `uses Untitled when item has no title element`() {
            val feedNoTitle = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
                    <channel>
                        <title>Test Blog</title>
                        <item>
                            <link>https://blog.com/pub/no-title-article</link>
                            <guid>https://blog.com/pub/no-title-article</guid>
                            <description>Content without a title</description>
                        </item>
                    </channel>
                </rss>
            """.trimIndent()

            val html = mapToString(
                feedNoTitle.toByteArray(StandardCharsets.UTF_8),
                URI("https://blog.com/pub/no-title-article"),
            )

            assertTrue(html.contains("Untitled"))
            assertTrue(html.contains("Content without a title"))
        }

        @Test
        fun `throws IOException when matched item has no content or description`() {
            val feedNoContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
                    <channel>
                        <title>Test Blog</title>
                        <item>
                            <title>Empty Article</title>
                            <link>https://blog.com/pub/empty-article</link>
                            <guid>https://blog.com/pub/empty-article</guid>
                        </item>
                    </channel>
                </rss>
            """.trimIndent()

            assertThrows<IOException> {
                mapper.map(
                    feedNoContent.toByteArray(StandardCharsets.UTF_8),
                    URI("https://blog.com/pub/empty-article"),
                )
            }
        }
    }

    @Nested
    inner class Composition {

        @Test
        fun `can be composed with other mappers via then`() {
            val uppercaseMapper = ContentMapper { content, _ ->
                String(content).uppercase().toByteArray()
            }
            val composed = mapper.then(uppercaseMapper)
            val result = String(composed.map(feedBytes(), URI("https://blog.com/pub/first-article")))

            assertTrue(result.contains("FULL ARTICLE CONTENT HERE"))
        }
    }
}
