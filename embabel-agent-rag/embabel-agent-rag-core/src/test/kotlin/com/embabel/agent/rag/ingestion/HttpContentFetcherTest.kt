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
import java.net.URI
import java.time.Duration
import java.util.zip.GZIPOutputStream

class HttpContentFetcherTest {

    private lateinit var server: HttpServer
    private var port: Int = 0
    private val fetchersToClose = mutableListOf<HttpContentFetcher>()

    private fun createFetcher(
        connectTimeout: Duration = Duration.ofSeconds(30),
        readTimeout: Duration = Duration.ofSeconds(30),
        headers: Map<String, String> = emptyMap(),
    ): HttpContentFetcher {
        val fetcher = HttpContentFetcher(connectTimeout, readTimeout, headers)
        fetchersToClose.add(fetcher)
        return fetcher
    }

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        port = server.address.port
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        fetchersToClose.forEach { it.close() }
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

            val result = createFetcher().fetch(URI("http://localhost:$port/page"))

            assertEquals("text", result.contentType?.type)
            assertEquals("html", result.contentType?.subtype)
            assertEquals(Charsets.UTF_8, result.contentType?.charset)
            assertTrue(String(result.content).contains("Hello"))
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

            val result = createFetcher().fetch(URI("http://localhost:$port/gzip"))

            assertTrue(String(result.content).contains("Compressed content"))
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

            val result = createFetcher().fetch(URI("http://localhost:$port/no-charset"))

            assertEquals("text", result.contentType?.type)
            assertEquals("plain", result.contentType?.subtype)
            assertEquals(null, result.contentType?.charset)
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
                createFetcher().fetch(URI("http://localhost:$port/error"))
            }
        }
    }

    @Nested
    inner class Configuration {

        @Test
        fun `uses custom timeouts`() {
            val fetcher = createFetcher(
                connectTimeout = Duration.ofSeconds(5),
                readTimeout = Duration.ofSeconds(5),
            )
            server.createContext("/timeout") { exchange ->
                val bytes = "ok".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            val result = fetcher.fetch(URI("http://localhost:$port/timeout"))
            assertNotNull(result.content)
        }

        @Test
        fun `custom headers override defaults`() {
            var receivedUserAgent: String? = null
            var receivedCustomHeader: String? = null
            server.createContext("/custom-headers") { exchange ->
                receivedUserAgent = exchange.requestHeaders.getFirst("User-Agent")
                receivedCustomHeader = exchange.requestHeaders.getFirst("X-Custom")
                val bytes = "ok".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            val fetcher = createFetcher(
                headers = mapOf(
                    "User-Agent" to "CustomBot/1.0",
                    "X-Custom" to "test-value",
                ),
            )
            fetcher.fetch(URI("http://localhost:$port/custom-headers"))

            assertEquals("CustomBot/1.0", receivedUserAgent)
            assertEquals("test-value", receivedCustomHeader)
        }

        @Test
        fun `default headers are sent when no overrides`() {
            var receivedUserAgent: String? = null
            server.createContext("/default-headers") { exchange ->
                receivedUserAgent = exchange.requestHeaders.getFirst("User-Agent")
                val bytes = "ok".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            createFetcher().fetch(URI("http://localhost:$port/default-headers"))

            assertTrue(receivedUserAgent!!.contains("Mozilla"))
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `handles response with no Content-Type header`() {
            server.createContext("/no-content-type") { exchange ->
                val bytes = "raw data".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            val result = createFetcher().fetch(URI("http://localhost:$port/no-content-type"))

            assertTrue(String(result.content).contains("raw data"))
        }

        @Test
        fun `handles deflate compressed response`() {
            val html = "<html><body>Deflate content</body></html>"
            server.createContext("/deflate") { exchange ->
                val compressed = java.io.ByteArrayOutputStream().use { baos ->
                    java.util.zip.DeflaterOutputStream(baos).use { it.write(html.toByteArray()) }
                    baos.toByteArray()
                }
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.responseHeaders.add("Content-Encoding", "deflate")
                exchange.sendResponseHeaders(200, compressed.size.toLong())
                exchange.responseBody.use { it.write(compressed) }
            }
            server.start()

            val result = createFetcher().fetch(URI("http://localhost:$port/deflate"))

            assertTrue(String(result.content).contains("Deflate content"))
        }
    }
}
