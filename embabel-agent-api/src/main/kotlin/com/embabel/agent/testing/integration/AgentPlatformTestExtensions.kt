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
package com.embabel.agent.testing.integration

import com.embabel.agent.api.common.autonomy.AutonomyProperties
import com.embabel.agent.spi.support.DefaultProcessIdGeneratorProperties
import com.embabel.agent.config.AgentPlatformProperties
import com.embabel.common.core.types.ZeroToOne

/**
 * Extension functions for creating test instances of properties that have migrated to AgentPlatformProperties.
 * These functions provide clean syntax for test object creation while maintaining constructor injection patterns.
 */

/**
 * Creates AutonomyProperties instance for testing with optional parameter overrides.
 * Uses null to indicate "use default value" vs explicit override.
 */
fun forAutonomyTesting(
    agentConfidenceCutOff: Double? = null,
    goalConfidenceCutOff: Double? = null
): AutonomyProperties {
    val autonomyConfig = AgentPlatformProperties.AutonomyConfig(
        agentConfidenceCutOff = agentConfidenceCutOff ?: 0.6,
        goalConfidenceCutOff = goalConfidenceCutOff ?: 0.6
    )
    val testPlatformProperties = AgentPlatformProperties(autonomy = autonomyConfig)
    return AutonomyProperties(testPlatformProperties)
}

/**
 * Creates DefaultProcessIdGeneratorProperties instance for testing with optional parameter overrides.
 * Uses null to indicate "use default value" vs explicit override.
 */
fun forProcessIdGenerationTesting(
    includeAgentName: Boolean? = null,
    includeVersion: Boolean? = null
): DefaultProcessIdGeneratorProperties {
    val processIdConfig = AgentPlatformProperties.ProcessIdGenerationConfig(
        includeAgentName = includeAgentName ?: false,
        includeVersion = includeVersion ?: false
    )
    val testPlatformProperties = AgentPlatformProperties(processIdGeneration = processIdConfig)
    return DefaultProcessIdGeneratorProperties(testPlatformProperties)
}
