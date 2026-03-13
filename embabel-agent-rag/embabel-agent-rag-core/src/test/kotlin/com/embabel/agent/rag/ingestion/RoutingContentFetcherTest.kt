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
import java.io.InputStream

class RoutingContentFetcherTest {

    private val defaultFetcher = mockk<ContentFetcher>()
    private val mediumFetcher = mockk<ContentFetcher>()
    private val substackFetcher = mockk<ContentFetcher>()

    private val readText: (InputStream) -> String = { it.bufferedReader().readText() }

    private fun fetchResult(content: String) = FetchResult(
        content = content,
        contentType = "text/html",
    )

    @Nested
    inner class Routing {

        @Test
        fun `routes to matching fetcher when URL contains pattern`() {
            val expected = fetchResult("medium content")
            every { mediumFetcher.fetch(any(), any<(InputStream) -> String>()) } returns expected

            val router = RoutingContentFetcher(
                default = defaultFetcher,
                routes = listOf("medium.com" to mediumFetcher),
            )
            val result = router.fetch("https://medium.com/some-article", readText)

            assertEquals(expected, result)
            verify(exactly = 0) { defaultFetcher.fetch(any(), any<(InputStream) -> String>()) }
        }

        @Test
        fun `falls back to default when no pattern matches`() {
            val expected = fetchResult("default content")
            every { defaultFetcher.fetch(any(), any<(InputStream) -> String>()) } returns expected

            val router = RoutingContentFetcher(
                default = defaultFetcher,
                routes = listOf("medium.com" to mediumFetcher),
            )
            val result = router.fetch("https://example.com/article", readText)

            assertEquals(expected, result)
            verify(exactly = 0) { mediumFetcher.fetch(any(), any<(InputStream) -> String>()) }
        }

        @Test
        fun `first matching route wins`() {
            val expected = fetchResult("medium content")
            every { mediumFetcher.fetch(any(), any<(InputStream) -> String>()) } returns expected

            val router = RoutingContentFetcher(
                default = defaultFetcher,
                routes = listOf(
                    "medium.com" to mediumFetcher,
                    "medium" to substackFetcher,
                ),
            )
            val result = router.fetch("https://medium.com/article", readText)

            assertEquals(expected, result)
            verify(exactly = 0) { substackFetcher.fetch(any(), any<(InputStream) -> String>()) }
        }

        @Test
        fun `works with multiple routes`() {
            val expected = fetchResult("substack content")
            every { substackFetcher.fetch(any(), any<(InputStream) -> String>()) } returns expected

            val router = RoutingContentFetcher(
                default = defaultFetcher,
                routes = listOf(
                    "medium.com" to mediumFetcher,
                    "substack.com" to substackFetcher,
                ),
            )
            val result = router.fetch("https://blog.substack.com/p/my-post", readText)

            assertEquals(expected, result)
        }
    }

    @Nested
    inner class JavaMapConstructor {

        @Test
        fun `works with map constructor`() {
            val expected = fetchResult("medium content")
            every { mediumFetcher.fetch(any(), any<(InputStream) -> String>()) } returns expected

            val router = RoutingContentFetcher(
                defaultFetcher,
                mapOf("medium.com" to mediumFetcher),
            )
            val result = router.fetch("https://medium.com/article", readText)

            assertEquals(expected, result)
        }
    }
}
