/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.common.ai.prompt

/**
 * Where should the prompt go?
 * Can be used to map into system messages or other locations.
 */
enum class PromptContributionLocation {
    BEGINNING,
    END,
}

/**
 * Contribution to a prompt
 * @param content The content of the contribution
 * @param location Where in the prompt this should go
 * @param role The role of the contribution, if known.
 */
data class PromptContribution(
    val content: String,
    val location: PromptContributionLocation,
    val role: String?,
) {

    companion object {

        const val KNOWLEDGE_CUTOFF_ROLE = "knowledge_cutoff"
        const val CURRENT_DATE_ROLE = "current_date"

    }
}

/**
 * Contributor to a prompt.
 * Contributors may be put in a system message or elsewhere
 * depending on location.
 * Only the contribution() method must be implemented,
 * but implementations are free to provide location and role data
 * via overriding those properties.
 */
interface PromptContributor {

    /**
     * Role defaults to class simple name.
     * Override for stereotyped roles.
     */
    val role: String?
        get() = javaClass.simpleName

    val promptContributionLocation: PromptContributionLocation
        get()
        = PromptContributionLocation.BEGINNING

    fun promptContribution(): PromptContribution {
        return PromptContribution(
            content = contribution(),
            location = promptContributionLocation,
            role = role,
        )
    }

    /**
     * Return the string content of the contribution.
     */
    fun contribution(): String

    companion object {

        /**
         * Create a prompt contribution with fixed content
         */
        @JvmStatic
        @JvmOverloads
        fun fixed(
            content: String,
            role: String? = null,
            location: PromptContributionLocation = PromptContributionLocation.BEGINNING,
        ): PromptContributor {
            return FixedPromptContributor(
                content = content,
                role = role,
                promptContributionLocation = location,
            )
        }

    }
}

private data class FixedPromptContributor(
    val content: String,
    override val role: String?,
    override val promptContributionLocation: PromptContributionLocation,
) : PromptContributor {

    override fun contribution() = content

    override fun toString(): String = "FixedPromptContributor: [${contribution()}]"

}
