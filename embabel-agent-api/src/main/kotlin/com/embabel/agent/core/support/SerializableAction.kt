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
package com.embabel.agent.core.support

import com.embabel.agent.core.*
import com.embabel.common.core.types.ZeroToOne

/**
 * Fully configured action, intended to be serializable.
 * @param name name of the action
 * @param description description of the action
 * @param pre preconditions for the action. Condition names, separated by commas
 * @param post expected postconditions for this action. May not eventuate.
 * @param cost cost of the action
 * @param input input variable definition, of form name:Type
 * @param output output variable definition, of form name:Type. Optional.
 * @param transitions transitions to other actions
 * @param runner runner for the action
 * @param qos quality of service
 */
internal class SerializableAction(
    name: String,
    description: String = name,
    pre: List<String> = emptyList(),
    post: List<String> = emptyList(),
    cost: ZeroToOne = 0.0,
    value: ZeroToOne = 0.0,
    input: IoBinding? = null,
    inputs: Set<IoBinding> = setOfNotNull(input),
    output: IoBinding? = null,
    outputs: Set<IoBinding> = setOfNotNull(output),
    toolGroups: Set<ToolGroupRequirement> = emptySet(),
    val runner: ActionRunner,
    qos: ActionQos = ActionQos(),
    canRerun: Boolean = false,
) : AbstractAction(
    name = name,
    description = description,
    pre = pre,
    post = post,
    cost = cost,
    value = value,
    inputs = inputs,
    outputs = outputs,
    canRerun = canRerun,
    toolGroups = toolGroups,
    qos = qos,
),
    ActionRunner by runner {

    override val domainTypes: Collection<DomainType>
        get() = emptySet()
}
