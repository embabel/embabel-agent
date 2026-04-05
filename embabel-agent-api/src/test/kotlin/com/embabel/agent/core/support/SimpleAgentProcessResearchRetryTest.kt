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
package com.embabel.agent.core.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Condition
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.embabel.agent.api.annotation.Agent as AnnotatedAgent

/**
 * Agent-process research-retry tests for the critical issue-949 replan state.
 *
 * Reviewer responsibility:
 * this is the closest test file to the user-visible behavior in issue 949. It shows what the full process
 * does once a negative critique already exists on the
 * blackboard and stale `mergedReport` / `critique` artifacts are still present.
 *
 * The file intentionally keeps both sides of the investigation:
 * - a "don't do this" antipattern fixture where static satisfactory posts and a rerunnable initial action
 *   let the process shortcut through fresh research
 * - a corrected fixture where only the explicit retry path can re-establish satisfaction after critique
 *
 * What this file proves:
 * - the antipattern model really does reproduce the bad shortcut at the process layer,
 * - the corrected model routes through `redoResearchWithGpt4` first.
 *
 * How it fits issue 949:
 * if a reviewer wants the shortest path from the ticket to observable behavior, this is the file to read
 * after the planner-only characterization.
 */
class SimpleAgentProcessResearchRetryTest {

    /**
     * Antipattern example: if fresh research is rerunnable and also claims `reportSatisfactory`, the
     * process can replan through `researchWithGpt4 -> acceptReport` even after a negative critique.
     */
    @Test
    fun `negative critique with stale merged artifacts replans through fresh research in anti pattern fixture`() {
        val scenario = criticalStateScenario(issue949AntiPatternAgent(), "issue-949-anti-pattern")

        scenario.process.tick()

        val formulatedPlan = scenario.latestPlan()
        val actionNames = formulatedPlan.actions.map(::shortName)

        assertEquals(
            listOf("researchWithGpt4", "acceptReport"),
            actionNames,
            "The anti-pattern fixture should demonstrate the bad shortcut from the issue discussion",
        )
        assertTrue(
            scenario.process.history.last().actionName.endsWith(".researchWithGpt4"),
            "The first executed action from the critical state should match the formulated plan",
        )
        assertFalse(
            scenario.process.history.any { it.actionName.endsWith(".redoResearchWithGpt4") },
            "The anti-pattern fixture should bypass the explicit retry action",
        )
        assertEquals(
            AgentProcessStatusCode.RUNNING,
            scenario.process.status,
            "The process should still be running after the first replanned step",
        )
    }

    /**
     * Corrected-model contract: once a negative critique exists, the next plan should force the explicit
     * retry branch before acceptance.
     */
    @Test
    fun `negative critique must force retry before acceptance`() {
        val scenario = criticalStateScenario(issue949CorrectedAgent(), "issue-949-corrected")

        scenario.process.tick()

        val formulatedPlan = scenario.latestPlan()
        val actionNames = formulatedPlan.actions.map(::shortName)

        assertEquals(
            "redoResearchWithGpt4",
            actionNames.first(),
            "After a negative critique, replanning should start with the explicit retry action",
        )
        assertFalse(
            scenario.process.history.any { it.actionName.endsWith(".acceptReport") },
            "acceptReport must not execute on the replanning tick that chooses the retry action",
        )
    }

    /** Builds the antipattern fixture that preserves the issue-shaped shortcut behavior. */
    private fun issue949AntiPatternAgent(): Agent =
        AgentMetadataReader().createAgentMetadata(Issue949AntiPatternAgent()) as Agent

    /** Builds the corrected fixture where only the explicit retry action can re-establish satisfaction. */
    private fun issue949CorrectedAgent(): Agent =
        AgentMetadataReader().createAgentMetadata(Issue949CorrectedAgent()) as Agent

    /** Creates the shared post-critique process state used by both the antipattern and corrected fixtures. */
    private fun criticalStateScenario(agent: Agent, processId: String): Issue949CriticalStateScenario {
        val eventListener = EventSavingAgenticEventListener()
        val platformServices = dummyPlatformServices(eventListener = eventListener)
        val blackboard = InMemoryBlackboard().apply {
            this += UserInput("Research the market")
            this["gpt4Report"] = Issue949DraftReport("initial draft")
            this["mergedReport"] = Issue949MergedReport("merged draft")
            this["critique"] = Issue949Critique(accepted = false, reasoning = "Need more evidence")
        }
        val process = SimpleAgentProcess(
            id = processId,
            agent = agent,
            processOptions = ProcessOptions(),
            blackboard = blackboard,
            platformServices = platformServices,
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )
        return Issue949CriticalStateScenario(process, eventListener)
    }

