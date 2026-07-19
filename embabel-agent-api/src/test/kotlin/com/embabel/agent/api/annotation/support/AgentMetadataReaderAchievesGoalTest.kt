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
package com.embabel.agent.api.annotation.support

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class AgentMetadataReaderAchievesGoalTest {
    val noActionErrorMessage = """@Action annotation is missing from the method com.embabel.agent.api.annotation.support.AgentWithAchievesGoalNoActionAnnotation.goal."""
    val invalidReturnTypeErrorMessage = """@AchievesGoal cannot be applied to void-returning @Action method com.embabel.agent.api.annotation.support.AgentWithInvalidReturnTypeOnAchievesGoalMethod.goal."""

    @Test
    fun `no Action annotation on AchievesGoal method`(output: CapturedOutput) {
        val reader = AgentMetadataReader()
        reader.createAgentMetadata(AgentWithAchievesGoalNoActionAnnotation())
        assertTrue(output.out.contains(noActionErrorMessage), "Error message about missing @Action is absent.")
    }

    @Test
    fun `goal with with invalid return type`(output: CapturedOutput) {
        val reader = AgentMetadataReader()
        reader.createAgentMetadata(AgentWithInvalidReturnTypeOnAchievesGoalMethod())
        assertTrue(output.out.contains(invalidReturnTypeErrorMessage), "Error message about invalid return type is absent.")
    }

    @Test
    fun `valid goal method`(output: CapturedOutput) {
        val reader = AgentMetadataReader()
        reader.createAgentMetadata(AgentWithValidAchievesGoalMethod())
        assertFalse(output.out.contains(noActionErrorMessage) , "Error message about mission @Action is unexpectedly present.")
        assertFalse(output.out.contains(invalidReturnTypeErrorMessage), "Error message about invalid return type is unexpectedly present.")
    }
}
