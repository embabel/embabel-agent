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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.spi.support.streaming.CountingChatModel
import com.embabel.agent.spi.support.streaming.DefaultStreamChatModel
import com.embabel.agent.spi.support.streaming.StreamingCapabilityDetector
import com.embabel.agent.spi.support.streaming.chatResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger

class StreamingCapabilityVerifierTest {

    @AfterEach
    fun clearCache() {
        StreamingCapabilityDetector.clearCache()
    }

    @Test
    fun `probes once per ChatModel instance when streaming is supported`() {
        val chatModel = CountingChatModel { Flux.just(chatResponse("ok")) }
        val service = SpringAiLlmService(
            name = "streaming",
            provider = "test",
            chatModel = chatModel,
        )

        assertThat(service.supportsStreaming()).isTrue()
        assertThat(service.supportsStreaming()).isTrue()
        assertThat(service.supportsStreaming()).isTrue()

        assertThat(chatModel.streamCalls.get()).isEqualTo(1)
    }

    @Test
    fun `probes once per ChatModel instance when streaming is unsupported`() {
        val chatModel = CountingChatModel {
            throw UnsupportedOperationException("streaming not supported")
        }
        val service = SpringAiLlmService(
            name = "non-streaming",
            provider = "test",
            chatModel = chatModel,
        )

        assertThat(service.supportsStreaming()).isFalse()
        assertThat(service.supportsStreaming()).isFalse()

        assertThat(chatModel.streamCalls.get()).isEqualTo(1)
    }

    @Test
    fun `non-capability failures return false and are not cached`() {
        val chatModel = CountingChatModel {
            throw RuntimeException("provider unreachable")
        }
        val service = SpringAiLlmService(
            name = "flaky",
            provider = "test",
            chatModel = chatModel,
        )

        assertThat(service.supportsStreaming()).isFalse()
        assertThat(service.supportsStreaming()).isFalse()

        assertThat(chatModel.streamCalls.get()).isEqualTo(2)
    }

    @Test
    fun `a later successful probe is cached after a transient failure`() {
        val failuresLeft = AtomicInteger(1)
        val chatModel = CountingChatModel {
            if (failuresLeft.getAndDecrement() > 0) {
                throw RuntimeException("provider unreachable")
            }
            Flux.just(chatResponse("ok"))
        }
        val service = SpringAiLlmService(
            name = "recovering",
            provider = "test",
            chatModel = chatModel,
        )

        assertThat(service.supportsStreaming()).isFalse()
        assertThat(service.supportsStreaming()).isTrue()
        assertThat(service.supportsStreaming()).isTrue()

        assertThat(chatModel.streamCalls.get()).isEqualTo(2)
    }

    @Test
    fun `distinct ChatModel instances are probed separately`() {
        val first = CountingChatModel { Flux.just(chatResponse("a")) }
        val second = CountingChatModel { Flux.just(chatResponse("b")) }

        assertThat(
            SpringAiLlmService(name = "one", provider = "test", chatModel = first).supportsStreaming()
        ).isTrue()
        assertThat(
            SpringAiLlmService(name = "two", provider = "test", chatModel = second).supportsStreaming()
        ).isTrue()

        assertThat(first.streamCalls.get()).isEqualTo(1)
        assertThat(second.streamCalls.get()).isEqualTo(1)
    }

    @Test
    fun `ChatModel default stream is reported as unsupported`() {
        val service = SpringAiLlmService(
            name = "default-stream",
            provider = "test",
            chatModel = DefaultStreamChatModel(),
        )

        assertThat(service.supportsStreaming()).isFalse()
        assertThat(service.supportsStreaming()).isFalse()
    }

    @Test
    fun `LlmService copies wrapping the same ChatModel do not probe again`() {
        val chatModel = CountingChatModel { Flux.just(chatResponse("ok")) }
        val original = SpringAiLlmService(name = "original", provider = "test", chatModel = chatModel)

        assertThat(original.supportsStreaming()).isTrue()
        assertThat(original.copy(name = "renamed").supportsStreaming()).isTrue()

        assertThat(chatModel.streamCalls.get()).isEqualTo(1)
    }
}
