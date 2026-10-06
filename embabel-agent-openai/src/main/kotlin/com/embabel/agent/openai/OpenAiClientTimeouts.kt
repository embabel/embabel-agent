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

import com.embabel.agent.common.http.ClientTimeoutProperties
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.openai.core.Timeout
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How long an OpenAI-compatible client waits to connect, and then for a response.
 *
 * [read] bounds the whole call, from sending the request to reading the last byte of the
 * response, rather than the gap between two reads. A model that sends nothing until it has
 * finished - an embedding batch, a non-streamed completion - is therefore bounded by it.
 *
 * A null [read] changes nothing. Chat completion and embedding calls then keep Spring AI's own
 * per-call timeout of 60 seconds, which it sends with every such call. Responses API models, and
 * any call made without Spring AI's options, keep the client's 10 minutes. A configured [read]
 * replaces both.
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

    /**
     * Chat options for [model], carrying [read] when it is set so that Spring AI's per-call
     * default does not override it.
     */
    fun chatOptions(model: String): OpenAiChatOptions.Builder =
        OpenAiChatOptions.builder()
            .model(model)
            .apply { read?.let { timeout(it) } }

    /**
     * Embedding options for [model], carrying [read] when it is set, for the same reason as
     * [chatOptions].
     */
    fun embeddingOptions(model: String): OpenAiEmbeddingOptions.Builder =
        OpenAiEmbeddingOptions.builder()
            .model(model)
            .apply { read?.let { timeout(it) } }

    companion object {

        /**
         * The openai-java SDK's default.
         */
        @JvmField
        val DEFAULT_CONNECT: Duration = Duration.ofMinutes(1)

        /**
         * The openai-java SDK's default, which Responses API models and calls without Spring AI's options see.
         */
        private val CLIENT_DEFAULT_READ: Duration = Duration.ofMinutes(10)

        @JvmField
        val DEFAULT = OpenAiClientTimeouts()
    }
}

/**
 * Properties shared by every OpenAI-compatible provider. Each provider's properties class
 * extends this and binds it under its own prefix, so `connect-timeout` and `read-timeout` are
 * set per provider while the fields, defaults and documentation live here once.
 */
abstract class OpenAiCompatibleClientProperties : ClientTimeoutProperties() {

    /**
     * How long to wait to connect to the provider.
     */
    override var connectTimeout: Duration? = OpenAiClientTimeouts.DEFAULT_CONNECT

    /**
     * The per-attempt response timeout: how long one attempt may take, from sending the request
     * to reading the whole response. Raise it for a slow model or a large embedding batch. Unset
     * keeps 60 seconds for chat completion and embedding calls, Spring AI's per-call default, and
     * 10 minutes for Responses API models, the client's. The client retries a timed-out call
     * twice, so a caller can wait up to three times this value.
     */
    override var readTimeout: Duration? = null

    fun clientTimeouts(): OpenAiClientTimeouts =
        OpenAiClientTimeouts(connectTimeout ?: OpenAiClientTimeouts.DEFAULT_CONNECT, readTimeout)
}

/**
 * Wraps a provider's converter so the chat options it produces carry the configured read timeout.
 * Reached only through [OpenAiClientTimeouts.optionsConverter].
 *
 * Options that are not [OpenAiChatOptions] have nowhere to carry the timeout, so they pass through
 * unchanged and the client-level timeout alone applies. That is logged once per converter, since
 * the configured value then bounds each attempt only through the client.
 */
internal class OpenAiReadTimeoutOptionsConverter(
    private val delegate: OptionsConverter,
    private val readTimeout: Duration,
) : OptionsConverter {

    private val warnedUnsupportedOptions = AtomicBoolean()

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions {
        val converted = delegate.convertOptions(options, model)
        if (converted is OpenAiChatOptions) {
            return converted.mutate().timeout(readTimeout).build()
        }
        if (warnedUnsupportedOptions.compareAndSet(false, true)) {
            logger.warn(
                "{} produced {} for model {}, which cannot carry a per-call timeout; only the client timeout of {} applies",
                delegate::class.java.simpleName,
                converted::class.java.simpleName,
                model,
                readTimeout,
            )
        }
        return converted
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(OpenAiReadTimeoutOptionsConverter::class.java)
    }
}
