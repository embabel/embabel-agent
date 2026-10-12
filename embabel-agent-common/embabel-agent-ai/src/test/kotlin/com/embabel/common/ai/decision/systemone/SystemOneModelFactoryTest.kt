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
package com.embabel.common.ai.decision.systemone

import com.embabel.common.ai.classification.*
import com.embabel.common.ai.decision.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.Duration
import java.util.function.Supplier

class SystemOneModelFactoryTest {
    @Test
    fun `options reject unsafe locations and invalid bounds without revealing URI`() {
        listOf("relative", "file:///private", "http://user:PRIVATE@localhost/", "http://localhost/?PRIVATE", "http://localhost/#PRIVATE")
            .forEach { value ->
                val failure = assertThrows<IllegalArgumentException> { SystemOneClientOptions(URI(value)) }
                assertFalse(failure.toString().contains("PRIVATE"))
            }
        listOf(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(301)).forEach { value ->
            assertThrows<IllegalArgumentException> { SystemOneClientOptions(URI("http://localhost/"), value) }
        }
        listOf(0, -1, 1048577).forEach { value ->
            assertThrows<IllegalArgumentException> { SystemOneClientOptions(URI("http://localhost/"), maxRequestBytes = value) }
            assertThrows<IllegalArgumentException> { SystemOneClientOptions(URI("http://localhost/"), maxResponseBytes = value) }
        }
        assertFalse(SystemOneClientOptions(URI("https://private-host/secret-path")).toString().contains("private-host"))
    }

    @Test
    fun `local catalogs construction and descriptions never call transport or credential suppliers`() {
        val names = mutableListOf("alias")
        var credentials = 0
        SystemOneTestServer().use { server ->
            SystemOneModelFactory("systemone", "SystemOne", SystemOneClientOptions(server.uri()), names,
                tokenSupplier = Supplier { credentials++; error("PRIVATE_TOKEN") }).use { factory ->
                names += "other"
                assertEquals(listOf("alias"), factory.models().map { it.modelName })
                assertEquals(factory.describe("alias").capabilities, factory.create("alias").capabilities())
                assertThrows<IllegalArgumentException> { factory.create("other") }
                assertThrows<IllegalArgumentException> { factory.describe("other") }
                assertEquals(0, credentials); assertEquals(0, server.calls.get())
            }
        }
    }

    @Test
    fun `explicit catalog validates vendor metadata without mutating configured catalog`() {
        val body = """{"models":[{"name":"remote","description":"model","release_date":"2026-09-15"}]}"""
        SystemOneTestServer(body).use { server -> server.factory().use { factory ->
            assertEquals(listOf("remote"), factory.discoverModels().map { it.modelName })
            assertEquals(listOf("alias"), factory.models().map { it.modelName })
            assertThrows<IllegalArgumentException> { factory.create("remote") }
            assertEquals(1, server.calls.get())
        } }
        listOf("{}", """{"models":[{"name":"x"}]}""", body.replace("2026-09-15", "invalid"),
            body.replace("\"remote\"", "\" \""), body.replace("]}", ",{\"name\":\"remote\",\"description\":\"same\",\"release_date\":\"2026-09-15\"}]}"))
            .forEach { invalid -> SystemOneTestServer(invalid).use { server -> server.factory().use { factory ->
                assertThrows<IllegalStateException> { factory.discoverModels() }
                assertEquals(1, server.calls.get())
            } } }
    }

    @Test
    fun `base paths normalize without requiring a trailing slash`() {
        listOf("/prefix", "/prefix/").forEach { path -> SystemOneTestServer().use { server ->
            var receivedPath = ""
            server.handler = { exchange -> receivedPath = exchange.requestURI.path; server.respond(exchange, SystemOneTestServer.fixture()) }
            SystemOneModelFactory("systemone", "SystemOne", SystemOneClientOptions(server.uri(path)), listOf("alias"))
                .use { factory ->
                    factory.create("alias").assess(PropositionRequest("input", "Is it true?"))
                    assertEquals("/prefix/v1/systemone", receivedPath)
                }
        } }
    }

    @Test
    fun `bearer rotates per call and nonloopback cleartext credentials never make a call`() {
        var token = "first"
        SystemOneTestServer().use { server -> server.factory(token = { token }).use { factory ->
            val question = Questions.named("noul").proposition("true?").build()
            factory.create("alias").ask("input", DecisionSpec.of(question)); token = "second"
            factory.create("alias").ask("input", DecisionSpec.of(question))
            assertEquals(listOf("Bearer first", "Bearer second"), server.authorizations.toList())
        } }
        SystemOneModelFactory("systemone", "SystemOne", SystemOneClientOptions(URI("http://192.0.2.1/")), listOf("alias"),
            tokenSupplier = Supplier { "PRIVATE_TOKEN" }).use { factory ->
            val result = factory.create("alias").assess(PropositionRequest("input", "true?"))
            assertEquals(PropositionResult.Failure(FailureReason.INVALID_RESPONSE), result)
            assertFalse(result.toString().contains("PRIVATE"))
        }
    }

    @Test
    fun `profile metadata is copied immutable and receives aliases usage and bounded headers`() {
        var seen: SystemOneResponseMetadata? = null
        val profile = object : SystemOneModelProfile {
            override fun provenance(metadata: SystemOneResponseMetadata): ModelProvenance {
                seen = metadata
                return ModelProvenance(metadata.actualModel, "custom", "v1", "request-1")
            }
        }
        SystemOneTestServer().use { server ->
            server.handler = { exchange -> exchange.responseHeaders.add("X-Custom", "value"); server.respond(exchange, SystemOneTestServer.fixture()) }
            server.factory(profile = profile).use { factory ->
                factory.create("alias").assess(PropositionRequest("input", "true?"))
                val metadata = seen!!
                assertEquals("alias", metadata.requestedModel); assertEquals("openjev-fixture", metadata.actualModel)
                assertEquals(100L, metadata.inputTokens); assertEquals(0L, metadata.outputTokens)
                assertEquals(listOf("value"), metadata.headers["x-custom"])
                assertThrows<UnsupportedOperationException> { (metadata.headers as MutableMap).clear() }
                assertThrows<UnsupportedOperationException> { (metadata.headers["x-custom"] as MutableList).clear() }
                assertFalse(metadata.toString().contains("value"))
            }
        }
    }
}
