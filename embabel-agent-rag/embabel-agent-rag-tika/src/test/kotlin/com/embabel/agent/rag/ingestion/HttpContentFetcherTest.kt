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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream

class HttpContentFetcherTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        port = server.address.port
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Nested
    inner class SuccessfulFetch {

        @Test
        fun `fetches HTML content`() {
            val html = "<html><body><h1>Hello</h1></body></html>"
            server.createContext("/page") { exchange ->
                val bytes = html.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            val result = HttpContentFetcher().fetch("http://localhost:$port/page")

            val content = result.inputStream.bufferedReader().readText()
            assertEquals("text/html", result.contentType)
            assertEquals("UTF-8", result.charset)
            assertTrue(content.contains("Hello"))
        }

        @Test
        fun `handles gzip compressed response`() {
            val html = "<html><body>Compressed content</body></html>"
            server.createContext("/gzip") { exchange ->
                val compressed = ByteArrayOutputStream().use { baos ->
                    GZIPOutputStream(baos).use { it.write(html.toByteArray()) }
                    baos.toByteArray()
                }
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(200, compressed.size.toLong())
                exchange.responseBody.use { it.write(compressed) }
            }
            server.start()

            val result = HttpContentFetcher().fetch("http://localhost:$port/gzip")

            val content = result.inputStream.bufferedReader().readText()
            assertTrue(content.contains("Compressed content"))
        }

        @Test
        fun `parses content type without charset`() {
            server.createContext("/no-charset") { exchange ->
                val bytes = "plain".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            val result = HttpContentFetcher().fetch("http://localhost:$port/no-charset")

            assertEquals("text/plain", result.contentType)
            assertEquals(null, result.charset)
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `throws IOException on non-200 response`() {
            server.createContext("/error") { exchange ->
                exchange.sendResponseHeaders(404, -1)
            }
            server.start()

            assertThrows<IOException> {
                HttpContentFetcher().fetch("http://localhost:$port/error")
            }
        }
    }

    @Nested
    inner class Configuration {

        @Test
        fun `uses custom timeouts`() {
            val fetcher = HttpContentFetcher(connectTimeout = 5_000, readTimeout = 5_000)
            server.createContext("/timeout") { exchange ->
                val bytes = "ok".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            val result = fetcher.fetch("http://localhost:$port/timeout")
            assertNotNull(result.inputStream)
        }
    }

    @Nested
    inner class ContentFetcherInjection {

        @Test
        fun `TikaHierarchicalContentReader uses injected ContentFetcher for HTTP URLs`() {
            val html = "<html><body><h1>Test</h1><p>Content from custom fetcher</p></body></html>"
            val customFetcher = ContentFetcher { _ ->
                FetchResult(
                    inputStream = html.byteInputStream(),
                    contentType = "text/html",
                    charset = "UTF-8",
                )
            }

            val reader = TikaHierarchicalContentReader(contentFetcher = customFetcher)
            val doc = reader.parseUrl("https://example.com/test")

            val leaves = doc.leaves().toList()
            assertTrue(leaves.isNotEmpty())
            val allText = leaves.joinToString(" ") { it.text }
            assertTrue(allText.contains("Content from custom fetcher"))
        }

        @Test
        fun `TikaHierarchicalContentReader defaults to HttpContentFetcher`() {
            val reader = TikaHierarchicalContentReader()
            assertNotNull(reader)
        }
    }
}
