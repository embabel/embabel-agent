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

import com.embabel.common.ai.model.ByNameModelSelectionCriteria
import com.embabel.common.ai.model.ConfigurableModelProvider
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.DefaultModelSelectionCriteria
import com.embabel.agent.spi.PlaceholderLlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.AiModel
import com.embabel.common.ai.prompt.PromptContributor
import org.springframework.ai.chat.model.ChatModel
import com.embabel.common.ai.model.LlmOptions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import java.time.LocalDate

/**
 * The placeholder exists to move the "no key" failure from context refresh to the call that
 * needs an LLM. These tests pin both halves of that: the model provider resolves, and the call
 * fails with something an operator can act on.
 */
class SetupRequiredLlmTest {

    @Test
    fun `placeholder is named by the well-known constant`() {
        /*
         * Not asserting the literal "setup-required": that is the constant restating itself.
         * What has to hold is that the service reports the name and provider the constants
         * declare, since that is what `embabel.models.default-llm` matches against.
         */
        val service = SetupRequiredLlm.llmService()
        assertThat(service.name).isEqualTo(SetupRequiredLlm.NAME)
        assertThat(service.provider).isEqualTo(SetupRequiredLlm.PROVIDER)
        /*
         * The marker is what puts the platform into setup-required mode, so an unresolvable model
         * name in configuration is treated as "awaiting a key" rather than as a typo. Losing it
         * would not fail here - it would quietly restore fail-fast startup for BYOK deployments.
         */
        assertThat(service).isInstanceOf(PlaceholderLlmService::class.java)
    }

    @Test
    fun `model provider with only the placeholder resolves the default LLM`() {
        val modelProvider = ConfigurableModelProvider(
            llms = listOf(SetupRequiredLlm.llmService()),
            embeddingServices = emptyList(),
            properties = ConfigurableModelProviderProperties(defaultLlm = SetupRequiredLlm.NAME),
        )

        assertThat(modelProvider.getLlm(DefaultModelSelectionCriteria).name).isEqualTo(SetupRequiredLlm.NAME)
        assertThat(modelProvider.getLlm(ByNameModelSelectionCriteria(SetupRequiredLlm.NAME)).name)
            .isEqualTo(SetupRequiredLlm.NAME)
    }

    @Test
    fun `the placeholder is never selected unless it is named`() {
        val real = SpringAiLlmService(name = "real-model", provider = "acme", chatModel = SetupRequiredChatModel())
        val modelProvider = ConfigurableModelProvider(
            llms = listOf(SetupRequiredLlm.llmService(), real),
            embeddingServices = emptyList(),
            properties = ConfigurableModelProviderProperties(defaultLlm = "real-model"),
        )

        assertThat(modelProvider.getLlm(DefaultModelSelectionCriteria).name).isEqualTo("real-model")
    }

    @Test
    fun `calling the placeholder fails with an actionable error rather than an empty completion`() {
        val service = SetupRequiredLlm.llmService()

        assertThatThrownBy { ((service as AiModel<*>).model as ChatModel).call(Prompt(listOf(UserMessage("hello")))) }
            .isInstanceOf(NoLlmConfiguredException::class.java)
            .hasMessageContaining("withLlmService")
    }

    @Test
    fun `streaming fails the same way, not with an unrelated streaming error`() {
        /*
         * Spring AI's default stream() throws UnsupportedOperationException("streaming is not
         * supported"), which is both untrue here and uncatchable by an application watching for
         * NoLlmConfiguredException — in a chat app, exactly where the message is needed.
         */
        val service = SetupRequiredLlm.llmService()

        assertThatThrownBy { ((service as AiModel<*>).model as ChatModel).stream(Prompt(listOf(UserMessage("hello")))) }
            .isInstanceOf(NoLlmConfiguredException::class.java)
            .hasMessageContaining("withLlmService")
    }

    @Test
    fun `the platform still reports the placeholder as not supporting streaming`() {
        val service = SetupRequiredLlm.llmService()
        assertThat(service.supportsStreaming()).isFalse()
        assertThat(service.supportsStreaming()).isFalse()
    }

    @Test
    fun `a message sender can be created, so failure happens at call time not setup time`() {
        val service = SetupRequiredLlm.llmService()
        assertThat(service.createMessageSender(LlmOptions())).isNotNull()
        assertThat(service.createMessageStreamer(LlmOptions())).isNotNull()
    }

    @Test
    fun `the marker survives the self-typed methods`() {
        /*
         * The reason this class wraps SpringAiLlmService by hand rather than delegating with `by`:
         * delegated self-typed methods hand back the bare delegate, dropping the marker. Losing it
         * is silent - the deployment simply stops being in setup-required mode and goes back to
         * failing at startup on names it cannot resolve, which is what this PR exists to prevent.
         */
        val service = SetupRequiredLlm.llmService()

        val withContributor = service.withPromptContributor(PromptContributor.fixed("extra"))
        assertThat(withContributor).isInstanceOf(PlaceholderLlmService::class.java)
        assertThat(withContributor.promptContributors).hasSize(service.promptContributors.size + 1)

        val withCutoff = service.withKnowledgeCutoffDate(LocalDate.of(2026, 1, 1))
        assertThat(withCutoff).isInstanceOf(PlaceholderLlmService::class.java)
        assertThat(withCutoff.knowledgeCutoffDate).isEqualTo(LocalDate.of(2026, 1, 1))
    }

    @Test
    fun `the placeholder does not claim to support thinking`() {
        assertThat(SetupRequiredLlm.llmService().supportsThinking()).isFalse()
    }

    @Test
    fun `it names itself in logs rather than reporting a delegate`() {
        assertThat(SetupRequiredLlm.llmService().toString()).contains(SetupRequiredLlm.NAME)
    }
}
