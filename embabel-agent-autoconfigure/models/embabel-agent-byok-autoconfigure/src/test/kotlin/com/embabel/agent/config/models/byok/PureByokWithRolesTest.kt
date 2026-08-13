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

import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.AiModel
import com.embabel.common.ai.model.ByRoleModelSelectionCriteria
import com.embabel.common.ai.model.ConfigurableModelProvider
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.DefaultModelSelectionCriteria
import com.embabel.common.ai.model.NoSuitableModelException
import com.embabel.common.ai.model.ModelProvider.Companion.BEST_ROLE
import com.embabel.common.ai.model.ModelProvider.Companion.CHEAPEST_ROLE
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt

/**
 * A pure BYOK deployment holds no provider key, so no model a role names is registered — and
 * roles are ordinary configuration that such a deployment may well already have.
 *
 * Treating that as fatal made the starter unable to do the one thing it exists for: an
 * application whose `application.yml` named any role at all failed context refresh with
 * "LLM 'x' for role y is not available", no matter that `default-llm` pointed at the
 * placeholder.
 */
class PureByokWithRolesTest {

    private fun pureByok(roles: Map<String, String>) = ConfigurableModelProvider(
        llms = listOf(SetupRequiredLlm.llmService()),
        embeddingServices = emptyList(),
        properties = ConfigurableModelProviderProperties(
            llms = roles,
            defaultLlm = SetupRequiredLlm.NAME,
        ),
    )

    @Test
    fun `starts with roles configured and no model to satisfy them`() {
        assertThatCode {
            pureByok(mapOf(BEST_ROLE to "gpt-4.1", CHEAPEST_ROLE to "gpt-4.1-nano"))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `still resolves the placeholder as the default`() {
        val modelProvider = pureByok(mapOf(BEST_ROLE to "gpt-4.1"))

        assertThat(modelProvider.getLlm(DefaultModelSelectionCriteria).name)
            .isEqualTo(SetupRequiredLlm.NAME)
    }

    @Test
    fun `a deployment that holds a key still dies at startup on a name nothing registers`() {
        /*
         * The other half of the rule, and the reason the relaxation is gated rather than
         * unconditional: making this a warning too would fix BYOK by making every keyed deployment
         * worse, turning a typo into a late failure at whichever call first wanted that role.
         */
        val real = SpringAiLlmService(name = "real-model", provider = "acme", chatModel = SetupRequiredChatModel())

        assertThatThrownBy {
            ConfigurableModelProvider(
                llms = listOf(SetupRequiredLlm.llmService(), real),
                embeddingServices = emptyList(),
                properties = ConfigurableModelProviderProperties(
                    llms = mapOf(BEST_ROLE to "gpt-4.1"),
                    defaultLlm = "real-model",
                ),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("gpt-4.1")
    }

    @Test
    fun `default-llm naming an unregistered model falls back to the placeholder`() {
        /*
         * The realistic pure-BYOK application.yml: default-llm still names the model the deployment
         * wants once a key arrives. Nothing registers it yet, so the placeholder stands in and that
         * is what puts the deployment into setup-required mode.
         */
        val modelProvider = ConfigurableModelProvider(
            llms = listOf(SetupRequiredLlm.llmService()),
            embeddingServices = emptyList(),
            properties = ConfigurableModelProviderProperties(
                llms = mapOf(BEST_ROLE to "gpt-4.1"),
                defaultLlm = "gpt-4.1",
            ),
        )

        assertThat(modelProvider.getLlm(DefaultModelSelectionCriteria).name)
            .isEqualTo(SetupRequiredLlm.NAME)
    }

    @Test
    fun `an unresolvable embedding role is tolerated while awaiting a key`() {
        assertThatCode {
            ConfigurableModelProvider(
                llms = listOf(SetupRequiredLlm.llmService()),
                embeddingServices = emptyList(),
                properties = ConfigurableModelProviderProperties(
                    embeddingServices = mapOf("default" to "text-embedding-3-small"),
                    defaultLlm = SetupRequiredLlm.NAME,
                ),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `an unresolvable embedding role is still fatal for a deployment holding a key`() {
        // Same gate as the LLM roles, and no fallback: there is no embedding placeholder and there
        // should not be one, so the gate decides only whether the deployment starts.
        val real = SpringAiLlmService(name = "real-model", provider = "acme", chatModel = SetupRequiredChatModel())

        assertThatThrownBy {
            ConfigurableModelProvider(
                llms = listOf(real),
                embeddingServices = emptyList(),
                properties = ConfigurableModelProviderProperties(
                    embeddingServices = mapOf("default" to "text-embedding-3-small"),
                    defaultLlm = "real-model",
                ),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("text-embedding-3-small")
    }

    @Test
    fun `a role awaiting a key resolves to the placeholder, like the default LLM already does`() {
        /*
         * This test used to require NoSuitableModelException. The concern behind it stands - "no key
         * configured" must never become an empty or broken answer somewhere later - but throwing
         * here is the wrong way to serve it, for two reasons.
         *
         * The exception reports the wrong problem. It names the role and lists what IS registered,
         * which in a pure BYOK deployment is the placeholder alone: "no model for role best,
         * available: setup-required". The actual problem is that no key has been set, and that is
         * not what the reader is told.
         *
         * And it makes a role behave differently from the default LLM in the one deployment where
         * they are in the same position. `default-llm` already resolves to the placeholder here -
         * see the two tests above - so a role that throws is the odd one out.
         *
         * Nothing is silent either way: the placeholder is not a working model, and the next test
         * pins what happens when a prompt actually reaches it.
         */
        val modelProvider = pureByok(mapOf(BEST_ROLE to "gpt-4.1"))

        assertThat(modelProvider.getLlm(ByRoleModelSelectionCriteria(BEST_ROLE)).name)
            .isEqualTo(SetupRequiredLlm.NAME)
    }

    @Test
    fun `and using that role fails with the message that says to add a key`() {
        // The half of the old assertion that mattered: resolving is tolerant, USING it is not. The
        // deployment gets the actionable error rather than a plausible-looking answer.
        val modelProvider = pureByok(mapOf(BEST_ROLE to "gpt-4.1"))
        val llm = modelProvider.getLlm(ByRoleModelSelectionCriteria(BEST_ROLE))

        assertThatThrownBy { ((llm as AiModel<*>).model as ChatModel).call(Prompt(listOf(UserMessage("hello")))) }
            .isInstanceOf(NoLlmConfiguredException::class.java)
            .hasMessageContaining("withLlmService")
    }
}
