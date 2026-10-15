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
package com.embabel.agent.openai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class OpenAiCompatibleClientPropertiesTest {

    private class ProviderProperties : OpenAiCompatibleClientProperties()

    @Test
    fun `unset timeouts give the default client timeouts`() {
        assertEquals(OpenAiClientTimeouts.DEFAULT, ProviderProperties().clientTimeouts())
    }

    @Test
    fun `a connect timeout set to null falls back to the SDK default`() {
        val properties = ProviderProperties().apply {
            connectTimeout = null
            readTimeout = Duration.ofMinutes(3)
        }

        assertEquals(
            OpenAiClientTimeouts(connect = OpenAiClientTimeouts.DEFAULT_CONNECT, read = Duration.ofMinutes(3)),
            properties.clientTimeouts(),
        )
    }

    @Test
    fun `configured timeouts reach the client timeouts`() {
        val properties = ProviderProperties().apply {
            connectTimeout = Duration.ofSeconds(4)
            readTimeout = Duration.ofMinutes(7)
        }

        assertEquals(
            OpenAiClientTimeouts(connect = Duration.ofSeconds(4), read = Duration.ofMinutes(7)),
            properties.clientTimeouts(),
        )
    }
}
