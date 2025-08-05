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
package com.embabel.agent.api.annotation

import com.embabel.agent.api.annotation.support.MethodReturnPromptRunner
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.api.common.ToolObject
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.prompt.element.ContextualPromptElement
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor

/**
 * Return a prompt runner for use to return in an @Action method, specifying
 * LLM options.
 * @param llm specification of LLM to use
 * @param toolGroups tool groups to request
 * @param toolObjects objects with @Tool annotations to expose to the LLM.
 * Note that domain objects passed into the @Action method will automatically
 * expose any @Tool methods.
 * @promptContributors promptContributors to expose
 * @generateExamples whether to override default example generation
 */
@Deprecated("Use an OperationContext to obtain a PromptRunner. Define it as an argument to your @Action method.")
@JvmOverloads
fun using(
    llm: LlmOptions? = null,
    toolGroups: Set<ToolGroupRequirement> = emptySet(),
    toolObjects: List<ToolObject> = emptyList(),
    promptContributors: List<PromptContributor> = emptyList(),
    contextualPromptContributors: List<ContextualPromptElement> = emptyList(),
    generateExamples: Boolean? = null,
): PromptRunner =
    MethodReturnPromptRunner(
        llm = llm,
        toolGroups = toolGroups,
        toolObjects = toolObjects,
        promptContributors = promptContributors,
        generateExamples = generateExamples,
        contextualPromptContributors = contextualPromptContributors, // No contextual contributors for this runner,
    )

/**
 * Convenience method to return a prompt runner for use to return in an @Action method
 * that uses the given model with default hyperparameters
 * @param model name of LLM to use
 * @param toolGroups tool groups to request
 * @param toolObjects objects with @Tool annotations to expose to the LLM.
 * Note that domain objects passed into the @Action method will automatically
 * expose any @Tool methods.
 * @promptContributors promptContributors to expose
 * @generateExamples whether to override default example generation
 */
@Deprecated("Use an OperationContext to obtain a PromptRunner. Define it as an argument to your @Action method.")
@JvmOverloads
fun usingModel(
    model: String,
    toolGroups: Set<ToolGroupRequirement> = emptySet(),
    toolObjects: List<ToolObject> = emptyList(),
    promptContributors: List<PromptContributor> = emptyList(),
    contextualPromptContributors: List<ContextualPromptElement> = emptyList(),
    generateExamples: Boolean? = null,
): PromptRunner =
    MethodReturnPromptRunner(
        llm = LlmOptions(model = model),
        toolGroups = toolGroups,
        toolObjects = toolObjects,
        promptContributors = promptContributors,
        contextualPromptContributors = contextualPromptContributors,
        generateExamples = generateExamples,
    )

/**
 * Return a PromptRunner instance for use in @Action methods
 * that uses default LLM and hyperparameters.
 */
@Deprecated("Use an OperationContext to obtain a PromptRunner. Define it as an argument to your @Action method.")
val usingDefaultLlm: PromptRunner =
    MethodReturnPromptRunner(
        llm = null,
        toolGroups = emptySet(),
        toolObjects = emptyList(),
        promptContributors = emptyList(),
        contextualPromptContributors = emptyList(),
        generateExamples = null,
    )
