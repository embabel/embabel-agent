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

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.client.RestClient

class OnnxModelLoaderTest {

    @Test
    fun `resolve returns cached file when it exists`(@TempDir tempDir: Path) {
        val cacheDir = tempDir.resolve("cache")
        Files.createDirectories(cacheDir)
        val cachedFile = cacheDir.resolve("model.onnx")
        Files.writeString(cachedFile, "cached model data")
        val result = OnnxModelLoader.resolve("https://example.com/model.onnx", cacheDir, "model.onnx")
        assertEquals(cachedFile, result)
        assertEquals("cached model data", Files.readString(result))
    }

    @Test
    fun `resolve handles file URI pointing to existing file`(@TempDir tempDir: Path) {
        val localFile = tempDir.resolve("local-model.onnx")
        Files.writeString(localFile, "local model data")
        val cacheDir = tempDir.resolve("cache")
        val result = OnnxModelLoader.resolve(localFile.toUri().toString(), cacheDir, "model.onnx")
        assertEquals(localFile, result)
        assertEquals("local model data", Files.readString(result))
    }

    @Test
    fun `resolve with file URI throws when file does not exist`(@TempDir tempDir: Path) {
        val missingFile = tempDir.resolve("missing.onnx")
        val cacheDir = tempDir.resolve("cache")
        assertThrows(IllegalArgumentException::class.java) {
            OnnxModelLoader.resolve(missingFile.toUri().toString(), cacheDir, "model.onnx")
        }
    }

    @Test
    fun `resolve creates cache directory if it does not exist`(@TempDir tempDir: Path) {
        val cacheDir = tempDir.resolve("deep/nested/cache")
        val localFile = tempDir.resolve("model.onnx")
        Files.writeString(localFile, "data")
        OnnxModelLoader.resolve(localFile.toUri().toString(), cacheDir, "model.onnx")
        assertTrue(Files.exists(cacheDir))
    }

    @Nested
    inner class HttpDownloadTest {

        @Test
        fun `resolve downloads from HTTP and caches locally`(@TempDir tempDir: Path) {
            val payload = "fake model bytes"
            val server = HttpServer.create(InetSocketAddress(0), 0)
            server.createContext("/model.onnx") { exchange ->
                val bytes = payload.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val port = server.address.port
                val cacheDir = tempDir.resolve("dl-cache")
                val restClient = RestClient.create()
                val result = OnnxModelLoader.resolve(
                    "http://localhost:$port/model.onnx", cacheDir, "model.onnx", restClient,
                )
                assertTrue(Files.exists(result))
                assertEquals(payload, Files.readString(result))
                assertEquals(cacheDir.resolve("model.onnx"), result)
            } finally {
                server.stop(0)
            }
        }

        @Test
        fun `resolve download failure does not leave partial file`(@TempDir tempDir: Path) {
            val server = HttpServer.create(InetSocketAddress(0), 0)
            server.createContext("/model.onnx") { exchange ->
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            }
            server.start()
            try {
                val port = server.address.port
                val cacheDir = tempDir.resolve("dl-cache")
                val restClient = RestClient.create()
                assertThrows(Exception::class.java) {
                    OnnxModelLoader.resolve(
                        "http://localhost:$port/model.onnx", cacheDir, "model.onnx", restClient,
                    )
                }
                assertFalse(Files.exists(cacheDir.resolve("model.onnx")))
            } finally {
                server.stop(0)
            }
        }
    }
}
