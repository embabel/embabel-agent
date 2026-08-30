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
import com.embabel.agent.api.annotation.support.AgenticComponentWithNoActionNoConditionNoGoalAnnotation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class AchievableGoalValidatorTest {
    val noActionErrorMessage = """@Action annotation is missing on the method 'com.embabel.agent.api.annotation.support.AgentWithAchievesGoalNoActionAnnotation.goal' annotated with @AchievesGoal."""

    @Test
    fun `no Action annotation on AchievesGoal method and skip-agent-on-error is false`(output: CapturedOutput) {
        val reader = AgentMetadataReader()
        val agentScope = reader.createAgentMetadata(AgentWithAchievesGoalNoActionAnnotation())
        assertNotNull(agentScope, "Validation error is unexpectedly not ignored.")
        assertTrue(output.out.contains(noActionErrorMessage), "Error message about missing @Action is absent.")
    }

    @Test
    fun `no Action annotation on AchievesGoal method of an Agent but skip-agent-on-error is true`(output: CapturedOutput) {
        val reader = AgentMetadataReader(skipAgentDeploymentOnError = true)
        val agentScope = reader.createAgentMetadata(AgentWithAchievesGoalNoActionAnnotation())
        assertNull(agentScope, "Validation error is unexpectedly ignored.")
        assertTrue(output.out.contains(noActionErrorMessage), "Error message about missing @Action is absent.")
        val className = "com.embabel.agent.api.annotation.support.AgentWithAchievesGoalNoActionAnnotation"
        assertTrue(output.out.contains("Agent $className is rejected as it has validation errors as reported above."), "Error message mentioning agent is missing.")
    }

    @Test
    fun `no Action annotation on AchievesGoal method of an Agentic component but skip-agent-on-error is true`(output: CapturedOutput) {
        val reader = AgentMetadataReader(skipAgentDeploymentOnError = true)
        val agentScope = reader.createAgentMetadata(AgenticComponentWithNoActionNoConditionNoGoalAnnotation())
        assertNull(agentScope, "Validation error is unexpectedly ignored.")
        val className = "com.embabel.agent.api.annotation.support.AgenticComponentWithNoActionNoConditionNoGoalAnnotation"
        assertTrue(output.out.contains("Agentic component $className is not registered due to no methods annotated with @Action or @Condition and no goals defined on $className"), "Error message about missing @Action is absent.")
        assertTrue(output.out.contains("Agentic component $className is rejected as it does not have any action, condition and goals."), "Error message mentioning Agentic component is missing.")
    }

    @Test
    fun `valid goal method`(output: CapturedOutput) {
        val reader = AgentMetadataReader()
        reader.createAgentMetadata(AgentWithValidAchievesGoalMethod())
        assertFalse(
            output.out.contains(noActionErrorMessage),
            "Error message about mission @Action is unexpectedly present."
        )
    }
}
