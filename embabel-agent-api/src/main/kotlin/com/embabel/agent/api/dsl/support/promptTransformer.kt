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
package com.embabel.agent.api.dsl.support

import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.support.TransformationAction
import com.embabel.agent.core.ActionQos
import com.embabel.agent.core.Condition
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.plan.CostComputation
import org.jetbrains.annotations.ApiStatus

/**
 * Supports AgentBuilder. Not for direct use in user code.
 */
@ApiStatus.Internal
fun <I, O : Any> promptTransformer(
    name: String,
    description: String = name,
    pre: List<Condition> = emptyList(),
    post: List<Condition> = emptyList(),
    inputVarName: String = IoBinding.DEFAULT_BINDING,
    outputVarName: String = IoBinding.DEFAULT_BINDING,
    inputClass: Class<I>,
    outputClass: Class<O>,
    cost: CostComputation = { 0.0 },
    toolGroups: Set<ToolGroupRequirement> = emptySet(),
    qos: ActionQos = ActionQos(),
    referencedInputProperties: Set<String>? = null,
    llm: LlmOptions = LlmOptions(),
    promptContributors: List<PromptContributor> = emptyList(),
    canRerun: Boolean = false,
    prompt: (actionContext: TransformationActionContext<I, O>) -> String,
): TransformationAction<I, O> {
    return TransformationAction(
        name = name,
        description = description,
        pre = pre.map { it.name },
        post = post.map { it.name },
        cost = cost,
        qos = qos,
        canRerun = canRerun,
        inputVarName = inputVarName,
        outputVarName = outputVarName,
        inputClass = inputClass,
        outputClass = outputClass,
        referencedInputProperties = referencedInputProperties,
        toolGroups = toolGroups,
    ) {
        it.promptRunner(
            llm = llm,
            toolGroups = toolGroups,
            promptContributors = promptContributors,
        ).createObject(
            prompt = prompt(it),
            outputClass = outputClass,
        )
    }
}
