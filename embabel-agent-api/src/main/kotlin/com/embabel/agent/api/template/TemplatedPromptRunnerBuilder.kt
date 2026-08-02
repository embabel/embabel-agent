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
package com.embabel.agent.api.template

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner

/**
 * Wrapper builder that renders a loaded template into a system prompt for a [PromptRunner],
 * using the platform [com.embabel.common.textio.template.TemplateRenderer] available from
 * the [OperationContext]. Keeps template handling out of the PromptRunner sub-interfaces,
 * following the same pattern as [com.embabel.agent.api.streaming.StreamingPromptRunnerBuilder].
 */
data class TemplatedPromptRunnerBuilder(
    val context: OperationContext,
    val runner: PromptRunner,
) {

    /**
     * Render the named template with the given model and return a runner
     * carrying the rendered text as its system prompt.
     *
     * @param templateName the name of the template to load and render
     * @param model the model data to use for template rendering.
     *        Defaults to the empty map (which is appropriate for static templates)
     * @return a runner with the rendered system prompt applied
     */
    @JvmOverloads
    fun withSystemPromptTemplate(
        templateName: String,
        model: Map<String, Any> = emptyMap(),
    ): PromptRunner {
        val renderer = context.agentPlatform().platformServices.templateRenderer
        return runner.withSystemPrompt(renderer.renderLoadedTemplate(templateName, model))
    }
}
