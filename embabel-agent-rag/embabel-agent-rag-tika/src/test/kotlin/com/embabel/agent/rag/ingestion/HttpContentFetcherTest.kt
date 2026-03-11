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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class HttpContentFetcherTest {

    @Nested
    @Tag("integration")
    inner class Integration {

        @Test
        fun `fetches HTML content from a public URL`() {
            val fetcher = HttpContentFetcher()

            val result = fetcher.fetch("https://example.com")

            assertNotNull(result.inputStream)
            assertEquals("text/html", result.contentType)
            val content = result.inputStream.bufferedReader().readText()
            assertTrue(content.contains("Example Domain"))
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
            // Just verify construction succeeds — actual HTTP fetching is an integration concern
            assertNotNull(reader)
        }
    }
}
