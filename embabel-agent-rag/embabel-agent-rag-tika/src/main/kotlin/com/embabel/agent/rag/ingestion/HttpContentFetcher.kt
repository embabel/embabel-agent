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

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Default [ContentFetcher] using [HttpURLConnection] with browser-like headers.
 * Works for most public URLs but will be blocked by paywalled sites (e.g. Medium)
 * or aggressive bot detection (e.g. Cloudflare TLS fingerprinting).
 */
class HttpContentFetcher @JvmOverloads constructor(
    private val connectTimeout: Int = DEFAULT_TIMEOUT,
    private val readTimeout: Int = DEFAULT_TIMEOUT,
) : ContentFetcher {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun fetch(url: String): FetchResult {
        logger.debug("Fetching URL with HttpURLConnection: {}", url)

        val uri = URI(url)
        val connection = uri.toURL().openConnection() as HttpURLConnection

        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
            )
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
            connection.setRequestProperty("Connection", "keep-alive")
            connection.setRequestProperty("Upgrade-Insecure-Requests", "1")
            connection.connectTimeout = connectTimeout
            connection.readTimeout = readTimeout

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                logger.warn("Received HTTP {} for URL: {}", responseCode, url)
                throw IOException("Server returned HTTP response code: $responseCode for URL: $url")
            }

            // Parse Content-Type header
            var mimeType: String? = null
            var charset: String? = null
            val contentType = connection.contentType
            if (contentType != null) {
                val parts = contentType.split(";").map { it.trim() }
                mimeType = parts[0]
                val charsetPart = parts.find { it.startsWith("charset=", ignoreCase = true) }
                if (charsetPart != null) {
                    charset = charsetPart.substringAfter("=").trim()
                }
                logger.debug("Content-Type: {}, charset: {}", mimeType, charset ?: "unspecified")
            }

            // Decompress if needed
            val contentEncoding = connection.contentEncoding
            logger.debug("Content-Encoding: {}", contentEncoding ?: "none")

            val rawStream = connection.inputStream
            val decompressedStream = when (contentEncoding?.lowercase()) {
                "gzip" -> GZIPInputStream(rawStream)
                "deflate" -> InflaterInputStream(rawStream)
                else -> rawStream
            }

            return FetchResult(
                inputStream = decompressedStream,
                contentType = mimeType,
                charset = charset,
            )
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT = 30_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
