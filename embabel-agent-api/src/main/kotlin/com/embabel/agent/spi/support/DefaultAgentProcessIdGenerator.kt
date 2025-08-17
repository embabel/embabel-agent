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
package com.embabel.agent.spi.support

import com.embabel.agent.core.Agent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.AgentProcessIdGenerator
import com.embabel.common.core.NameGenerator
import com.embabel.agent.config.AgentPlatformProperties
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

// MIGRATED: @ConfigurationProperties("embabel.process-id-generation") → AgentPlatformProperties.processIdGeneration
// Properties now sourced from embabel.agent.platform.process-id-generation.* in agent-platform.properties
@Component
class DefaultProcessIdGeneratorProperties(platformProperties: AgentPlatformProperties) {
    val includeVersion: Boolean = platformProperties.processIdGeneration.includeVersion
    val includeAgentName: Boolean = platformProperties.processIdGeneration.includeAgentName
}

/**
 * Create an informative process ID for the agent process.
 */
@Service
internal class DefaultAgentProcessIdGenerator(
    private val nameGenerator: NameGenerator,
    private val properties: DefaultProcessIdGeneratorProperties,
) : AgentProcessIdGenerator {

    override fun createProcessId(agent: Agent, processOptions: ProcessOptions): String {
        val agentName = if (properties.includeAgentName) {
            "${agent.name}-"
        } else {
            ""
        }
        val version = if (properties.includeVersion) {
            "${agent.version}-"
        } else {
            ""
        }
        val randomPart = nameGenerator.generateName()
        return "${agentName}$version$randomPart"
    }
}
