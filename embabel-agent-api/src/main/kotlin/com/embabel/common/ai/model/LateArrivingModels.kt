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
 * A resolver declaring that a named provider's models may appear AFTER startup.
 *
 * Implemented alongside [RoleResolver] or [EmbeddingRoleResolver]. It says nothing about how a role
 * resolves; it answers a different question, which only startup validation asks: is a role naming an
 * unregistered model under this provider a typo, or a model that has not arrived yet?
 *
 * Without it the answer is "typo", and it is fatal - correctly, for a deployment that holds its
 * models, because letting it start moves the failure to whichever unrelated call first asks for that
 * role. A provider whose models are pulled on the host is the exception: `roles.cheapest.docker`
 * naming a model nobody has pulled yet is the ordinary state of an appliance before setup, and
 * refusing to start is refusing to reach the point where the operator could pull it.
 *
 * This is why [ConfigurableModelProviderProperties.embeddingRoles] is not checked at all - every
 * entry there may be for a provider this process cannot serve - and it extends the same tolerance to
 * the ONE case the chat check still treats as fatal: an entry under the same provider the default
 * LLM comes from.
 *
 * Only the PROVIDER-QUALIFIED maps are covered. The flat `llms` and `embedding-services` maps name
 * no provider, so nothing there can be attributed to a runner rather than to a typo - which is the
 * reason a model that may arrive late belongs in a provider column.
 */
interface LateArrivingModels {

    /**
     * Provider whose models may appear after startup, matching [ModelMetadata.provider]. Compared
     * case-insensitively, as provider names are everywhere else.
     */
    val lateArrivingProvider: String
}
