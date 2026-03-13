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

import java.io.InputStream

/**
 * Result of fetching content from a URL.
 * @param T the type of the transformed content
 * @param content the content produced by the mapper function
 * @param contentType the MIME type of the content (e.g. "text/html"), or null if unknown
 * @param charset the charset of the content (e.g. "UTF-8"), or null if unknown
 */
data class FetchResult<T>(
    val content: T,
    val contentType: String? = null,
    val charset: String? = null,
)

/**
 * Abstraction for fetching content from HTTP/HTTPS URLs.
 * Implementations can use different strategies such as HttpURLConnection,
 * headless browsers (Selenium), or other HTTP clients.
 *
 * The [mapper] function is invoked while the underlying connection is still open,
 * ensuring the stream remains readable. The connection is closed after the mapper returns.
 */
interface ContentFetcher {

    /**
     * Fetch content from the given URL, transforming the response stream via [mapper].
     * @param url the HTTP/HTTPS URL to fetch
     * @param mapper transforms the response [InputStream] into the desired result type
     * @return a [FetchResult] containing the mapped content and HTTP metadata
     * @throws java.io.IOException if the fetch fails
     */
    fun <T> fetch(url: String, mapper: (InputStream) -> T): FetchResult<T>
}
