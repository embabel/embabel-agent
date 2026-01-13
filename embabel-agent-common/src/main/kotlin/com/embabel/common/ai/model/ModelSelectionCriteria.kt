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

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Exception thrown when no suitable model is found for the given criteria.
 */
class NoSuitableModelException(
    criteria: ModelSelectionCriteria,
    modelNames: List<String>,
) : RuntimeException(
    """
    No suitable model found for $criteria.
    ${modelNames.size} models available: $modelNames
    """.trimIndent()
) {
    companion object {
        @JvmStatic
        fun forModels(criteria: ModelSelectionCriteria, models: List<AiModel<*>>): NoSuitableModelException =
            NoSuitableModelException(criteria, models.map { it.name })
    }
}

/**
 * Superinterface for model selection criteria
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.SIMPLE_NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ByNameModelSelectionCriteria::class),
    JsonSubTypes.Type(value = ByRoleModelSelectionCriteria::class),
    JsonSubTypes.Type(value = RandomByNameModelSelectionCriteria::class),
    JsonSubTypes.Type(value = FallbackByNameModelSelectionCriteria::class),
    JsonSubTypes.Type(value = AutoModelSelectionCriteria::class),
    JsonSubTypes.Type(value = DefaultModelSelectionCriteria::class),
)
sealed interface ModelSelectionCriteria {

    companion object {

        @JvmStatic
        fun byRole(role: String): ModelSelectionCriteria = ByRoleModelSelectionCriteria(role)

        @JvmStatic
        fun byName(name: String): ModelSelectionCriteria = ByNameModelSelectionCriteria(name)

        @JvmStatic
        fun randomOf(vararg names: String): ModelSelectionCriteria =
            RandomByNameModelSelectionCriteria(names.toList())

        @JvmStatic
        fun firstOf(vararg names: String): ModelSelectionCriteria =
            FallbackByNameModelSelectionCriteria(names.toList())

        /**
         * Choose an LLM automatically. Rely on platform
         * to do the right thing.
         */
        @JvmStatic
        val Auto: ModelSelectionCriteria = AutoModelSelectionCriteria

        @JvmStatic
        val PlatformDefault: ModelSelectionCriteria = DefaultModelSelectionCriteria
    }
}

/**
 * Select an LLM by role
 */
data class ByRoleModelSelectionCriteria(
    val role: String,
) : ModelSelectionCriteria

data class ByNameModelSelectionCriteria(
    val name: String,
) : ModelSelectionCriteria

data class RandomByNameModelSelectionCriteria(
    val names: List<String>,
) : ModelSelectionCriteria

data class FallbackByNameModelSelectionCriteria(
    val names: List<String>,
) : ModelSelectionCriteria

/**
 * Choose an LLM automatically: For example, in a platform, based
 * on runtime analysis, or based on analysis of the prompt
 */
object AutoModelSelectionCriteria : ModelSelectionCriteria {
    override fun toString(): String = "AutoModelSelectionCriteria"
}

object DefaultModelSelectionCriteria : ModelSelectionCriteria {
    override fun toString(): String = "DefaultModelSelectionCriteria"
}
