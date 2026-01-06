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
package com.embabel.agent.test.unit

import com.embabel.agent.api.common.support.TransformationAction
import com.embabel.agent.core.ActionQos
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.plan.CostComputation

class FakeAction(
    name: String,
    description: String = name,
    pre: List<String> = emptyList(),
    post: List<String> = emptyList(),
    cost: CostComputation = { 0.0 },
    value: CostComputation = { 0.0 },
    canRerun: Boolean = false,
    qos: ActionQos = ActionQos(),
    inputClass: Class<Unit> = Unit::class.java,
    outputVarName: String? = IoBinding.DEFAULT_BINDING,
    referencedInputProperties: Set<String>? = null,
    toolGroups: Set<ToolGroupRequirement> = emptySet(),
) : TransformationAction<Unit, Unit>(
    name = name,
    description = description,
    pre = pre,
    post = post,
    cost = cost,
    value = value,
    canRerun = canRerun,
    qos = qos,
    inputClass = inputClass,
    outputClass = Unit::class.java,
    outputVarName = outputVarName,
    referencedInputProperties = referencedInputProperties,
    toolGroups = toolGroups,
    block = { },
)
