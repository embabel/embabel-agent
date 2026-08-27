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
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionContext
import com.embabel.common.ai.model.ModelSelectionContextHolder
import com.embabel.common.ai.model.NoSuitableModelException
import com.embabel.common.ai.model.ProviderCredential
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

    private companion object {

        /**
         * A misspelling of `gpt-4.1-nano`. The point of #1899: in setup-required mode this is
         * indistinguishable at startup from a correctly spelled name awaiting a key, so the tests
         * below assert it reaches the operator by name rather than being swallowed.
         */
        const val TYPO_MODEL = "gpt-4.1-nanoo"

        /** A correctly spelled name, to sit alongside [TYPO_MODEL] where two roles are in play. */
        const val VALID_MODEL = "gpt-4.1"

        /** A provider this deployment holds no key for, used for the user-credential path. */
        const val OTHER_PROVIDER = "acme"

        /** Never sent anywhere - the credential path under test never reaches a provider. */
        const val TEST_API_KEY = "sk-test-not-a-real-key"
    }

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

    /*
     * The rest of this class pins #1899: in setup-required mode a typo in a model name and a key
     * that has not arrived are indistinguishable at startup, so the failure the operator eventually
     * sees has to carry enough to tell them apart. It cannot be caught earlier without a catalogue
     * of every model name the framework knows, which is deliberately not what this does.
     */

    @Test
    fun `the failure names the role and the model it wanted`() {
        // Without this, a typo'd name fails as a bare "no LLM is configured", pointing the operator
        // at the key - the one thing that is not wrong.
        val modelProvider = pureByok(mapOf(CHEAPEST_ROLE to TYPO_MODEL))
        val llm = modelProvider.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))

        assertThatThrownBy { ((llm as AiModel<*>).model as ChatModel).call(Prompt(listOf(UserMessage("hello")))) }
            .isInstanceOf(NoLlmConfiguredException::class.java)
            .hasMessageContaining(CHEAPEST_ROLE)
            .hasMessageContaining(TYPO_MODEL)
            .hasMessageContaining("withLlmService")
    }

    @Test
    fun `streaming carries the same named failure`() {
        // Chat applications are the ones that stream and the ones most likely to be BYOK, so the
        // named message has to reach the streaming path too.
        val modelProvider = pureByok(mapOf(CHEAPEST_ROLE to TYPO_MODEL))
        val llm = modelProvider.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))

        assertThatThrownBy { ((llm as AiModel<*>).model as ChatModel).stream(Prompt(listOf(UserMessage("hello")))) }
            .isInstanceOf(NoLlmConfiguredException::class.java)
            .hasMessageContaining(TYPO_MODEL)
    }

    @Test
    fun `naming the role does not rename the placeholder`() {
        /*
         * The service handed back is still the placeholder under its well-known name. Only the
         * message it fails with is specialised, so nothing that matches on the name - the model
         * provider's own default-llm lookup included - sees a different model.
         */
        val modelProvider = pureByok(mapOf(CHEAPEST_ROLE to TYPO_MODEL))

        assertThat(modelProvider.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE)).name)
            .isEqualTo(SetupRequiredLlm.NAME)
    }

    @Test
    fun `the default LLM keeps the general message, having no role to name`() {
        // Nothing asked for a role here, so there is no model name to report and the original
        // provider-neutral message is still the right one.
        val modelProvider = pureByok(mapOf(CHEAPEST_ROLE to TYPO_MODEL))
        val llm = modelProvider.getLlm(DefaultModelSelectionCriteria)

        assertThatThrownBy { ((llm as AiModel<*>).model as ChatModel).call(Prompt(listOf(UserMessage("hello")))) }
            .isInstanceOf(NoLlmConfiguredException::class.java)
            .hasMessage(SetupRequiredLlm.MESSAGE)
    }

    @Test
    fun `a role configured with no model at all still says which role failed`() {
        // The nested shape can name a role without naming a model under it. There is nothing to
        // report as "wanted", but the role is still worth naming.
        val modelProvider = ConfigurableModelProvider(
            llms = listOf(SetupRequiredLlm.llmService()),
            embeddingServices = emptyList(),
            properties = ConfigurableModelProviderProperties(defaultLlm = SetupRequiredLlm.NAME),
        )
        val llm = modelProvider.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))

        assertThatThrownBy { ((llm as AiModel<*>).model as ChatModel).call(Prompt(listOf(UserMessage("hello")))) }
            .isInstanceOf(NoLlmConfiguredException::class.java)
            .hasMessageContaining(CHEAPEST_ROLE)
    }

    @Test
    fun `a typo under the nested roles shape is named too, not only under the flat map`() {
        /*
         * The two shapes reach the placeholder by different routes, and the flat map is the one the
         * tests above use. Applying the naming to only one of them is the shape the original defect
         * in this area took - a rule that held for `llms` and not for `roles` - so it is worth
         * pinning rather than assuming.
         */
        val modelProvider = nestedRoleByok(mapOf(SetupRequiredLlm.PROVIDER to LlmOptions.withModel(TYPO_MODEL)))
        val llm = modelProvider.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))

        assertThatThrownBy { ((llm as AiModel<*>).model as ChatModel).call(Prompt(listOf(UserMessage("hello")))) }
            .isInstanceOf(NoLlmConfiguredException::class.java)
            .hasMessageContaining(CHEAPEST_ROLE)
            .hasMessageContaining(TYPO_MODEL)
    }

    @Test
    fun `a role reached through a user's key names the model configured for that provider`() {
        /*
         * The third route to the placeholder, and the only one where the wanted model cannot be read
         * off the resolution: `RoleResolution.Credential` carries a key, not a model, so the name has
         * to be looked up under the user's provider.
         *
         * Reached when the deployment ships no factory for that provider - the case #1935 removes for
         * OpenAI and Anthropic, and the case that remains for every other provider. Naming the model
         * matters more here, not less: the operator has supplied a key, so "no LLM is configured" is
         * the least useful thing the platform could say.
         */
        val modelProvider = nestedRoleByok(mapOf(OTHER_PROVIDER to LlmOptions.withModel("$OTHER_PROVIDER-mini-typo")))

        val llm = ModelSelectionContextHolder.with(
            ModelSelectionContext(credential = ProviderCredential(OTHER_PROVIDER, TEST_API_KEY)),
        ) {
            modelProvider.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))
        }

        assertThatThrownBy { ((llm as AiModel<*>).model as ChatModel).call(Prompt(listOf(UserMessage("hello")))) }
            .isInstanceOf(NoLlmConfiguredException::class.java)
            .hasMessageContaining(CHEAPEST_ROLE)
            .hasMessageContaining("$OTHER_PROVIDER-mini-typo")
    }

    @Test
    fun `two roles failing do not report each other's model`() {
        /*
         * One placeholder bean serves every call on any thread, so the role being stood in for
         * cannot be state on it. Specialising by mutation would pass every other test here and fail
         * only under concurrency, reporting whichever role happened to resolve most recently.
         */
        val modelProvider = pureByok(mapOf(BEST_ROLE to VALID_MODEL, CHEAPEST_ROLE to TYPO_MODEL))
        val best = modelProvider.getLlm(ByRoleModelSelectionCriteria(BEST_ROLE))
        val cheapest = modelProvider.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))

        assertThatThrownBy { ((best as AiModel<*>).model as ChatModel).call(Prompt(listOf(UserMessage("hello")))) }
            .hasMessageContaining(VALID_MODEL)
            .hasMessageNotContaining(TYPO_MODEL)
        assertThatThrownBy { ((cheapest as AiModel<*>).model as ChatModel).call(Prompt(listOf(UserMessage("hello")))) }
            .hasMessageContaining(TYPO_MODEL)
    }

    @Test
    fun `the registered placeholder bean is left alone by all of this`() {
        // The corollary of the test above, from the other side: whatever roles have resolved, the
        // bean an application injects still fails with the general message.
        val modelProvider = pureByok(mapOf(CHEAPEST_ROLE to TYPO_MODEL))
        modelProvider.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE))

        val registered = SetupRequiredLlm.llmService()
        assertThatThrownBy {
            ((registered as AiModel<*>).model as ChatModel).call(Prompt(listOf(UserMessage("hello"))))
        }.hasMessage(SetupRequiredLlm.MESSAGE)
    }

    @Test
    fun `a keyed deployment still throws rather than naming anything`() {
        /*
         * The naming exists because setup-required mode cannot tell a typo from a missing key. A
         * deployment holding a key has no such excuse: an unsatisfiable role there is a real
         * misconfiguration and must keep failing with NoSuitableModelException, not resolve to a
         * placeholder carrying a friendly message.
         */
        val real = SpringAiLlmService(name = "real-model", provider = "acme", chatModel = SetupRequiredChatModel())
        val modelProvider = ConfigurableModelProvider(
            llms = listOf(real),
            embeddingServices = emptyList(),
            properties = ConfigurableModelProviderProperties(defaultLlm = "real-model"),
        )

        assertThatThrownBy { modelProvider.getLlm(ByRoleModelSelectionCriteria(CHEAPEST_ROLE)) }
            .isInstanceOf(NoSuitableModelException::class.java)
    }

    @Test
    fun `the named message is not mangled by the indentation of its own source`() {
        /*
         * Asserted in full rather than with hasMessageContaining, because every other test here
         * matches on substrings and a substring match cannot see leading whitespace. This message
         * is assembled from several trimIndent blocks, and interpolating one already-trimmed block
         * into another drags the outer common indent to zero and leaves every other line indented
         * to its position in the source file. That is invisible to a `contains` assertion and
         * lands in operator-facing logs.
         */
        val message = SetupRequiredLlm.messageFor(CHEAPEST_ROLE, TYPO_MODEL)

        assertThat(message).isEqualTo(
            """
                No LLM is configured. Role 'cheapest' wanted model '$TYPO_MODEL', which nothing has registered.
                This deployment holds no provider API key, so a key must be supplied per request
                via PromptRunner.withLlmService(...).
                If a key HAS been supplied and this persists, check that '$TYPO_MODEL' is spelled correctly
                and is a model that provider offers - a name that never resolves fails exactly like
                a missing key.
                See the Bring Your Own Key section of the Embabel reference documentation.
            """.trimIndent(),
        )
    }

    @Test
    fun `the message for a role naming no model is not mangled either`() {
        // The branch that omits the spelling paragraph entirely, so the join has a gap to get wrong.
        val message = SetupRequiredLlm.messageFor(CHEAPEST_ROLE, null)

        assertThat(message).isEqualTo(
            """
                No LLM is configured. Role 'cheapest' names no model, so nothing can satisfy it.
                This deployment holds no provider API key, so a key must be supplied per request
                via PromptRunner.withLlmService(...).
                See the Bring Your Own Key section of the Embabel reference documentation.
            """.trimIndent(),
        )
    }

    @Test
    fun `no line of the named message carries leading whitespace`() {
        // The general form of the two assertions above: whatever the wording becomes, a line that
        // starts with a space means a trimIndent has been defeated somewhere.
        val message = SetupRequiredLlm.messageFor(CHEAPEST_ROLE, TYPO_MODEL)

        assertThat(message.lines()).allSatisfy { line ->
            assertThat(line).doesNotStartWith(" ")
        }
    }

    /**
     * A pure BYOK deployment configuring [CHEAPEST_ROLE] through the nested per-provider shape,
     * rather than the flat `embabel.models.llms` map [pureByok] uses.
     *
     * @param byProvider provider name to the options that role carries under it
     */
    private fun nestedRoleByok(byProvider: Map<String, LlmOptions>) = ConfigurableModelProvider(
        llms = listOf(SetupRequiredLlm.llmService()),
        embeddingServices = emptyList(),
        properties = ConfigurableModelProviderProperties(
            defaultLlm = SetupRequiredLlm.NAME,
            roles = mapOf(CHEAPEST_ROLE to byProvider),
        ),
    )
}
