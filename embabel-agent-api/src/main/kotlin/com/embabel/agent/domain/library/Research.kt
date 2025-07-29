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

import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.indentLines
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@JsonClassDescription("topic to research")
data class ResearchTopic(
    @get:JsonPropertyDescription("topic to research") val topic: String,
    @get:JsonPropertyDescription("specific questions") val questions: List<String>,
)

data class ResearchTopics(
    val topics: List<ResearchTopic>,
)

/**
 * Reusable domain object for a research report
 */
@JsonClassDescription("Research report, containing a text field and links")
data class ResearchReport(
    @get:JsonPropertyDescription(
        "The text of the research report",
    )
    override val content: String,
    override val links: List<InternetResource>,
) : HasContent, InternetResources, HasInfoString {

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        """|Report:
           |$content
           |Links: ${links.joinToString("\n") { it.url }}
           |"""
            .trimMargin()
            .indentLines(indent)
}

data class CompletedResearch(
    val topic: ResearchTopic,
    val researchReport: ResearchReport,
)

data class ResearchResult(
    val topicResearches: List<CompletedResearch>,
)
