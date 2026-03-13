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

enum class FetchRouteType {
    HTTP,
    RSS,
}

data class FetchRoute(
    val pattern: String,
    val type: FetchRouteType = FetchRouteType.HTTP,
    val feedTemplate: String? = null,
) {

    fun buildFetcher(): ContentFetcher = when (type) {
        FetchRouteType.HTTP -> HttpContentFetcher()
        FetchRouteType.RSS -> {
            require(feedTemplate != null) { "RSS route requires feed-template for pattern: $pattern" }
            RssContentFetcher(
                feedResolver = RssContentFetcher.templateResolver(feedTemplate),
                delegate = HttpContentFetcher(),
            )
        }
    }

    companion object {

        @JvmStatic
        fun buildRoutingFetcher(
            routes: List<FetchRoute>,
            defaultFetcher: ContentFetcher = HttpContentFetcher(),
        ): ContentFetcher {
            if (routes.isEmpty()) return defaultFetcher
            val routePairs = routes.map { it.pattern to it.buildFetcher() }
            return RoutingContentFetcher(defaultFetcher, routePairs)
        }
    }
}
