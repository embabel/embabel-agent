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
package com.embabel.common.ai.model.local

import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.CredentialModelFactory
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelCapability
import com.embabel.common.ai.model.ModelKind
import com.embabel.common.ai.model.ModelMetadata

/**
 * A local model runner - Docker Model Runner, LM Studio, Ollama - asked what it is serving NOW.
 *
 * The local counterpart of [CredentialModelFactory], and it exists for the same reason: something
 * has to be able to answer for a model the platform did not know about when it was built.
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
     * What the runner is serving right now, by the names models are asked for under, each with the
     * kind of model it is.
     *
     * Empty when the runner is unreachable, which declines every role rather than failing one.
     *
     * [LocalModel.kind] must be decided the same way this source's startup registration decides it,
     * so a model does not change kind by virtue of having arrived late.
     */
    fun servedModels(): Set<LocalModel>

    /**
     * A model of [kind] for [model], or null if this source cannot build one.
     *
     * Called only for a model [servedModels] just reported, and at most once per kind and model
     * name: [LocalModelCatalog] holds what this returns. Returning null is the right answer for a
     * kind this runner does not serve, and for a runner that can tell a chat model from an embedding
     * model and knows this one is not of [kind]. A runner that cannot tell should build, since the
     * role asking is what says which it is.
     */
    fun <C : ModelCapability> model(kind: ModelKind<C, *>, model: String): C?
}

/**
 * A model a local runner is serving right now.
 *
 * [kind] decides which listing the model appears in and what a caller naming it gets back. It does
 * NOT gate role resolution: there, the role that asked is the better signal - an embedding role
 * asking for a name means that name is an embedding model, whatever a runner's own type field says -
 * and declining on a disagreement would break a configuration that works.
 *
 * A runner lists names. The kind of each name is either stated by the runner (LM Studio reports a
 * type) or decided by configuration ([kindFromConfiguration]).
 */
data class LocalModel(
    val name: String,
    val kind: ModelKind<*, *>,
) {

    companion object {

        /**
         * What kind of model [modelName] is when the runner itself does not say.
         *
         * Docker's `/v1/models` and Ollama's `/api/tags` list names and nothing else, so
         * configuration decides: a model some `embedding-services` entry or embedding role names is
         * an embedding model, and anything else is a chat model.
         *
         * ONE function rather than one per runner, because both runners' startup registration
         * applies this same rule and the guarantee that matters - a model cannot land in one kind at
         * boot and the other when pulled later - is only as good as the two copies staying
         * identical. LM Studio does not call this; it reports a type per model.
         */
        @JvmStatic
        fun kindFromConfiguration(
            modelName: String,
            properties: ConfigurableModelProviderProperties,
        ): ModelKind<*, *> =
            if (properties.allWellKnownEmbeddingServiceNames().contains(modelName)) {
                EmbeddingService.KIND
            } else {
                LlmService.KIND
            }
    }
}

/**
 * A runner that serves chat and embedding models, which every shipped runner is.
 *
 * Builds through [chatModel] and [embeddingModel] and declines every other kind, so a runner states
 * how it builds each kind it serves and nothing more.
 */
abstract class ChatAndEmbeddingModelSource : LocalModelSource {

    /** A chat model for [model], or null if this runner cannot build one. */
    protected abstract fun chatModel(model: String): LlmService<*>?

    /** An embedding model for [model], or null if this runner cannot build one. */
    protected abstract fun embeddingModel(model: String): EmbeddingService?

    final override fun <C : ModelCapability> model(kind: ModelKind<C, *>, model: String): C? =
        // Kinds are compared by identity. The cast only changes the static type: the branch has
        // already established that C is the capability of the kind returned.
        when {
            kind === LlmService.KIND -> chatModel(model)?.let(kind.capability::cast)
            kind === EmbeddingService.KIND -> embeddingModel(model)?.let(kind.capability::cast)
            else -> null
        }
}
