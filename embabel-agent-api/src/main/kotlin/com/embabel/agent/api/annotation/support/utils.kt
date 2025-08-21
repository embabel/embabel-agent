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

import com.embabel.agent.api.annotation.RequireNameMatch
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.IoBinding
import java.lang.reflect.Parameter

/**
 * Convenient method to deploy instances to an agent platform
 */
fun AgentPlatform.deployAnnotatedInstances(
    agentMetadataReader: AgentMetadataReader,
    vararg instances: Any,
) {
    instances
        .mapNotNull { agentMetadataReader.createAgentMetadata(it) }
        .forEach { deploy(it) }
}

/**
 * Returns the name of the parameter based on the provided [RequireNameMatch].
 */
fun getBindingParameterName(
    parameter: Parameter,
    requireNameMatch: RequireNameMatch?,
): String {
    if (requireNameMatch == null) {
        return IoBinding.DEFAULT_BINDING
    }

    return requireNameMatch.value.ifBlank { parameter.name }
}
