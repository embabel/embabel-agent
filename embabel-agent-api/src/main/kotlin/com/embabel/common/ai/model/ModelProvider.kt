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
import com.embabel.common.core.types.HasInfoString

/**
 * Provide AI models for requested roles, and expose data about available models.
 */
interface ModelProvider : HasInfoString {

    @Throws(NoSuitableModelException::class)
    fun getLlm(criteria: ModelSelectionCriteria): LlmService<*>

    @Throws(NoSuitableModelException::class)
    fun getEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService

    /**
     * Resolve any role in these options to a concrete model, applying whatever hyperparameters are
     * configured against that role.
     *
     * Hyperparameters the caller set explicitly are kept - a role may say `temperature: 0.3`, but a
     * caller that passed its own temperature still gets that one. Model selection is the exception
     * and comes from the role wholesale: deciding which model a role means is the entire point of
     * resolving it, so a `model` set alongside a role is replaced rather than preserved.
     *
     * Called once per LLM operation, before the model is chosen, so that a role can carry more than
     * a model name. Options naming no role are returned unchanged.
     */
    fun resolveLlmOptions(llmOptions: LlmOptions): LlmOptions = llmOptions

    /**
     * What configuration says [role] means for this deployment, or null if it says nothing.
     *
     * The read side of role configuration, for callers that want to *show* a role's model rather
     * than use it - a settings screen, a diagnostic endpoint, or an application layering its own
     * per-user overrides on top. Without it such a caller has to read the raw properties and
     * reimplement the precedence between the flat and nested shapes, which is how a settings
     * screen ends up disagreeing with what actually runs.
     *
     * Narrower than [resolveLlmOptions] on purpose: it answers for the deployment's own provider
     * and ignores both user keys and application [RoleResolver] beans, so the answer does not
     * depend on who is asking or on which thread. Use [resolveLlmOptions] to find out what a
     * particular call will really do.
     *
     * Returns null by default, meaning "this implementation cannot say".
     */
    fun configuredOptionsForRole(role: String): LlmOptions? = null

    /**
     * List the roles available for this class of model
     */
    fun listRoles(modelClass: Class<*>): List<String>

    fun listModelNames(modelClass: Class<*>): List<String>

    fun listModels(): List<ModelMetadata>

    /**
     * Well-known roles for models
     * Useful but not exhaustive: users are free to define their own roles
     * @see ByRoleModelSelectionCriteria
     */
    companion object {

        const val BEST_ROLE = "best"

        const val CHEAPEST_ROLE = "cheapest"

    }

}
