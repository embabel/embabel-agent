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
 * What a model a local runner is serving is FOR.
 *
 * A runner lists names; what a name is for is either something the runner states (LM Studio reports
 * a type) or something configuration decides (Docker and Ollama read
 * [ConfigurableModelProviderProperties.allWellKnownEmbeddingServiceNames], which is the same rule
 * their startup registration uses). Either way a [LocalModelSource] must answer, because the
 * platform lists chat models and embedding models separately and cannot guess.
 */
enum class LocalModelKind {
    CHAT,
    EMBEDDING,
    ;

    companion object {

        /**
         * What a model is for when the runner itself does not say.
         *
         * Docker's `/v1/models` and Ollama's `/api/tags` list names and nothing else, so
         * configuration decides: a model some `embedding-services` entry or embedding role names is
         * an embedding model, and anything else is a chat model.
         *
         * ONE function rather than one per runner, because both runners' startup registration
         * applies this same rule and the guarantee that matters - a model cannot land in one
         * category at boot and the other when pulled later - is only as good as the two copies
         * staying identical. LM Studio does not call this; it reports a type per model.
         */
        fun fromConfiguration(
            modelName: String,
            properties: ConfigurableModelProviderProperties,
        ): LocalModelKind =
            if (properties.allWellKnownEmbeddingServiceNames().contains(modelName)) {
                EMBEDDING
            } else {
                CHAT
            }
    }
}

/**
 * A model a local runner is serving right now.
 *
 * [kind] decides which list the model appears in and what a caller naming it gets back. It does NOT
 * gate role resolution: there, the role that asked is the better signal - an embedding role asking
 * for a name means that name is an embedding model, whatever a runner's own type field says - and
 * declining on a disagreement would break a configuration that works.
 */
data class LocalModel(
    val name: String,
    val kind: LocalModelKind,
)
