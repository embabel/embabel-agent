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
package com.embabel.common.ai.model

import org.springframework.core.Ordered

/**
 * The platform's own [RoleResolver], consulted after any the application registers.
 *
 * Reads `embabel.models.roles` - role, then provider, then options:
 *
 * ```yaml
 * embabel:
 *   models:
 *     roles:
 *       cheapest:
 *         openai:    { model: gpt-4.1-nano }
 *         anthropic: { model: claude-haiku-4-5, temperature: 0.3 }
 * ```
 *
 * The active provider comes from [ModelSelectionContext.provider] when a user key is in play, and
 * otherwise from whichever provider supplies the default LLM - so a single-provider deployment gets
 * the right column without setting a context at all.
 *
 * Falls back to the flat `embabel.models.llms` map, which remains the right shape for a deployment
 * that will only ever have one provider.
 *
 * @param properties the bound `embabel.models` configuration
 * @param defaultProviderName provider of the default LLM, used when no key is active. A function
 * because the default LLM is resolved by [ConfigurableModelProvider] against registered beans.
 */
class ConfigurableRoleResolver(
    private val properties: ConfigurableModelProviderProperties,
    private val defaultProviderName: () -> String?,
) : RoleResolver, Ordered {

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun resolve(role: String, context: ModelSelectionContext): RoleResolution? {
        val activeProvider = context.provider ?: defaultProviderName()
        val configuredForProvider = optionsFor(role, activeProvider)
        if (configuredForProvider != null) {
            // A user key beats deployment credentials: hand the key back and let the platform
            // build a service for the model this role names under that provider.
            return context.credential
                ?.let { RoleResolution.Credential(it) }
                ?: RoleResolution.Options(configuredForProvider)
        }
        if (context.credential != null) {
            // The flat map names models the deployment is keyed for. Serving them to a user who
            // brought their own key would quietly bill the deployment for a call the user meant to
            // pay for, so leave it alone and let the role fail for this user.
            return null
        }
        return flatOptionsFor(role)?.let { RoleResolution.Options(it) }
    }

    /**
     * What configuration says [role] means for [provider] - the nested entry if there is one,
     * otherwise the flat map.
     *
     * This is the read side of [resolve], for callers that want to *show* a role's model rather
     * than use it: a settings UI, a diagnostic endpoint, an application with its own per-user
     * override layer on top. Sharing the lookup means such a caller cannot drift from what
     * resolution would actually pick.
     *
     * It is deliberately narrower than resolution in two ways. It does not consult application
     * [RoleResolver] beans, so a resolver that overrides a role per user is not reflected here.
     * And it takes the provider as an argument rather than reading the active
     * [ModelSelectionContext], so the answer does not depend on which thread asks.
     */
    fun configuredOptionsFor(role: String, provider: String?): LlmOptions? =
        optionsFor(role, provider) ?: flatOptionsFor(role)

    /**
     * Options configured for [role] under [provider], or null if the role says nothing about it.
     *
     * Reads the nested shape, in which a role names a different model per provider:
     *
     * ```yaml
     * embabel:
     *   models:
     *     roles:
     *       cheapest:
     *         openai:
     *           model: gpt-4.1-nano
     *         anthropic:
     *           model: claude-haiku-4-5
     *           temperature: 0.0
     * ```
     *
     * `optionsFor("cheapest", "anthropic")` returns those Anthropic options, temperature included.
     * `optionsFor("cheapest", "mistral")` returns null - the role is configured, but says nothing
     * about that provider, so the caller falls back to the flat `embabel.models.llms` map.
     *
     * A null [provider] returns null rather than guessing: with no active credential there is no
     * provider to select within, and the flat map is the right answer. Provider names are matched
     * case-insensitively, because they arrive from user-supplied credentials as often as from yaml.
     */
    fun optionsFor(role: String, provider: String?): LlmOptions? {
        if (provider == null) {
            return null
        }
        return properties.roles[role]
            ?.entries
            ?.firstOrNull { it.key.equals(provider, ignoreCase = true) }
            ?.value
    }

    private fun flatOptionsFor(role: String): LlmOptions? =
        properties.llms[role]?.let { LlmOptions.withModel(it) }
}
