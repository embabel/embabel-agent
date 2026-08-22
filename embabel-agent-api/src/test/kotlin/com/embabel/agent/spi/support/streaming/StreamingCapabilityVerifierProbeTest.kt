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
package com.embabel.agent.spi.support.streaming

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import kotlin.test.assertTrue

class StreamingCapabilityVerifierProbeTest {

    @Test
    fun `probe sends the streaming confirmation prompt`() {
        val chatModel = CountingChatModel { Flux.just(chatResponse("ok")) }

        StreamingCapabilityVerifier.probe(chatModel)

        val contents = chatModel.lastPrompt?.instructions?.map { it.text.orEmpty() } ?: emptyList()
        assertTrue(contents.any { it.contains("confirm streaming works") })
    }

    @Test
    fun `empty flux is not treated as unsupported`() {
        val chatModel = CountingChatModel { Flux.empty() }

        assertDoesNotThrow { StreamingCapabilityVerifier.probe(chatModel) }
    }

    @Test
    fun `a stream that never emits is not treated as unsupported`() {
        val chatModel = CountingChatModel { Flux.never() }

        assertDoesNotThrow { StreamingCapabilityVerifier.probe(chatModel) }
    }

    @Test
    fun `errors after stream returns are not treated as unsupported`() {
        val chatModel = CountingChatModel {
            Flux.error(UnsupportedOperationException("lazy unsupported"))
        }

        assertDoesNotThrow { StreamingCapabilityVerifier.probe(chatModel) }
    }

    @Test
    fun `UnsupportedOperationException from stream itself propagates`() {
        val chatModel = CountingChatModel {
            throw UnsupportedOperationException("streaming not supported")
        }

        assertThrows<UnsupportedOperationException> {
            StreamingCapabilityVerifier.probe(chatModel)
        }
    }

    @Test
    fun `other exceptions from stream itself propagate`() {
        val chatModel = CountingChatModel {
            throw RuntimeException("provider unreachable")
        }

        assertThrows<RuntimeException> {
            StreamingCapabilityVerifier.probe(chatModel)
        }
    }
}
