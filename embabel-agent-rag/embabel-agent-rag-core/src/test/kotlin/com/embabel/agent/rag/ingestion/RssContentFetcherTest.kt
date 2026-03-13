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

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetSocketAddress

class RssContentFetcherTest {

    @Nested
    inner class TemplateResolver {

        @Test
        fun `replaces single placeholder with first path segment`() {
            val resolver = RssContentFetcher.templateResolver("https://medium.com/feed/{0}")
            val result = resolver.resolve("https://medium.com/embabel/my-article")

            assertEquals("https://medium.com/feed/embabel", result)
        }

        @Test
        fun `replaces multiple placeholders with path segments`() {
            val resolver = RssContentFetcher.templateResolver("https://example.com/{0}/feed/{1}")
            val result = resolver.resolve("https://example.com/blog/posts/article-slug")

            assertEquals("https://example.com/blog/feed/posts", result)
        }

        @Test
        fun `template with no placeholders returns constant URL`() {
            val resolver = RssContentFetcher.templateResolver("https://myblog.com/feed")
            val result = resolver.resolve("https://myblog.com/2024/some-article")

            assertEquals("https://myblog.com/feed", result)
        }

        @Test
        fun `handles URLs with leading slash in path`() {
            val resolver = RssContentFetcher.templateResolver("https://medium.com/feed/{0}")
            val result = resolver.resolve("https://medium.com/my-publication/my-post")

            assertEquals("https://medium.com/feed/my-publication", result)
        }

        @Test
        fun `placeholder beyond available segments is left as-is`() {
            val resolver = RssContentFetcher.templateResolver("https://example.com/{0}/{5}")
            val result = resolver.resolve("https://example.com/blog/post")

            assertEquals("https://example.com/blog/{5}", result)
        }
    }

    @Nested
    inner class Fetch {

        private lateinit var server: HttpServer
        private var port: Int = 0

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

        @BeforeEach
        fun setUp() {
            server = HttpServer.create(InetSocketAddress(0), 0)
            port = server.address.port
            server.createContext("/feed") { exchange ->
                val bytes = feedWithContentEncoded.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/rss+xml; charset=UTF-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        @AfterEach
        fun tearDown() {
            server.stop(0)
        }

        private fun createFetcher(): RssContentFetcher {
            return RssContentFetcher(
                feedResolver = FeedResolver { "http://localhost:$port/feed" },
            )
        }

        private fun readText() = { stream: java.io.InputStream -> stream.bufferedReader().readText() }

        @Test
        fun `extracts content encoded when available`() {
            val result = createFetcher().fetch("https://blog.com/pub/first-article", readText())

            assertTrue(result.content.contains("Full article content here"))
            assertTrue(result.content.contains("First Article"))
            assertEquals("text/html", result.contentType)
            assertEquals("UTF-8", result.charset)
        }

        @Test
        fun `falls back to description when content encoded is absent`() {
            val result = createFetcher().fetch("https://blog.com/pub/second-article", readText())

            assertTrue(result.content.contains("Second article description only"))
            assertTrue(result.content.contains("Second Article"))
        }

        @Test
        fun `throws IOException when article not found in feed`() {
            val exception = assertThrows<IOException> {
                createFetcher().fetch("https://blog.com/pub/nonexistent-article", readText())
            }
            assertTrue(exception.message!!.contains("Article not found"))
        }

        @Test
        fun `matches article by slug from URL path`() {
            val result = createFetcher().fetch("https://blog.com/category/first-article", readText())

            assertTrue(result.content.contains("Full article content here"))
        }

        @Test
        fun `wraps content in HTML document with title`() {
            val result = createFetcher().fetch("https://blog.com/pub/first-article", readText())

            assertTrue(result.content.contains("<html>"))
            assertTrue(result.content.contains("<title>First Article</title>"))
            assertTrue(result.content.contains("<h1>First Article</h1>"))
        }

        @Test
        fun `returns valid FetchResult metadata`() {
            val result = createFetcher().fetch("https://blog.com/pub/first-article", readText())

            assertNotNull(result.content)
            assertEquals("text/html", result.contentType)
            assertEquals("UTF-8", result.charset)
        }

        @Test
        fun `matches article by guid when link does not match`() {
            val result = createFetcher().fetch("https://blog.com/pub/guid-article", readText())

            assertTrue(result.content.contains("Found by guid"))
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

            val noTitleServer = HttpServer.create(InetSocketAddress(0), 0)
            val noTitlePort = noTitleServer.address.port
            noTitleServer.createContext("/feed") { exchange ->
                val bytes = feedNoTitle.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/rss+xml; charset=UTF-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            noTitleServer.start()

            try {
                val fetcher = RssContentFetcher(
                    feedResolver = FeedResolver { "http://localhost:$noTitlePort/feed" },
                )
                val result = fetcher.fetch("https://blog.com/pub/no-title-article", readText())

                assertTrue(result.content.contains("Untitled"))
                assertTrue(result.content.contains("Content without a title"))
            } finally {
                noTitleServer.stop(0)
            }
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

            val emptyServer = HttpServer.create(InetSocketAddress(0), 0)
            val emptyPort = emptyServer.address.port
            emptyServer.createContext("/feed") { exchange ->
                val bytes = feedNoContent.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/rss+xml; charset=UTF-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            emptyServer.start()

            try {
                val fetcher = RssContentFetcher(
                    feedResolver = FeedResolver { "http://localhost:$emptyPort/feed" },
                )
                assertThrows<IOException> {
                    fetcher.fetch("https://blog.com/pub/empty-article", readText())
                }
            } finally {
                emptyServer.stop(0)
            }
        }
    }
}