    /** Converts framework-qualified action names into the short names used in assertions and review notes. */
    private fun shortName(action: com.embabel.plan.Action): String = action.name.substringAfterLast('.')
}

private data class Issue949CriticalStateScenario(
    val process: SimpleAgentProcess,
    val eventListener: EventSavingAgenticEventListener,
) {
    fun latestPlan() = eventListener.processEvents.filterIsInstance<AgentProcessPlanFormulatedEvent>().last().plan
}

data class Issue949DraftReport(
    val content: String,
)

data class Issue949MergedReport(
    val content: String,
)

data class Issue949Critique(
    val accepted: Boolean,
    val reasoning: String,
)

data class Issue949FinalReport(
    val content: String,
)

object Issue949TestConditions {
    const val REPORT_SATISFACTORY = "reportSatisfactory"
    const val REPORT_UNSATISFACTORY = "reportUnsatisfactory"
}

/**
 * Deliberately mis-modeled research workflow used as a "don't do this" example for issue 949.
 *
 * This fixture preserves the annotation shape that makes the process choose
 * `researchWithGpt4 -> acceptReport` after a negative critique, even though a human reading the workflow
 * would expect the explicit retry path to run first.
 *
 * Why this fixture is intentionally wrong:
 * - `researchWithGpt4` is marked `canRerun = true`, so the planner may treat it as a valid retry step even
 *   though the workflow also defines a dedicated retry action.
 * - `researchWithGpt4`, `mergeReports`, and `critiqueMergedReport` all statically advertise
 *   `reportSatisfactory`, even though the runtime truth for that condition is computed from
 *   `Critique.accepted`.
 * - the shared test blackboard already contains stale `mergedReport` and `critique` objects, so once the
 *   planner believes `reportSatisfactory = TRUE`, `acceptReport` becomes a cheap next step.
 *
 * What maintainers should take from this fixture:
 * - it is not demonstrating a framework fix;
 * - it is demonstrating how contradictory action annotations can create a bad plan;
 * - if a workflow has a dedicated retry action, the initial action should usually not also be the retry
 *   mechanism for the same output binding.
 */
@Suppress("unused")
@AnnotatedAgent(description = "Process-level issue 949 anti-pattern fixture", scan = false)
class Issue949AntiPatternAgent {

    /**
     * Initial research action, intentionally mis-modeled as both rerunnable and sufficient to satisfy the
     * acceptance condition.
     *
     * This is the core antipattern: after a negative critique, the planner can reuse this cheap action
     * instead of taking the explicit retry path.
     */
    @Action(
        post = [Issue949TestConditions.REPORT_SATISFACTORY],
        canRerun = true,
        outputBinding = "gpt4Report",
        cost = 1.0,
    )
    fun researchWithGpt4(userInput: UserInput): Issue949DraftReport = Issue949DraftReport("fresh:${userInput.content}")

    /**
     * Dedicated retry action that the workflow author likely intended to run after an unsatisfactory
     * critique.
     *
     * The antipattern exists because this action is more expensive than `researchWithGpt4`, while the
     * initial action is also allowed to rerun and claim satisfaction. That makes this explicit retry path
     * easy for the planner to bypass.
     */
    @Action(
        pre = [Issue949TestConditions.REPORT_UNSATISFACTORY],
        post = [Issue949TestConditions.REPORT_SATISFACTORY],
        canRerun = true,
        outputBinding = "gpt4Report",
        cost = 3.0,
    )
    fun redoResearchWithGpt4(
        userInput: UserInput,
        critique: Issue949Critique,
    ): Issue949DraftReport = Issue949DraftReport("redo:${userInput.content}:${critique.reasoning}")

