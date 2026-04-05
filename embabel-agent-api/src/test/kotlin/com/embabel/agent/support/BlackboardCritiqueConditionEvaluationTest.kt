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
package com.embabel.agent.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Condition
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.channel.DevNullOutputChannel
import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.core.*
import com.embabel.agent.core.support.BlackboardWorldStateDeterminer
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.plan.common.condition.ConditionDetermination
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Blackboard critique-condition evaluation tests for the issue-949 investigation.
 *
 * Reviewer responsibility:
 * this file isolates runtime truth. It shows what the blackboard-backed `@Condition` methods say once a
 * critique is missing or explicitly negative, so reviewers can separate runtime condition evaluation from
 * planner metadata and action-graph modeling.
 *
 * What this file proves:
 * - Missing `Critique` input causes the condition methods to resolve to `FALSE`, not `UNKNOWN`.
 * - A negative critique on the blackboard produces the expected runtime truth:
 *   `reportUnsatisfactory = TRUE` and `reportSatisfactory = FALSE`.
 *
 * What this file weakens:
 * - that this repro naturally flows through the multi-`UNKNOWN` TODO,
 * - that blackboard condition evaluation is itself misreading a negative critique.
 *
 * How it fits issue 949:
 * if a reviewer wants to know whether runtime condition evaluation is the source of the bad path,
 * this file says no.
 */
class BlackboardCritiqueConditionEvaluationTest {

    private val eventListener = EventSavingAgenticEventListener()
    private val mockPlatformServices = mockk<PlatformServices>()

    init {
        every { mockPlatformServices.eventListener } returns eventListener
        every { mockPlatformServices.llmOperations } returns mockk()
        every { mockPlatformServices.outputChannel } returns DevNullOutputChannel
    }

    /**
     * Characterization test: missing critique input resolves to `FALSE`, so this workflow does not enter
     * the `UNKNOWN` branch by default.
     */
    @Test
    fun `missing critique input makes computed conditions false not unknown`() {
        val determiner = createDeterminer(InMemoryBlackboard())

        assertEquals(ConditionDetermination.FALSE, determiner.determineCondition(Issue949ConditionAgent.REPORT_SATISFACTORY))
        assertEquals(ConditionDetermination.FALSE, determiner.determineCondition(Issue949ConditionAgent.REPORT_UNSATISFACTORY))
    }

    /**
     * Characterization test: once a negative critique exists, runtime condition evaluation correctly marks
     * the report unsatisfactory.
     */
    @Test
    fun `negative critique on blackboard makes unsatisfactory true and satisfactory false`() {
        val blackboard = InMemoryBlackboard()
        blackboard["critique"] = Issue949ConditionCritique(accepted = false, reasoning = "Insufficient data")
        val determiner = createDeterminer(blackboard)

        assertEquals(ConditionDetermination.FALSE, determiner.determineCondition(Issue949ConditionAgent.REPORT_SATISFACTORY))
        assertEquals(ConditionDetermination.TRUE, determiner.determineCondition(Issue949ConditionAgent.REPORT_UNSATISFACTORY))
    }

    /**
     * Creates the runtime condition evaluator used by the blackboard tests.
     *
     * The helper wires a mocked `AgentProcess` back to the supplied blackboard so `@Condition` methods are
     * resolved the same way they would be during planning, without needing a full agent execution.
     */
    private fun createDeterminer(blackboard: Blackboard): BlackboardWorldStateDeterminer {
        val mockAgentProcess = mockk<AgentProcess>()
        val agent = AgentMetadataReader().createAgentMetadata(Issue949ConditionAgent()) as com.embabel.agent.core.Agent

        every { mockAgentProcess.history } returns emptyList()
        every { mockAgentProcess.infoString(any()) } returns ""
        every { mockAgentProcess.getValue(any(), any()) } answers {
            blackboard.getValue(
                firstArg(),
                secondArg(),
                DataDictionary.fromDomainTypes(
                    "issue949",
                    blackboard.objects.map { JvmType(it.javaClass) }
                ),
            )
        }
        every { mockAgentProcess.getValue(any(), any(), any()) } answers {
            blackboard.getValue(
                firstArg(),
                secondArg(),
                thirdArg(),
            )
        }
        every { mockAgentProcess.get(any()) } answers {
            blackboard.get(firstArg())
        }
        every { mockAgentProcess.getCondition(any()) } answers {
            blackboard.getCondition(firstArg())
        }
        every { mockAgentProcess.agent } returns agent

        return BlackboardWorldStateDeterminer(
            processContext = ProcessContext(
                platformServices = mockPlatformServices,
                agentProcess = mockAgentProcess,
                processOptions = ProcessOptions(),
            ),
            logicalExpressionParser = com.embabel.agent.core.expression.LogicalExpressionParser.EMPTY,
        )
    }
}

data class Issue949ConditionReport(
    val content: String,
)

data class Issue949ConditionCritique(
    val accepted: Boolean,
    val reasoning: String,
)

@Agent(description = "Condition fixture for issue 949")
class Issue949ConditionAgent {

    @Action(post = [REPORT_SATISFACTORY], canRerun = true, outputBinding = "gpt4Report")
    fun researchWithGpt4(userInput: UserInput): Issue949ConditionReport =
        Issue949ConditionReport(userInput.content)

    @Action(
        pre = [REPORT_UNSATISFACTORY],
        post = [REPORT_SATISFACTORY],
        canRerun = true,
        outputBinding = "gpt4Report",
    )
    fun redoResearchWithGpt4(
        userInput: UserInput,
        critique: Issue949ConditionCritique,
    ): Issue949ConditionReport = Issue949ConditionReport("${userInput.content}:${critique.reasoning}")

    @Condition(name = REPORT_SATISFACTORY)
    fun makesTheGrade(critique: Issue949ConditionCritique): Boolean = critique.accepted

    @Condition(name = REPORT_UNSATISFACTORY)
    fun rejected(critique: Issue949ConditionCritique): Boolean = !critique.accepted

    @AchievesGoal(description = "Completes research")
    @Action(pre = [REPORT_SATISFACTORY], outputBinding = "finalResearchReport")
    fun acceptReport(
        gpt4Report: Issue949ConditionReport,
        critique: Issue949ConditionCritique,
    ): Issue949ConditionReport = gpt4Report

    companion object {
        const val REPORT_SATISFACTORY = "reportSatisfactory"
        const val REPORT_UNSATISFACTORY = "reportUnsatisfactory"
    }
}
