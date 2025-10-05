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
package com.embabel.agent.rag.tools

import com.embabel.agent.api.common.LlmReference
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.rag.PromptRunnerRagResponseSummarizer

/**
 * Expose a RagService as an LlmReference with tools.
 */
class RagReference(
    override val name: String,
    override val description: String,
    val options: RagOptions,
    private val summarizerPromptRunner: PromptRunner,
) : LlmReference {

    private val toolInstance: Any = run {
        if (options.dualShot != null) {
            DualShotRagServiceSearchTools(
                options = options,
                summarizer = PromptRunnerRagResponseSummarizer(summarizerPromptRunner, options)
            )
        } else {
            SingleShotRagServiceSearchTools(
                options = options,
            )
        }
    }

    override fun toolInstance() = toolInstance

    override fun notes() = ""

}
