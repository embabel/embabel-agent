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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FetchRouteTest {

    @Nested
    inner class FetchRouteTypeFactory {

        @Test
        fun `HTTP type builds HttpContentFetcher`() {
            val route = FetchRoute(pattern = "https://example.com/**", type = FetchRouteType.HTTP)
            val fetcher = route.buildFetcher()

            assertInstanceOf(HttpContentFetcher::class.java, fetcher)
        }

        @Test
        fun `RSS type builds RssContentFetcher with template resolver`() {
            val route = FetchRoute(
                pattern = "https://medium.com/**",
                type = FetchRouteType.RSS,
                feedTemplate = "https://medium.com/feed/{0}",
            )
            val fetcher = route.buildFetcher()

            assertInstanceOf(RssContentFetcher::class.java, fetcher)
        }

        @Test
        fun `RSS type throws when feedTemplate is missing`() {
            val route = FetchRoute(pattern = "https://medium.com/**", type = FetchRouteType.RSS)

            assertThrows<IllegalArgumentException> {
                route.buildFetcher()
            }
        }

        @Test
        fun `defaults to HTTP type`() {
            val route = FetchRoute(pattern = "https://example.com/**")

            assertEquals(FetchRouteType.HTTP, route.type)
        }
    }

    @Nested
    inner class BuildRoutingFetcher {

        @Test
        fun `builds RoutingContentFetcher from list of routes`() {
            val routes = listOf(
                FetchRoute(
                    pattern = "https://medium.com/**",
                    type = FetchRouteType.RSS,
                    feedTemplate = "https://medium.com/feed/{0}",
                ),
            )
            val fetcher = FetchRoute.buildRoutingFetcher(routes)

            assertInstanceOf(RoutingContentFetcher::class.java, fetcher)
        }

        @Test
        fun `empty routes returns default fetcher`() {
            val fetcher = FetchRoute.buildRoutingFetcher(emptyList())

            assertInstanceOf(HttpContentFetcher::class.java, fetcher)
        }

        @Test
        fun `custom default fetcher is returned when no routes`() {
            val custom = HttpContentFetcher(headers = mapOf("X-Custom" to "test"))
            val fetcher = FetchRoute.buildRoutingFetcher(emptyList(), defaultFetcher = custom)

            assertEquals(custom, fetcher)
        }
    }
}
