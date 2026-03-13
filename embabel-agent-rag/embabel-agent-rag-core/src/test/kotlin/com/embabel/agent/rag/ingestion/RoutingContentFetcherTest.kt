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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI
import org.springframework.util.MimeType

class RoutingContentFetcherTest {

    private val defaultFetcher = mockk<ContentFetcher>()
    private val mediumFetcher = mockk<ContentFetcher>()
    private val substackFetcher = mockk<ContentFetcher>()

    private fun fetchResult(content: String) = FetchResult(
        content = content.toByteArray(),
        contentType = MimeType("text", "html"),
    )

    @Nested
    inner class Routing {

        @Test
        fun `routes to matching fetcher when URI matches pattern`() {
            val expected = fetchResult("medium content")
            every { mediumFetcher.fetch(any()) } returns expected

            val router = RoutingContentFetcher(
                default = defaultFetcher,
                routes = listOf("https://medium.com/**" to mediumFetcher),
            )
            val result = router.fetch(URI("https://medium.com/some-article"))

            assertEquals(expected, result)
            verify(exactly = 0) { defaultFetcher.fetch(any()) }
        }

        @Test
        fun `falls back to default when no pattern matches`() {
            val expected = fetchResult("default content")
            every { defaultFetcher.fetch(any()) } returns expected

            val router = RoutingContentFetcher(
                default = defaultFetcher,
                routes = listOf("https://medium.com/**" to mediumFetcher),
            )
            val result = router.fetch(URI("https://example.com/article"))

            assertEquals(expected, result)
            verify(exactly = 0) { mediumFetcher.fetch(any()) }
        }

        @Test
        fun `first matching route wins`() {
            val expected = fetchResult("medium content")
            every { mediumFetcher.fetch(any()) } returns expected

            val router = RoutingContentFetcher(
                default = defaultFetcher,
                routes = listOf(
                    "https://medium.com/**" to mediumFetcher,
                    "**/medium*/**" to substackFetcher,
                ),
            )
            val result = router.fetch(URI("https://medium.com/article"))

            assertEquals(expected, result)
            verify(exactly = 0) { substackFetcher.fetch(any()) }
        }

        @Test
        fun `works with multiple routes`() {
            val expected = fetchResult("substack content")
            every { substackFetcher.fetch(any()) } returns expected

            val router = RoutingContentFetcher(
                default = defaultFetcher,
                routes = listOf(
                    "https://medium.com/**" to mediumFetcher,
                    "https://*.substack.com/**" to substackFetcher,
                ),
            )
            val result = router.fetch(URI("https://blog.substack.com/p/my-post"))

            assertEquals(expected, result)
        }

        @Test
        fun `supports wildcard for subdomain matching`() {
            val expected = fetchResult("substack content")
            every { substackFetcher.fetch(any()) } returns expected

            val router = RoutingContentFetcher(
                default = defaultFetcher,
                routes = listOf("https://*.substack.com/**" to substackFetcher),
            )
            val result = router.fetch(URI("https://myblog.substack.com/p/my-post"))

            assertEquals(expected, result)
        }

        @Test
        fun `supports double wildcard for path matching`() {
            val expected = fetchResult("api content")
            every { mediumFetcher.fetch(any()) } returns expected

            val router = RoutingContentFetcher(
                default = defaultFetcher,
                routes = listOf("**/api/v2/**" to mediumFetcher),
            )
            val result = router.fetch(URI("https://example.com/api/v2/articles"))

            assertEquals(expected, result)
        }
    }

    @Nested
    inner class JavaMapConstructor {

        @Test
        fun `works with map constructor`() {
            val expected = fetchResult("medium content")
            every { mediumFetcher.fetch(any()) } returns expected

            val router = RoutingContentFetcher(
                defaultFetcher,
                mapOf("https://medium.com/**" to mediumFetcher),
            )
            val result = router.fetch(URI("https://medium.com/article"))

            assertEquals(expected, result)
        }
    }
}
