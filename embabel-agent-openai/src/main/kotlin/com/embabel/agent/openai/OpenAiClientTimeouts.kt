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

import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.openai.core.Timeout
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.openai.OpenAiChatOptions
import java.time.Duration

/**
 * How long an OpenAI-compatible client waits to connect, and then for a response.
 *
 * [read] bounds the whole call, from sending the request to reading the last byte of the
 * response, rather than the gap between two reads. A model that sends nothing until it has
 * finished - an embedding batch, a non-streamed completion - is therefore bounded by it.
 *
 * A null [read] changes nothing. Spring AI then sends its own per-call timeout of 60 seconds
 * with every chat completion and embedding, and that, not the client's 10 minutes, is what
 * bounds them. A configured [read] replaces both.
 *
 * The openai-java SDK retries a call that times out, twice by default, so a caller sees the
 * failure only after three attempts: up to three times the timeout, plus a short backoff.
 */
data class OpenAiClientTimeouts @JvmOverloads constructor(
    val connect: Duration = DEFAULT_CONNECT,
    val read: Duration? = null,
) {

    /**
     * The client-level timeout. The SDK derives its read and write timeouts from the request
     * timeout, so setting that bounds all three.
     */
    fun toSdkTimeout(): Timeout =
        Timeout.builder()
            .connect(connect)
            .request(read ?: CLIENT_DEFAULT_READ)
            .build()

    /**
     * [delegate], with the chat options it produces carrying [read], so that Spring AI's
     * per-call default does not override it. [delegate] itself when [read] is unset.
     */
    fun optionsConverter(delegate: OptionsConverter): OptionsConverter =
        read?.let { OpenAiReadTimeoutOptionsConverter(delegate, it) } ?: delegate

    companion object {

        /**
         * The openai-java SDK's default.
         */
        @JvmField
        val DEFAULT_CONNECT: Duration = Duration.ofMinutes(1)

        /**
         * The openai-java SDK's default, which only a call without Spring AI's options sees.
         */
        private val CLIENT_DEFAULT_READ: Duration = Duration.ofMinutes(10)

        @JvmField
        val DEFAULT = OpenAiClientTimeouts()
    }
}

/**
 * Configuration that sets the timeouts of an OpenAI-compatible client, under the provider's
 * own property prefix as `connect-timeout` and `read-timeout`.
 */
interface OpenAiClientTimeoutProperties {

    val connectTimeout: Duration

    val readTimeout: Duration?

    fun clientTimeouts(): OpenAiClientTimeouts = OpenAiClientTimeouts(connectTimeout, readTimeout)
}

/**
 * Wraps a provider's converter so the chat options it produces carry the configured read timeout.
 * Reached only through [OpenAiClientTimeouts.optionsConverter].
 */
internal class OpenAiReadTimeoutOptionsConverter(
    private val delegate: OptionsConverter,
    private val readTimeout: Duration,
) : OptionsConverter {

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions {
        val converted = delegate.convertOptions(options, model)
        return (converted as? OpenAiChatOptions)?.mutate()?.timeout(readTimeout)?.build() ?: converted
    }
}
