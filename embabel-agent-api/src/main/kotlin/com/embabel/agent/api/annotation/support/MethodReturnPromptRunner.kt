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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.common.CreateObjectPromptException
import com.embabel.agent.api.common.EvaluateConditionPromptException
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.api.common.ToolObject
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.prompt.element.ContextualPromptElement
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.ZeroToOne

/**
 * PromptRunner implementation that can be used to return a value
 * from an @Action or @Condition method.
 */
internal data class MethodReturnPromptRunner(
    override val llm: LlmOptions?,
    override val toolGroups: Set<ToolGroupRequirement>,
    override val toolObjects: List<ToolObject>,
    override val promptContributors: List<PromptContributor>,
    private val contextualPromptContributors: List<ContextualPromptElement>,
    override val generateExamples: Boolean?,
) : PromptRunner {

    override fun <T> createObject(
        prompt: String,
        outputClass: Class<T>,
    ): T {
        throw CreateObjectPromptException(
            prompt = prompt,
            llm = llm,
            requireResult = true,
            outputClass = outputClass,
            toolGroups = toolGroups,
            toolCallbacks = emptyList(),
            toolObjects = toolObjects,
            promptContributors = promptContributors,
            contextualPromptContributors = contextualPromptContributors,
            generateExamples = generateExamples,
        )
    }

    override fun <T> createObjectIfPossible(
        prompt: String,
        outputClass: Class<T>,
    ): T? {
        throw CreateObjectPromptException(
            prompt = prompt,
            llm = llm,
            requireResult = false,
            outputClass = outputClass,
            toolGroups = toolGroups,
            toolCallbacks = emptyList(),
            toolObjects = toolObjects,
            promptContributors = promptContributors,
            contextualPromptContributors = contextualPromptContributors,
            generateExamples = generateExamples,
        )
    }

    override fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne,
    ): Boolean {
        throw EvaluateConditionPromptException(
            condition = condition,
            context = context,
            confidenceThreshold = confidenceThreshold,
            llm = llm,
            requireResult = false,
            toolGroups = toolGroups,
            toolCallbacks = emptyList(),
            toolObjects = toolObjects,
            promptContributors = promptContributors,
            contextualPromptContributors = contextualPromptContributors,
            generateExamples = generateExamples,
        )
    }

    override fun withHandoffs(vararg outputTypes: Class<*>): PromptRunner {
        TODO("Probably won't be implemented as this class is likely to be deprecated")
    }

    override fun withLlm(llm: LlmOptions): PromptRunner =
        copy(llm = llm)

    override fun withToolGroup(toolGroup: ToolGroupRequirement): PromptRunner =
        copy(toolGroups = this.toolGroups + toolGroup)

    override fun withToolObject(toolObject: ToolObject): PromptRunner =
        copy(toolObjects = this.toolObjects + toolObject)

    override fun withPromptContributors(promptContributors: List<PromptContributor>): PromptRunner =
        copy(promptContributors = this.promptContributors + promptContributors)

    override fun withContextualPromptContributors(
        contextualPromptContributors: List<ContextualPromptElement>,
    ): PromptRunner =
        copy(contextualPromptContributors = this.contextualPromptContributors + contextualPromptContributors)

    override fun withGenerateExamples(generateExamples: Boolean): PromptRunner =
        copy(generateExamples = generateExamples)
}
