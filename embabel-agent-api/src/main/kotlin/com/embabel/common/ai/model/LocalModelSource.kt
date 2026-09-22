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
 * A local model runner - Docker Model Runner, LM Studio, Ollama - asked what it is serving NOW.
 *
 * The local counterpart of [CredentialLlmServiceFactory] and [CredentialEmbeddingServiceFactory],
 * and it exists for the same reason they do: something has to be able to answer for a model the
 * platform did not know about when it was built.
 *
 * WHY THIS IS NOT JUST STARTUP DISCOVERY. Each runner's autoconfiguration enumerates models in a
 * [com.embabel.common.ai.autoconfig.ProviderInitialization] and `registerSingleton`s one bean per
 * model, which happens exactly once, while the platform is being constructed. A model pulled a
 * minute later is invisible however reachable it is, so `docker model pull` costs a restart - the
 * restart bring-your-own-key removed for keys and left in place for models on this machine. A
 * source is asked per call, so a model pulled a second ago serves the next request.
 *
 * Nothing here is built from a secret and no network identity is missing: the endpoint is known and
 * the model is being served. The only thing missing was that nothing asked again.
 *
 * Implementations must be thread-safe, and must be CHEAP to ask: [LocalModelCatalog] caches
 * [servedModels] for a short interval, but that interval is the only thing between this and an
 * HTTP round trip per embedding. They must also be quiet about an unreachable runner - returning
 * an empty set rather than throwing - since a runner that is not running is the ordinary state of
 * a deployment that does not use one.
 */
interface LocalModelSource {

    /**
     * Provider name, matching [ModelMetadata.provider] - "docker", "lmstudio", "ollama".
     *
     * This is the column of `embabel.models.roles` / `embabel.models.embedding-roles` the resolvers
     * read, so it must be the same string the runner's own models register under.
     */
    val provider: String

    /**
     * What the runner is serving right now, by the names models are asked for under, each with what
     * it is for.
     *
     * Empty when the runner is unreachable, which declines every role rather than failing one.
     *
     * [LocalModel.kind] must be decided the same way this source's startup registration decides it,
     * so a model does not change category by virtue of having arrived late.
     */
    fun servedModels(): Set<LocalModel>

    /**
     * A chat service for [model], or null if this source cannot build one.
     *
     * Called only for a model [servedModels] just reported, and at most once per model name:
     * [LocalModelCatalog] holds what this returns.
     */
    fun llmService(model: String): LlmService<*>?

    /**
     * An embedding service for [model], or null if this source cannot build one.
     *
     * Called on the same terms as [llmService]. Returning null is the right answer for a runner
     * that can tell a chat model from an embedding model and knows this one is not the latter;
     * a runner that cannot tell should build, since the role asking is what says which it is.
     */
    fun embeddingService(model: String): EmbeddingService?
}
