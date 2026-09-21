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
 * The platform's own [EmbeddingRoleResolver], consulted after any the application registers.
 *
 * Reads `embabel.models.embedding-roles` - role, then provider, then model:
 *
 * ```yaml
 * embabel:
 *   models:
 *     embedding-roles:
 *       documents:
 *         openai:  text-embedding-3-small
 *         mistral: mistral-embed
 * ```
 *
 * The active provider comes from [ModelSelectionContext.provider] when a user key is in play, and
 * otherwise from [defaultProviderName] - so a single-provider deployment gets the right column
 * without setting a context at all.
 *
 * Falls back to the flat `embabel.models.embedding-services` map, which remains the right shape
 * for a deployment that will only ever have one provider.
 *
 * The structure mirrors [ConfigurableRoleResolver] case for case, deliberately: the two are read
 * side by side whenever somebody is working out why a role resolved the way it did, and a
 * difference between them would have to be a difference that matters.
 *
 * @param properties the bound `embabel.models` configuration
 * @param defaultProviderName provider of the deployment's default embedding service, used when no
 * key is active. A function because that default is resolved by [ConfigurableModelProvider]
 * against registered beans, after this is constructed.
 */
class ConfigurableEmbeddingRoleResolver(
    private val properties: ConfigurableModelProviderProperties,
    private val defaultProviderName: () -> String?,
) : EmbeddingRoleResolver, Ordered {

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun resolve(role: String, context: ModelSelectionContext): EmbeddingRoleResolution? {
        val activeProvider = context.provider ?: defaultProviderName()
        val configuredForProvider = modelFor(role, activeProvider)
        if (configuredForProvider != null) {
            // A user key beats deployment credentials: hand the key back and let the platform
            // build a service for the model this role names under that provider.
            return context.credential
                ?.let { EmbeddingRoleResolution.Credential(it) }
                ?: EmbeddingRoleResolution.Model(configuredForProvider)
        }
        if (context.credential != null) {
            // The flat map names models the deployment is keyed for. Serving them to a user who
            // brought their own key would quietly bill the deployment for a call the user meant
            // to pay for, so leave it alone and let the role fail for this user.
            return null
        }
        return flatModelFor(role)?.let { EmbeddingRoleResolution.Model(it) }
    }

    /**
     * What configuration says [role] means for [provider] - the nested entry if there is one,
     * otherwise the flat map.
     *
     * The read side of [resolve], for callers that want to *show* a role's model rather than use
     * it: a settings UI, a diagnostic endpoint. Sharing the lookup means such a caller cannot
     * drift from what resolution would actually pick. Narrower than resolution in the same two
     * ways [ConfigurableRoleResolver.configuredOptionsFor] is: it does not consult application
     * resolvers, and it takes the provider as an argument rather than reading the active context.
     */
    fun configuredModelFor(role: String, provider: String?): String? =
        modelFor(role, provider) ?: flatModelFor(role)

    /**
     * The model configured for [role] under [provider], or null if the role says nothing about it.
     *
     * A null [provider] returns null rather than guessing: with no active credential there is no
     * provider to select within, and the flat map is the right answer. Provider names are matched
     * case-insensitively, because they arrive from user-supplied credentials as often as from yaml.
     */
    fun modelFor(role: String, provider: String?): String? {
        if (provider == null) {
            return null
        }
        return properties.embeddingRoles[role]
            ?.entries
            ?.firstOrNull { it.key.equals(provider, ignoreCase = true) }
            ?.value
    }

    private fun flatModelFor(role: String): String? = properties.embeddingServices[role]
}
