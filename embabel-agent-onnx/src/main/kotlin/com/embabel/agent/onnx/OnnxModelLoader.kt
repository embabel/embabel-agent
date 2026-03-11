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
package com.embabel.agent.onnx

import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.slf4j.LoggerFactory

/**
 * Shared utility for downloading and caching ONNX model files.
 *
 * Downloads from HuggingFace (or other HTTPS sources) on first use
 * and caches locally. Supports file:// URIs for pre-downloaded models.
 */
object OnnxModelLoader {

    private val logger = LoggerFactory.getLogger(OnnxModelLoader::class.java)

    const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
    const val DEFAULT_READ_TIMEOUT_MS = 120_000

    /**
     * Resolves a model resource, downloading and caching it if necessary.
     *
     * @param uri HTTPS or file:// URI of the resource
     * @param cacheDir local directory for cached files
     * @param filename name to use for the cached file
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs read timeout in milliseconds
     * @return path to the local file
     */
    fun resolve(
        uri: String,
        cacheDir: Path,
        filename: String,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    ): Path {
        val cachedFile = cacheDir.resolve(filename)

        if (Files.exists(cachedFile)) {
            logger.debug("Using cached file: {}", cachedFile)
            return cachedFile
        }

        Files.createDirectories(cacheDir)

        val parsedUri = URI(uri)
        if (parsedUri.scheme == "file") {
            val localPath = Path.of(parsedUri)
            require(Files.exists(localPath)) { "Local file not found: $localPath" }
            return localPath
        }

        logger.info("Downloading {} to {}", uri, cachedFile)
        val tempFile = Files.createTempFile(cacheDir, filename, ".tmp")
        try {
            downloadWithRedirects(parsedUri, connectTimeoutMs, readTimeoutMs).use { input ->
                Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(tempFile, cachedFile, StandardCopyOption.ATOMIC_MOVE)
            logger.info("Downloaded {} ({} bytes)", filename, Files.size(cachedFile))
        } catch (e: Exception) {
            Files.deleteIfExists(tempFile)
            throw e
        }

        return cachedFile
    }

    /**
     * Opens an InputStream from the given URI, following HTTP redirects.
     * HuggingFace uses 302 redirects to CDN URLs.
     */
    private fun downloadWithRedirects(uri: URI, connectTimeoutMs: Int, readTimeoutMs: Int): InputStream {
        val connection = uri.toURL().openConnection() as java.net.HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        return connection.inputStream
    }
}
