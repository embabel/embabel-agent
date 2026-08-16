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

import com.embabel.agent.spi.LlmService

/**
 * What a role resolved to.
 *
 * Three cases, in increasing order of how much the resolver does itself. Most applications need
 * only [Credential]: say whose key is active and let the platform do the rest.
 */
sealed interface RoleResolution {

    /**
     * Configured options - a model name, and optionally hyperparameters that travel with the role.
     * Values the caller set explicitly still win.
     *
     * The wrapper is what makes this a case rather than a payload. Returning a bare [LlmOptions]
     * would leave the platform unable to tell "resolved to options" from the other two answers in
     * a `when`, and would rule out ever adding a third field to this case without changing every
     * resolver's signature. The other two wrap for the same reason; the cost is one `.llmOptions`
     * at the use site.
     */
    data class Options(
        val llmOptions: LlmOptions,
    ) : RoleResolution

    /**
     * A provider key. The platform looks the role up for that provider and builds the service
     * through a [CredentialLlmServiceFactory], caching it.
     */
    data class Credential(
        val credential: ProviderCredential,
    ) : RoleResolution

    /**
     * An already-built service, for callers that construct their own.
     */
    data class Service(
        val llmService: LlmService<*>,
    ) : RoleResolution
}

/**
 * Decides what a role means for a given call.
 *
 * Register as many as you like. The platform takes the first non-null answer, so a resolver can
 * handle the roles it cares about and delegate the rest by returning null.
 *
 * Ordering, in full, because it decides who wins:
 *
 * - **Application resolvers always precede the platform's own.** [ConfigurableRoleResolver] is not
 *   in the bean stream at all - it is appended after the ordered beans - so configuration is
 *   always the last word, whatever an application registers.
 * - **Between application resolvers**, Spring's [org.springframework.core.Ordered] applies:
 *   lowest value first, via `@Order` or by implementing `Ordered`. A resolver that does neither is
 *   `LOWEST_PRECEDENCE` and sorts after any that do.
 * - **Ties** fall back to bean registration order, which is not something to depend on. If two of
 *   your resolvers can answer the same role, order them explicitly.
 *
 * Implementations must be thread-safe: one instance serves every call, on any thread.
 */
fun interface RoleResolver {

    /**
     * @param role the role requested, for example "cheapest"
     * @param context who the call is for and which provider key is active
     * @return how to satisfy the role, or null to let the next resolver decide
     */
    fun resolve(role: String, context: ModelSelectionContext): RoleResolution?
}

/**
 * Builds an [LlmService] from a user-supplied key.
 *
 * The BYOK starter supplies implementations for OpenAI and Anthropic. Applications using another
 * provider must register one - otherwise [RoleResolution.Credential] fails with
 * [NoSuitableModelException] and a log line naming the provider nothing handled. An implementation
 * is a one-liner over the provider's own BYOK factory:
 *
 * ```kotlin
 * @Bean
 * fun openAiCredentialFactory() = CredentialLlmServiceFactory { credential, model ->
 *     if (!credential.provider.equals(OpenAiModels.PROVIDER, ignoreCase = true)) null
 *     else OpenAiCompatibleModelFactory(baseUrl = null, apiKey = credential.apiKey)
 *         .openAiCompatibleLlm(model = model, provider = OpenAiModels.PROVIDER)
 * }
 * ```
 *
 * The platform caches what this returns, per (provider, key, model), so an implementation should
 * build rather than maintain a cache of its own.
 *
 * A bean with the same name as a starter-supplied implementation replaces it, allowing an
 * application to customize details such as the base URL or proxy.
 */
fun interface CredentialLlmServiceFactory {

    /**
     * @return a service for [model] authenticated with [credential], or null if this factory does
     * not handle that provider
     */
    fun createLlmService(credential: ProviderCredential, model: String): LlmService<*>?
}
