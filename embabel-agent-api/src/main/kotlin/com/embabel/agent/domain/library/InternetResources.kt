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
package com.embabel.agent.domain.library

import com.embabel.common.ai.prompt.PromptContributor
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

interface Page : PromptContributor {

    val url: String

    val summary: String

    override fun contribution(): String {
        return "URL: $url\nSummary: $summary"
    }
}

@JsonClassDescription("Internet resource")
open class InternetResource(
    @get:JsonPropertyDescription("url of the resource")
    override val url: String,
    @get:JsonPropertyDescription("concise summary of the resource")
    override val summary: String,
) : Page

interface InternetResources : PromptContributor {

    @get:JsonPropertyDescription("internet resources")
    val links: List<InternetResource>

    override fun contribution(): String {
        return links.joinToString("\n") { it.contribution() }
            .ifBlank { "No relevant internet resources found." }
    }
}
