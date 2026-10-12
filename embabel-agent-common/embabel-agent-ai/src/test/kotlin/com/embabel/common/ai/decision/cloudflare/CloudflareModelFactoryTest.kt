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
package com.embabel.common.ai.decision.cloudflare

import com.embabel.common.ai.decision.QuestionKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.function.Supplier

class CloudflareModelFactoryTest {
    @Nested
    inner class Configuration {
        @Test
        fun `account and bounded options reject unsafe values with sanitized diagnostics`() {
            listOf("../other", "", "abc", "0".repeat(31), "0".repeat(33), "g".repeat(32), "PRIVATE/account")
                .forEach { account ->
                    val failure = assertThrows<IllegalArgumentException> { CloudflareClientOptions(account) }
                    assertFalse(failure.toString().contains(account.takeIf { it.isNotEmpty() } ?: "PRIVATE"))
                }
            listOf(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(301)).forEach { timeout ->
                assertThrows<IllegalArgumentException> { CloudflareClientOptions(ACCOUNT, timeout) }
            }
            listOf(0, -1, 1048577).forEach { bytes ->
                assertThrows<IllegalArgumentException> { CloudflareClientOptions(ACCOUNT, maxRequestBytes = bytes) }
                assertThrows<IllegalArgumentException> { CloudflareClientOptions(ACCOUNT, maxResponseBytes = bytes) }
            }
            val options = CloudflareClientOptions(ACCOUNT)
            assertEquals(Duration.ofSeconds(20), options.timeout)
            assertEquals(262144, options.maxRequestBytes)
            assertEquals(1048576, options.maxResponseBytes)
            assertFalse(options.toString().contains(ACCOUNT))
        }

        @Test
        fun `ordinary Java constructor overloads expose only supported options`() {
            CloudflareClientOptions::class.java.getConstructor(String::class.java).newInstance(ACCOUNT)
            CloudflareModelFactory::class.java.getConstructor(CloudflareClientOptions::class.java)
                .newInstance(CloudflareClientOptions(ACCOUNT)).use { factory ->
                    assertEquals("cloudflare", factory.id)
                    assertEquals(listOf(CLEF, FLASH), factory.models().map { it.modelName })
                }
            assertFalse(CloudflareClientOptions::class.java.constructors.any { constructor ->
                constructor.parameterTypes.any { it == java.net.URI::class.java }
            })
        }
    }

    @Nested
    inner class DeclaredCatalog {
        @Test
        fun `registration and catalog never resolve auth or call HTTP and snapshot names`() {
            val names = mutableListOf(CLEF, FLASH)
            var credentials = 0
            CloudflarePeer().use { peer -> peer.factory(names = names, token = Supplier { credentials++; error("PRIVATE") }).use { factory ->
                names.clear()
                assertEquals(listOf(CLEF, FLASH), factory.models().map { it.modelName })
                listOf(CLEF, FLASH).forEach { name ->
                    assertEquals(factory.describe(name).capabilities, factory.create(name).capabilities())
                    assertEquals("Cloudflare Workers AI", factory.create(name).provider)
                }
                assertEquals(0, credentials)
                assertEquals(0, peer.received.get())
                assertEquals(setOf(QuestionKind.PROPOSITION, QuestionKind.CHOICE, QuestionKind.RATING),
                    factory.create(CLEF).capabilities().questionKinds.toSet())
            } }
        }

        @Test
        fun `unknown aliases traversal omni and duplicate catalogs reject locally`() {
            listOf("clef", "@cf/cloudflare/clef-omni", "$CLEF/../other", "$CLEF?secret", "https://PRIVATE/model")
                .forEach { unknown ->
                    CloudflareModelFactory(CloudflareClientOptions(ACCOUNT)).use { factory ->
                        assertThrows<IllegalArgumentException> { factory.describe(unknown) }
                        assertThrows<IllegalArgumentException> { factory.create(unknown) }
                    }
                    assertThrows<IllegalArgumentException> { CloudflareModelFactory(CloudflareClientOptions(ACCOUNT), listOf(unknown)) }
                }
            assertThrows<IllegalArgumentException> { CloudflareModelFactory(CloudflareClientOptions(ACCOUNT), listOf(CLEF, CLEF)) }
        }
    }
}

internal const val ACCOUNT = "0123456789abcdef0123456789abcdef"
internal const val CLEF = "@cf/cloudflare/clef"
internal const val FLASH = "@cf/cloudflare/clef-flash"
