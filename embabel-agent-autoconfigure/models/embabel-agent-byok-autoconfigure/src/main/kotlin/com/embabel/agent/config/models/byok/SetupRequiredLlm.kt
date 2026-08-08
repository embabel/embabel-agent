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
package com.embabel.agent.config.models.byok

import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt

/**
 * Thrown when a prompt reaches the [SetupRequiredLlm] placeholder, meaning no real LLM was
 * available for the call: the deployment has no server-side key and the caller did not supply
 * one with [com.embabel.agent.api.common.PromptRunner.withLlmService].
 *
 * Catch this to render your own "add an API key" experience. The placeholder deliberately
 * fails loudly rather than returning an empty completion, so a missing key surfaces as an
 * actionable error rather than a silently empty answer.
 */
class NoLlmConfiguredException(message: String) : RuntimeException(message)

/**
 * A [ChatModel] that never completes anything. It exists only so the platform has a model to
 * resolve before any key is supplied; every call fails with [NoLlmConfiguredException].
 */
internal class SetupRequiredChatModel : ChatModel {

    override fun call(prompt: Prompt): ChatResponse =
        throw NoLlmConfiguredException(SetupRequiredLlm.MESSAGE)

    override fun getOptions(): ChatOptions = ChatOptions.builder().build()

    override fun toString(): String = javaClass.simpleName
}

/**
 * The placeholder LLM for a pure BYOK deployment — one that starts with no provider key at all
 * and obtains a key per user, per tenant, or per request at runtime.
 *
 * Such a deployment has no [LlmService] beans at startup, so
 * [com.embabel.common.ai.model.ConfigurableModelProvider] cannot resolve `embabel.models.default-llm`
 * and the context never refreshes. Registering this placeholder and pointing `default-llm` at it
 * gives the platform something to resolve, deferring the "no key" failure from startup to the
 * first call that actually needs an LLM.
 *
 * Opt in by putting `embabel-agent-starter-byok` on the classpath and configuring:
 * ```yaml
 * embabel:
 *   models:
 *     default-llm: setup-required
 * ```
 *
 * Real work bypasses the placeholder entirely: resolve a key, build a service with
 * [com.embabel.agent.anthropic.AnthropicModelFactory] or
 * [com.embabel.agent.openai.OpenAiCompatibleModelFactory], and pass it to
 * [com.embabel.agent.api.common.PromptRunner.withLlmService].
 */
object SetupRequiredLlm {

    /**
     * The well-known name of the placeholder, as both the bean name and the [LlmService] name.
     * Use it as the value of `embabel.models.default-llm`.
     */
    const val NAME: String = "setup-required"

    /**
     * Provider reported by the placeholder. Not a real provider — no key reaches any endpoint.
     */
    const val PROVIDER: String = "none"

    /**
     * Message carried by [NoLlmConfiguredException]. Deliberately provider-neutral: an
     * application knows which providers it accepts and how a user supplies a key, so it should
     * catch the exception and say so in its own words rather than surface this verbatim.
     */
    const val MESSAGE: String =
        "No LLM is configured. This deployment holds no provider API key, so a key must be " +
            "supplied per request via PromptRunner.withLlmService(...). See the Bring Your Own " +
            "Key section of the Embabel reference documentation."

    /**
     * Builds the placeholder service. Registered as a bean by [SetupRequiredLlmConfig]; call this
     * directly only when constructing a model provider outside a Spring context.
     */
    fun llmService(): LlmService<*> = SpringAiLlmService(
        name = NAME,
        provider = PROVIDER,
        chatModel = SetupRequiredChatModel(),
    )
}
