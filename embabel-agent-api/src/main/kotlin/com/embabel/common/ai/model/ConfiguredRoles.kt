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

import org.jetbrains.annotations.ApiStatus

/**
 * What configuration says the roles of one [ModelKind] mean.
 *
 * Two shapes, read the same way for every kind. The nested shape names a binding per provider, for
 * a deployment whose provider is not fixed:
 *
 * ```yaml
 * embabel:
 *   models:
 *     roles:                 # chat: a binding is LlmOptions
 *       cheapest:
 *         openai:    { model: gpt-4.1-nano }
 *         anthropic: { model: claude-haiku-4-5, temperature: 0.3 }
 *     embedding-roles:       # embedding: a binding is a model name
 *       documents:
 *         openai:  text-embedding-3-small
 *         mistral: mistral-embed
 * ```
 *
 * The flat shape (`llms`, `embedding-services`) names one binding per role, which remains the
 * right shape for a deployment that will only ever have one provider.
 *
 * Read on every call rather than copied, so it always reflects the bound properties.
 *
 * @param B what a role of this kind binds to
 * @param byProvider role, then provider, then binding
 * @param flat role, then binding
 */
@ApiStatus.Experimental
class ConfiguredRoles<B : RoleBinding>(
    private val byProvider: () -> Map<String, Map<String, B>>,
    private val flat: () -> Map<String, B>,
) {

    /**
     * The binding configured for [role] under [provider], or null if the role says nothing about it.
     *
     * A null [provider] returns null rather than guessing: with no active credential there is no
     * provider to select within, and the flat map is the right answer. Provider names are matched
     * case-insensitively, because they arrive from user-supplied credentials as often as from yaml.
     */
    fun boundFor(role: String, provider: String?): B? {
        if (provider == null) {
            return null
        }
        return byProvider()[role]
            ?.entries
            ?.firstOrNull { it.key.equals(provider, ignoreCase = true) }
            ?.value
    }

    /** The binding the flat map gives [role], or null. */
    fun flatFor(role: String): B? = flat()[role]

    /** The nested entry for [role] under [provider] if there is one, otherwise the flat map's. */
    fun configuredFor(role: String, provider: String?): B? = boundFor(role, provider) ?: flatFor(role)
}
