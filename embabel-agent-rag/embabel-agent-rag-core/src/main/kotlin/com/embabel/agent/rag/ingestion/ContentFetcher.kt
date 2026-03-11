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
 * @param inputStream the decompressed content stream. Caller is responsible for closing.
 * @param contentType the MIME type of the content (e.g. "text/html"), or null if unknown
 * @param charset the charset of the content (e.g. "UTF-8"), or null if unknown
 */
data class FetchResult(
    val inputStream: InputStream,
    val contentType: String? = null,
    val charset: String? = null,
)

/**
 * Abstraction for fetching content from HTTP/HTTPS URLs.
 * Implementations can use different strategies such as HttpURLConnection,
 * headless browsers (Selenium), or other HTTP clients.
 *
 * This allows plugging in alternative fetchers for sites that block
 * simple HTTP clients (e.g. Medium's paywall, Cloudflare-protected sites).
 */
fun interface ContentFetcher {

    /**
     * Fetch content from the given URL.
     * @param url the HTTP/HTTPS URL to fetch
     * @return a [FetchResult] containing the content stream and metadata
     * @throws java.io.IOException if the fetch fails
     */
    fun fetch(url: String): FetchResult
}
