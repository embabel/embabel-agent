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

open class RelevantNewsStories(
    val items: List<NewsStory>,
) : PromptContributor {

    override fun contribution(): String {
        return items.joinToString("\n") {
            it.contribution()
        }.ifBlank { "No relevant news stories found." }
    }
}

open class NewsStory(
    override val url: String,
    val title: String,
    override val summary: String,
) : PromptContributor, Page {

    override fun contribution(): String {
        return "Title: $title\nSummary: $summary\nURL: $url"
    }
}
