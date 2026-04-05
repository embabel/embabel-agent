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

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Condition
import com.embabel.agent.core.JvmType
import com.embabel.agent.core.support.Rerun
import com.embabel.agent.domain.io.UserInput
import com.embabel.plan.common.condition.ConditionDetermination
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Goal-and-rerun metadata tests for the issue-949 investigation.
 *
 * Reviewer responsibility:
 * this file explains what the planner actually receives from annotations before any planning starts.
 * It exists to prevent issue-949 discussions from drifting into assumptions about goal construction or
 * rerun semantics that do not match the metadata layer.
 *
 * What this file proves:
 * - `@AchievesGoal` becomes "goal action preconditions + goal output binding + hasRun(goal action)".
 * - `canRerun = false` synthesizes blocking preconditions, while `canRerun = true` removes them.
 *
 * What this file weakens:
 * - that the issue goal is represented only by runtime `@Condition` methods,
 * - that rerun behavior is opaque until execution time.
 *
 * How it fits issue 949:
 * if a reviewer needs to know whether the bug is caused by bad goal metadata or hidden rerun rules,
 * this file gives that answer.
 */
class AchievesGoalAndRerunMetadataTest {

    /**
     * Characterization test: `acceptReport` contributes goal metadata through its declared preconditions,
     * output binding, and synthesized `hasRun` marker.
     */
    @Test
    fun `issue 949 goal metadata is built from goal action preconditions plus hasRun`() {
        val metadata = AgentMetadataReader().createAgentMetadata(Issue949MetadataAgent()) as CoreAgent

        val acceptAction = metadata.actions.single { it.name.endsWith(".acceptReport") }
        val goal = metadata.goals.single { it.name.endsWith(".acceptReport") }

        assertNotNull(goal.outputType)
        assertEquals(Issue949Report::class.java, (goal.outputType as JvmType).clazz)
        assertTrue(
            mapOf(
                Issue949MetadataAgent.REPORT_SATISFACTORY to ConditionDetermination.TRUE,
                "finalResearchReport:${Issue949Report::class.qualifiedName}" to ConditionDetermination.TRUE,
                Rerun.hasRunCondition(acceptAction) to ConditionDetermination.TRUE,
            ).all { (key, value) -> goal.preconditions[key] == value },
            "Goal should be satisfied by the goal action's preconditions, output binding, and hasRun marker",
        )
        assertFalse(
            goal.preconditions.containsKey(Issue949MetadataAgent.REPORT_UNSATISFACTORY),
            "Unsatisfactory branch conditions should not become goal preconditions",
        )
    }

    /**
     * Characterization test: rerun eligibility is decided in metadata via synthesized blocking
     * preconditions, not by a hidden runtime rule.
     */
    @Test
    fun `rerun metadata differs between single use and rerunnable actions`() {
        val metadata = AgentMetadataReader().createAgentMetadata(RerunMetadataAgent()) as CoreAgent

        val singleUse = metadata.actions.single { it.name.endsWith(".singleUseResearch") }
        val rerunnable = metadata.actions.single { it.name.endsWith(".rerunnableResearch") }

        assertTrue(
            mapOf(
                singleUse.outputs.single().value to ConditionDetermination.FALSE,
                Rerun.hasRunCondition(singleUse) to ConditionDetermination.FALSE,
            ).all { (key, value) -> singleUse.preconditions[key] == value },
            "Single-use actions should require the output to be absent and the action not to have run",
        )
        assertFalse(
            rerunnable.preconditions.containsKey(rerunnable.outputs.single().value),
            "Rerunnable actions should not be blocked by their prior output binding",
        )
        assertFalse(
            rerunnable.preconditions.containsKey(Rerun.hasRunCondition(rerunnable)),
            "Rerunnable actions should not synthesize hasRun=false",
        )
    }
}

data class Issue949Report(
    val content: String,
)

data class Issue949Critique(
    val accepted: Boolean,
    val reasoning: String,
)

@Agent(description = "Metadata fixture for issue 949")
class Issue949MetadataAgent {

    @Action(
        post = [REPORT_SATISFACTORY],
        canRerun = true,
        outputBinding = "gpt4Report",
    )
    fun researchWithGpt4(userInput: UserInput): Issue949Report =
        Issue949Report(userInput.content)

    @Action(
        pre = [REPORT_UNSATISFACTORY],
        post = [REPORT_SATISFACTORY],
        canRerun = true,
        outputBinding = "gpt4Report",
    )
    fun redoResearchWithGpt4(
        userInput: UserInput,
        critique: Issue949Critique,
    ): Issue949Report = Issue949Report("${userInput.content}:${critique.reasoning}")

    @Condition(name = REPORT_SATISFACTORY)
    fun makesTheGrade(critique: Issue949Critique): Boolean = critique.accepted

    @Condition(name = REPORT_UNSATISFACTORY)
    fun rejected(critique: Issue949Critique): Boolean = !critique.accepted

    @AchievesGoal(description = "Completes research")
    @Action(pre = [REPORT_SATISFACTORY], outputBinding = "finalResearchReport")
    fun acceptReport(
        mergedReport: Issue949Report,
        critique: Issue949Critique,
    ): Issue949Report = mergedReport

    companion object {
        const val REPORT_SATISFACTORY = "reportSatisfactory"
        const val REPORT_UNSATISFACTORY = "reportUnsatisfactory"
    }
}

@Agent(description = "Metadata fixture for canRerun behavior")
class RerunMetadataAgent {

    @Action(canRerun = false, outputBinding = "singleUseDraft")
    fun singleUseResearch(userInput: UserInput): Issue949Report =
        Issue949Report(userInput.content)

    @Action(canRerun = true, outputBinding = "rerunnableDraft")
    fun rerunnableResearch(userInput: UserInput): Issue949Report =
        Issue949Report(userInput.content)
}
