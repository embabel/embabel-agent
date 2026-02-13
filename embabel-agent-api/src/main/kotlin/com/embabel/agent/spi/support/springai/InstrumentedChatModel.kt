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

import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.common.util.loggerFor
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux

/**
 * Decorator that intercepts [ChatModel.call] to emit a [ChatModelCallEvent]
 * containing the **actual final prompt** — including tool schemas, format
 * instructions, and structured-output schemas that Spring AI adds after
 * Embabel constructs its initial prompt.
 *
 * ### How it works```
 * [ChatClientLlmOperations.createChatClient]
 *   → wraps ChatModel in InstrumentedChatModel
 *     → ChatClient uses InstrumentedChatModel internally
 *       → [Spring AI augments the prompt: tool schemas, format, etc.]
 *       → InstrumentedChatModel.call(Prompt)
 *         → emits ChatModelCallEvent with the REAL final prompt
 *         → delegates to the underlying ChatModel
 * ```
 *
 * The [LlmRequestEvent] captured at construction time provides the domain
 * context ([AgentProcess], [LlmInteraction], etc.) needed for the event,
 * avoiding the need for any ThreadLocal propagation.
 *
 * @param delegate the actual ChatModel implementation to delegate to
 * @param llmRequestEvent the domain context for event emission
 *
 * @see ChatModelCallEvent
 * @see ChatClientLlmOperations.createChatClient
 */
internal class InstrumentedChatModel(
    private val delegate: ChatModel,
    private val llmRequestEvent: LlmRequestEvent<*>,
) : ChatModel {

    private val logger = loggerFor<InstrumentedChatModel>()

    /**
     * Intercepts the call to capture the complete final prompt and emit
     * a [ChatModelCallEvent], then delegates to the real [ChatModel].
     */
    override fun call(prompt: Prompt): ChatResponse {
        logger.debug(
            "Intercepted ChatModel.call for interaction '{}' — {} messages, options: {}",
            llmRequestEvent.interaction.id.value,
            prompt.instructions.size,
            prompt.options?.javaClass?.simpleName ?: "none",
        )

        llmRequestEvent.agentProcess.processContext.onProcessEvent(
            llmRequestEvent.chatModelCallEvent(prompt)
        )

        return delegate.call(prompt)
    }

    // -------------------------------------------------------------------
    // Explicit delegation for all non-abstract interface methods.
    //
    // We intentionally do NOT use Kotlin's `by` delegation here because
    // it does NOT delegate Java default methods to the delegate instance.
    // Instead it calls the interface's own default implementation, which
    // would silently break:
    //   - getDefaultOptions() would return a bare ChatOptions instead of
    //     the delegate's model-specific options (e.g. OpenAiChatOptions)
    //   - stream(Prompt) would throw UnsupportedOperationException
    //     instead of using the delegate's streaming implementation
    //
    // By implementing ChatModel directly (no `by`) any new method added
    // to the interface will cause a compile error, forcing an explicit
    // decision about whether to delegate or instrument.
    // -------------------------------------------------------------------

    override fun getDefaultOptions(): ChatOptions = delegate.defaultOptions

    override fun stream(prompt: Prompt): Flux<ChatResponse> = delegate.stream(prompt)
}
