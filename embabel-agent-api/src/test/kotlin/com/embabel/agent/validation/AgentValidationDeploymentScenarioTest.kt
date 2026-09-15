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
package com.embabel.agent.validation

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.annotation.support.AgentWithAchievesGoalNoActionAnnotation
import com.embabel.agent.api.annotation.support.AgentWithValidAchievesGoalMethod
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.common.core.validation.ValidationErrorCodes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class AgentValidationDeploymentScenarioTest {

    @Test
    fun `valid agent is deployed with either validation policy`() {
        listOf(false, true).forEach { skipAgentDeploymentOnError ->
            val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
            val agentScope = AgentMetadataReader(
                skipAgentDeploymentOnError = skipAgentDeploymentOnError,
            ).createAgentMetadata(AgentWithValidAchievesGoalMethod())

            assertNotNull(agentScope)
            agentPlatform.deploy(agentScope)

            assertTrue(agentPlatform.agents().any { it.name == agentScope.name })
        }
    }

    @Test
    fun `invalid agent is deployed under permissive policy and its error is logged once`(output: CapturedOutput) {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        val agentScope = AgentMetadataReader(
            skipAgentDeploymentOnError = false,
        ).createAgentMetadata(AgentWithAchievesGoalNoActionAnnotation())

        assertNotNull(agentScope)
        agentPlatform.deploy(agentScope)

        assertTrue(agentPlatform.agents().any { it.name == agentScope.name })
        assertEquals(1, validationErrorCount(output))
    }

    @Test
    fun `invalid agent is rejected under strict policy and its error is logged once`(output: CapturedOutput) {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        val agentScope = AgentMetadataReader(
            skipAgentDeploymentOnError = true,
        ).createAgentMetadata(AgentWithAchievesGoalNoActionAnnotation())

        assertNull(agentScope)
        assertTrue(agentPlatform.agents().isEmpty())
        assertEquals(1, validationErrorCount(output))
        assertTrue(output.out.contains("is rejected due to validation errors as reported above."))
    }

    private fun validationErrorCount(output: CapturedOutput): Int =
        output.out.lines().count { it.contains(ValidationErrorCodes.MISSING_ACTION_ANNOTATION) }
}
