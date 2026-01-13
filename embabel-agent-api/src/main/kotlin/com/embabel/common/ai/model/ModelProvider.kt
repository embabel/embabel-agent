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
     * List the roles available for this class of model
     */
    fun listRoles(modelClass: Class<out AiModel<*>>): List<String>

    fun listModelNames(modelClass: Class<out AiModel<*>>): List<String>

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
