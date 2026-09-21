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

/**
 * What an embedding role resolved to.
 *
 * The embedding counterpart of [RoleResolution], and deliberately a separate type rather than a
 * fourth case on that one. A resolver written for chat answers with an [com.embabel.agent.spi.LlmService]
 * or with [LlmOptions] carrying a temperature, neither of which means anything to an embedding
 * call - so sharing the chain would oblige the platform to ask every chat resolver about every
 * embedding role and discard most of what came back. Worse, it would make a role NAME shared
 * between the two - "cheapest", "default" - resolve to a chat model for an embedding request.
 * Two chains, two answers, no collision.
 *
 * An application that resolves both from one source implements both interfaces on one class; the
 * [Credential] case is usually all either of them needs.
 */
sealed interface EmbeddingRoleResolution {

    /**
     * The name of a registered embedding service.
     *
     * The counterpart of [RoleResolution.Options], carrying a name rather than an options object
     * because there is nothing to tune: an embedding model takes text and returns a vector, and
     * the one parameter that would vary - the width - is the model's to state, not the caller's
     * to ask for. See [ConfigurableModelProviderProperties.embeddingRoles] for why the
     * configuration shape is flatter than `roles` for the same reason.
     */
    data class Model(
        val model: String,
    ) : EmbeddingRoleResolution

    /**
     * A provider key. The platform looks the role up for that provider and builds the service
     * through a [CredentialEmbeddingServiceFactory], caching it per provider, key and model.
     */
    data class Credential(
        val credential: ProviderCredential,
    ) : EmbeddingRoleResolution

    /**
     * An already-built service, for callers that construct their own.
     */
    data class Service(
        val embeddingService: EmbeddingService,
    ) : EmbeddingRoleResolution
}

/**
 * Decides what an embedding role means for a given call.
 *
 * The embedding counterpart of [RoleResolver], with the same ordering rules: application beans
 * first in [org.springframework.core.Ordered] order, then the platform's own
 * [ConfigurableEmbeddingRoleResolver], so configuration is always the last word. The platform
 * takes the first non-null answer, so a resolver can handle the roles it cares about and return
 * null for the rest.
 *
 * WHY THIS EXISTS AT ALL, given that an embedding service can be a Spring bean and a role could
 * just name one. Because the bean stream is captured once, when the platform is built. A
 * deployment whose provider key arrives after startup has no embedding bean to name at that point
 * and never gets one, so its embedding default is a placeholder for the life of the process -
 * which is the restart that bring-your-own-key removed from chat and left in place for
 * embeddings. A resolver is asked per call, so a key stored a second ago embeds the next document.
 *
 * Implementations must be thread-safe: one instance serves every call, on any thread.
 */
fun interface EmbeddingRoleResolver {

    /**
     * @param role the role requested, for example "documents"
     * @param context who the call is for and which provider key is active
     * @return how to satisfy the role, or null to let the next resolver decide
     */
    fun resolve(role: String, context: ModelSelectionContext): EmbeddingRoleResolution?
}

/**
 * Builds an [EmbeddingService] from a user-supplied key.
 *
 * The embedding counterpart of [CredentialLlmServiceFactory], and simpler than it: embedding has
 * one wire protocol worth speaking, so `embabel-agent-starter-byok` ships a single implementation
 * covering every OpenAI-compatible provider. An application needs one of its own only for a
 * provider that embeds over some other protocol.
 *
 * ON SCOPE, since the LLM counterpart has to answer this differently. That one is the second of
 * two tiers and the one to reach for last, because it names [com.embabel.agent.spi.LlmService] -
 * a package application code is asked not to depend on. This one names [EmbeddingService], which
 * lives here in `com.embabel.common.ai.model` alongside the rest of the model API, so there is no
 * SPI type to avoid and no lower tier to prefer: an application with a provider of its own
 * implements this directly.
 *
 * Return null for a provider this factory does not handle, rather than building something: the
 * platform tries each factory in turn, and a factory that answered for everything would hand back
 * a client pointed at the wrong endpoint for someone else's key.
 *
 * The platform caches what this returns, per (provider, key, model), so an implementation should
 * build rather than maintain a cache of its own.
 */
fun interface CredentialEmbeddingServiceFactory {

    /**
     * @return a service for [model] authenticated with [credential], or null if this factory does
     * not handle that provider
     */
    fun createEmbeddingService(credential: ProviderCredential, model: String): EmbeddingService?
}
