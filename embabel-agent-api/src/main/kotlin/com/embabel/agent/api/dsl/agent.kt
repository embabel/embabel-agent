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
package com.embabel.agent.api.dsl

import com.embabel.agent.core.Agent
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.Semver

/**
 * Surface area of DSL for creating an agent.
 * @param name The name of the agent.
 */
fun agent(
    name: String,
    provider: String = "embabel",
    version: Semver = Semver(),
    description: String,
    promptContributors: List<PromptContributor> = emptyList(),
    block: AgentBuilder.() -> Unit,
): Agent {
    return AgentBuilder(
        name = name,
        version = version,
        description = description,
        promptContributors = promptContributors,
    )
        .apply(block)
        .build()
}
