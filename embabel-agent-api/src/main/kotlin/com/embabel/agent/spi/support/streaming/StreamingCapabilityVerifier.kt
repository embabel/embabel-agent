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

import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * Live probe of whether a [ChatModel] actually streams.
 *
 * Spring AI's ChatModel interface extends StreamingChatModel, but some implementations throw
 * [UnsupportedOperationException] from [ChatModel.stream]. This object performs that check;
 * [StreamingCapabilityDetector] decides whether to cache the answer.
 *
 * Only exceptions thrown directly from [ChatModel.stream] are treated as capability signals;
 * errors emitted by the returned Flux are ignored.
 */
internal object StreamingCapabilityVerifier {
    private const val TEST_PROMPT_MESSAGE = "Say 'test' to confirm streaming works"
    private const val STREAMING_TEST_TIMEOUT_MS = 100L

    fun probe(chatModel: ChatModel) {
        val testRequest = Prompt(listOf(UserMessage(TEST_PROMPT_MESSAGE)))
        val stream = chatModel.stream(testRequest)
        consumeStream(stream)
    }

    private fun consumeStream(stream: Flux<ChatResponse>) {
        try {
            stream.hasElements()
                .timeout(Duration.ofMillis(STREAMING_TEST_TIMEOUT_MS))
                .block()
        } catch (_: Exception) {
            // stream() itself succeeded; a timeout or empty flux is not "unsupported"
        }
    }
}
