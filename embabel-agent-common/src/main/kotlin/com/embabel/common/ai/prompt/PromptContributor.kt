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

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Where should the prompt go?
 */
enum class Location {
    BEGINNING,
    END,
}

/**
 * Contribution to a prompt
 * @param content The content of the contribution
 * @param location Where in the prompt this should go
 * @param role The role of the contribution.
 */
data class PromptContribution(
    val content: String,
    val location: Location,
    val role: String,
) {

    companion object {

        const val KNOWLEDGE_CUTOFF_ROLE = "knowledge_cutoff"
    }
}

/**
 * Contributor to a prompt.
 * Contributors may be put in a system message.
 */
interface PromptContributor {

    fun promptContribution(): PromptContribution

    /**
     * Return just the string content of the contribution.
     */
    fun contribution(): String = promptContribution().content
}

/**
 * Well known prompt contributor for knowledge cutoff date.
 */
class KnowledgeCutoffDate(
    date: LocalDate,
    formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
) : PromptContributor {

    private val value = PromptContribution(
        content = "Knowledge cutoff: ${date.format(formatter)}\n",
        location = Location.BEGINNING,
        role = PromptContribution.KNOWLEDGE_CUTOFF_ROLE,
    )

    /**
     * Based on ChatGPT default system prompt
     */
    override fun promptContribution(): PromptContribution = value

    override fun toString(): String = "KnowledgeCutoffDate: [$value]"

}

class CurrentDate(
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
) : PromptContributor {

    /**
     * Based on ChatGPT default system prompt
     */
    override fun promptContribution(): PromptContribution {
        return PromptContribution(
            content = "Current date: ${LocalDate.now().format(formatter)}\n",
            location = Location.BEGINNING,
            role = PromptContribution.KNOWLEDGE_CUTOFF_ROLE,
        )
    }

    override fun toString(): String = "CurrentDate: [${contribution()}]"

}
