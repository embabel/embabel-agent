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
package com.embabel.common.byok

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DetectProviderTest {

    @Test
    fun `only successful candidate is returned`() {
        val service = "winning-service"
        val result = detectProvider(
            ByokFactory<String> { throw InvalidApiKeyException("bad key") },
            ByokFactory<String> { service },
        )
        assertSame(service, result)
    }

    @Test
    fun `all candidates fail throws InvalidApiKeyException`() {
        assertThrows<InvalidApiKeyException> {
            detectProvider(
                ByokFactory<String> { throw InvalidApiKeyException("bad") },
                ByokFactory<String> { throw InvalidApiKeyException("bad") },
                ByokFactory<String> { throw InvalidApiKeyException("bad") },
                ByokFactory<String> { throw InvalidApiKeyException("bad") },
            )
        }
    }

    @Test
    fun `single candidate returns service - settings flow`() {
        val service = "only-service"
        val result = detectProvider(ByokFactory<String> { service })
        assertSame(service, result)
    }

    @Test
    fun `no candidates throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            detectProvider<Any>()
        }
    }

    @Test
    fun `returned service is the one from the winning factory`() {
        val winner = "anthropic-service"
        val result = detectProvider(
            ByokFactory<String> { winner },
            ByokFactory<String> { throw InvalidApiKeyException("bad") },
        )
        assertSame(winner, result)
    }
}
