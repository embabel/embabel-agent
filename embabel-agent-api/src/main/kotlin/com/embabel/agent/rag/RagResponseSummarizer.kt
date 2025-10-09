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
package com.embabel.agent.rag

import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.rag.tools.RagOptions

interface RagResponseSummarizer {
    fun summarize(ragResponse: RagResponse): String
}

class PromptRunnerRagResponseSummarizer(
    val promptRunner: PromptRunner,
    val options: RagOptions,
) : RagResponseSummarizer {

    override fun summarize(ragResponse: RagResponse): String {
        return promptRunner
            .withId("summarizer")
            .generateText(
                prompt = """
                Summarize the following information provided as context to answer a question.
                Limit the summary to approximately ${options.dualShot?.summaryWords ?: 100} words.
                <query>${ragResponse.request.query}</query>
                <context>
                ${options.ragResponseFormatter.format(ragResponse)}
                </context>
            """.trimIndent(),
            )
    }
}