    /**
     * Merge step, also intentionally wrong because it statically claims `reportSatisfactory`.
     *
     * Merging a draft does not actually prove the critique is positive, but advertising that condition here
     * gives the planner another way to believe the acceptance gate has already been met.
     */
    @Action(
        post = [Issue949TestConditions.REPORT_SATISFACTORY],
        outputBinding = "mergedReport",
        cost = 2.0,
    )
    fun mergeReports(gpt4Report: Issue949DraftReport): Issue949MergedReport = Issue949MergedReport("merged:${gpt4Report.content}")

    /**
     * Critique step that always emits a negative critique in this fixture.
     *
     * It also intentionally posts `reportSatisfactory`, which contradicts the runtime `@Condition`
     * evaluation below. That contradiction is part of the antipattern under test.
     */
    @Action(
        post = [Issue949TestConditions.REPORT_SATISFACTORY],
        outputBinding = "critique",
        cost = 2.0,
    )
    fun critiqueMergedReport(mergedReport: Issue949MergedReport): Issue949Critique = Issue949Critique(accepted = false, reasoning = "critique:${mergedReport.content}")

    /** Runtime truth for satisfaction: only an accepted critique should make the report satisfactory. */
    @Condition(name = Issue949TestConditions.REPORT_SATISFACTORY)
    fun reportSatisfactory(critique: Issue949Critique): Boolean = critique.accepted

    /** Runtime truth for dissatisfaction: a rejected critique should force retry work. */
    @Condition(name = Issue949TestConditions.REPORT_UNSATISFACTORY)
    fun reportUnsatisfactory(critique: Issue949Critique): Boolean = !critique.accepted

    /**
     * Final acceptance action.
     *
     * In the antipattern fixture this action is not wrong by itself; it becomes reachable too early because
     * earlier actions have already claimed `reportSatisfactory`.
     */
    @AchievesGoal(description = "Complete research")
    @Action(
        pre = [Issue949TestConditions.REPORT_SATISFACTORY],
        outputBinding = "finalResearchReport",
        cost = 1.0,
    )
    fun acceptReport(
        mergedReport: Issue949MergedReport,
        critique: Issue949Critique,
    ): Issue949FinalReport = Issue949FinalReport("${mergedReport.content}:${critique.reasoning}")
}

@Suppress("unused")
@AnnotatedAgent(description = "Process-level issue 949 corrected fixture", scan = false)
class Issue949CorrectedAgent {

    @Action(
        outputBinding = "gpt4Report",
        cost = 1.0,
    )
    fun researchWithGpt4(userInput: UserInput): Issue949DraftReport = Issue949DraftReport("fresh:${userInput.content}")

    @Action(
        pre = [Issue949TestConditions.REPORT_UNSATISFACTORY],
        post = [Issue949TestConditions.REPORT_SATISFACTORY],
        canRerun = true,
        outputBinding = "gpt4Report",
        cost = 3.0,
    )
    fun redoResearchWithGpt4(
        userInput: UserInput,
        critique: Issue949Critique,
    ): Issue949DraftReport = Issue949DraftReport("redo:${userInput.content}:${critique.reasoning}")

    @Action(
        outputBinding = "mergedReport",
        cost = 2.0,
    )
    fun mergeReports(gpt4Report: Issue949DraftReport): Issue949MergedReport = Issue949MergedReport("merged:${gpt4Report.content}")

    @Action(
        post = [Issue949TestConditions.REPORT_SATISFACTORY],
        outputBinding = "critique",
        cost = 2.0,
    )
    fun critiqueMergedReport(mergedReport: Issue949MergedReport): Issue949Critique = Issue949Critique(accepted = false, reasoning = "critique:${mergedReport.content}")

    @Condition(name = Issue949TestConditions.REPORT_SATISFACTORY)
    fun reportSatisfactory(critique: Issue949Critique): Boolean = critique.accepted

    @Condition(name = Issue949TestConditions.REPORT_UNSATISFACTORY)
    fun reportUnsatisfactory(critique: Issue949Critique): Boolean = !critique.accepted

    @AchievesGoal(description = "Complete research")
    @Action(
        pre = [Issue949TestConditions.REPORT_SATISFACTORY],
        outputBinding = "finalResearchReport",
        cost = 1.0,
    )
    fun acceptReport(
        mergedReport: Issue949MergedReport,
        critique: Issue949Critique,
    ): Issue949FinalReport = Issue949FinalReport("${mergedReport.content}:${critique.reasoning}")
}
