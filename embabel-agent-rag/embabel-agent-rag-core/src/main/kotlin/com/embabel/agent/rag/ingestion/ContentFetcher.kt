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

import java.net.URI
import org.springframework.util.MimeType

/**
 * Result of fetching content from a URI.
 * @param content the raw content bytes
 * @param contentType the MIME type of the content, or null if unknown.
 *        Carries charset when available (e.g. `text/html;charset=UTF-8`).
 */
data class FetchResult(
    val content: ByteArray,
    val contentType: MimeType? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FetchResult) return false
        return content.contentEquals(other.content) &&
            contentType == other.contentType
    }

    override fun hashCode(): Int {
        var result = content.contentHashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        return result
    }
}

/**
 * Abstraction for fetching raw content from HTTP/HTTPS URIs.
 * Implementations can use different strategies such as HttpURLConnection,
 * headless browsers (Selenium), or other HTTP clients.
 */
fun interface ContentFetcher {

    /**
     * Fetch raw content from the given URI.
     * @param uri the HTTP/HTTPS URI to fetch
     * @return a [FetchResult] containing the raw bytes and HTTP metadata
     * @throws java.io.IOException if the fetch fails
     */
    fun fetch(uri: URI): FetchResult
}

/**
 * Transforms fetched content bytes, e.g. extracting article HTML from an RSS feed,
 * stripping ads, or normalizing encoding.
 *
 * Mappers are composable via [then], allowing pipelines such as
 * `rssMapper.then(removeAds).then(translateTo(Language.FRENCH))`.
 */
fun interface ContentMapper {

    fun map(content: ByteArray, uri: URI): ByteArray

    /**
     * Compose this mapper with [next], producing a mapper that applies this first, then [next].
     */
    fun then(next: ContentMapper): ContentMapper = ContentMapper { content, uri ->
        next.map(this.map(content, uri), uri)
    }

    companion object {
        @JvmField
        val IDENTITY: ContentMapper = ContentMapper { content, _ -> content }
    }
}
