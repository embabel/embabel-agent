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

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import org.slf4j.LoggerFactory
import org.springframework.util.MimeTypeUtils

/**
 * Default [ContentFetcher] using [java.net.http.HttpClient] with browser-like headers.
 * Works for most public URLs but will be blocked by paywalled sites (e.g. Medium)
 * or aggressive bot detection (e.g. Cloudflare TLS fingerprinting).
 *
 * @param connectTimeout connection timeout
 * @param readTimeout read/response timeout
 * @param headers custom request headers; merged with (and override) the defaults
 */
class HttpContentFetcher @JvmOverloads constructor(
    private val connectTimeout: Duration = DEFAULT_TIMEOUT,
    private val readTimeout: Duration = DEFAULT_TIMEOUT,
    private val headers: Map<String, String> = emptyMap(),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : ContentFetcher, AutoCloseable by client {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun fetch(uri: URI): FetchResult {
        logger.debug("Fetching URI: {}", uri)
        val effectiveHeaders = DEFAULT_HEADERS + headers
        val builder = HttpRequest.newBuilder(uri)
            .timeout(readTimeout)
            .GET()
        effectiveHeaders.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val statusCode = response.statusCode()
        if (statusCode != 200) {
            logger.warn("Received HTTP {} for URI: {}", statusCode, uri)
            throw IOException("Server returned HTTP response code: $statusCode for URI: $uri")
        }
        val contentTypeHeader = response.headers().firstValue("Content-Type").orElse(null)
        val contentType = contentTypeHeader?.let {
            try {
                MimeTypeUtils.parseMimeType(it)
            } catch (e: Exception) {
                logger.warn("Failed to parse Content-Type '{}': {}", it, e.message)
                null
            }
        }
        logger.debug("Content-Type: {}", contentType ?: "unknown")
        val contentEncoding = response.headers().firstValue("Content-Encoding").orElse(null)
        logger.debug("Content-Encoding: {}", contentEncoding ?: "none")
        val content = response.body().use { rawStream ->
            val decompressed = when (contentEncoding?.lowercase()) {
                "gzip" -> GZIPInputStream(rawStream)
                "deflate" -> InflaterInputStream(rawStream)
                else -> rawStream
            }
            decompressed.use { it.readBytes() }
        }
        return FetchResult(
            content = content,
            contentType = contentType,
        )
    }

    companion object {
        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)

        @JvmField
        val DEFAULT_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "gzip, deflate",
            "Upgrade-Insecure-Requests" to "1",
        )
    }
}
