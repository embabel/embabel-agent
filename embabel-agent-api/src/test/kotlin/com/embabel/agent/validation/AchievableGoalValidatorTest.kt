package com.embabel.agent.validation

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.annotation.support.AgentWithAchievesGoalNoActionAnnotation
import com.embabel.agent.api.annotation.support.AgentWithValidAchievesGoalMethod
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
    fun `no Action annotation on AchievesGoal method but skip-agent-on-error is true`(output: CapturedOutput) {
        val reader = AgentMetadataReader(skipAgentDeploymentOnError = true)
        val agentScope = reader.createAgentMetadata(AgentWithAchievesGoalNoActionAnnotation())
        assertNull(agentScope, "Validation error is unexpectedly ignored.")
        assertTrue(output.out.contains(noActionErrorMessage), "Error message about missing @Action is absent.")
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
