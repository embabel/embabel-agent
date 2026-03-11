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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
}
