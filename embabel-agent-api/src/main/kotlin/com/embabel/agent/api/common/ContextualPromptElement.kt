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
package com.embabel.agent.api.common

import com.embabel.common.ai.prompt.PromptContribution
import com.embabel.common.ai.prompt.PromptContributionLocation
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.ai.prompt.PromptElement

/**
 * Make a dynamic prompt contribution based on the operation context.
 */
interface ContextualPromptElement : PromptElement {

    /**
     * Make a dynamic PromptContribution based on the operation context.
     */
    fun promptContribution(context: OperationContext): PromptContribution {
        return PromptContribution(
            content = contribution(context),
            location = promptContributionLocation,
            role = role,
        )
    }

    fun contribution(context: OperationContext): String

    /**
     * Make a static PromptContributor based on the operation context.
     */
    fun toPromptContributor(context: OperationContext): PromptContributor {
        return PromptContributor.fixed(
            content = contribution(context),
            location = promptContributionLocation,
            role = role,
        )
    }

    companion object {

        operator fun invoke(
            role: String? = null,
            location: PromptContributionLocation = PromptContributionLocation.BEGINNING,
            contribution: (OperationContext) -> String,
        ): ContextualPromptElement {
            return of(role, location, contribution)
        }

        /**
         * Create a prompt contribution with fixed content
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            role: String? = null,
            location: PromptContributionLocation = PromptContributionLocation.BEGINNING,
            contribution: (OperationContext) -> String,
        ): ContextualPromptElement {
            return ContextualPromptElementImpl(
                role = role,
                promptContributionLocation = location,
                contribution = contribution,
            )
        }

    }
}

private data class ContextualPromptElementImpl(
    private val contribution: (OperationContext) -> String,
    override val role: String? = null,
    override val promptContributionLocation: PromptContributionLocation = PromptContributionLocation.BEGINNING,
) : ContextualPromptElement {

    override fun contribution(context: OperationContext): String =
        contribution(context)

}
