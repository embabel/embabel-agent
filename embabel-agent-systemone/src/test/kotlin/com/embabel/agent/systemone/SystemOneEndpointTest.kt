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
package com.embabel.agent.systemone

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.Duration

class SystemOneEndpointTest {

    @Nested
    inner class Validation {

        @Test
        fun `rejects endpoints that could carry credentials or are not http`() {
            listOf(
                "https://user:secret@api.example.com",
                "https://api.example.com?key=secret",
                "https://api.example.com#secret",
                "ftp://api.example.com",
                "api.example.com",
            ).forEach { uri ->
                val e = assertThrows<IllegalArgumentException>(uri) { SystemOneEndpoint("Example", URI.create(uri)) }
                assertFalse(e.message!!.contains("secret"), uri)
            }
        }

        @Test
        fun `rejects a blank provider, bad timeouts and a non-positive response limit`() {
            val base = URI.create("https://api.example.com")
            assertThrows<IllegalArgumentException> { SystemOneEndpoint(" ", base) }
            assertThrows<IllegalArgumentException> { SystemOneEndpoint("Example", base, connectTimeout = Duration.ZERO) }
            assertThrows<IllegalArgumentException> { SystemOneEndpoint("Example", base, readTimeout = Duration.ofDays(30)) }
            assertThrows<IllegalArgumentException> { SystemOneEndpoint("Example", base, maxResponseBytes = 0) }
        }
    }

    @Test
    fun `resolves paths under the base path`() {
        val endpoint = SystemOneEndpoint("Example", URI.create("http://proxy.example/systemone/"))
        assertEquals(URI.create("http://proxy.example/systemone/v1/models"), endpoint.resolve("/v1/models"))
    }

    @Test
    fun `defaults to ten second timeouts and a one MiB response limit`() {
        val endpoint = SystemOneEndpoint("Example", URI.create("https://api.example.com"))
        assertEquals(Duration.ofSeconds(10), endpoint.connectTimeout)
        assertEquals(Duration.ofSeconds(10), endpoint.readTimeout)
        assertEquals(1024 * 1024, endpoint.maxResponseBytes)
    }

    @Test
    fun `toString leaves the endpoint address out`() {
        val endpoint = SystemOneEndpoint("Example", URI.create("https://private.example.com"))
        assertFalse(endpoint.toString().contains("private"))
    }
}
