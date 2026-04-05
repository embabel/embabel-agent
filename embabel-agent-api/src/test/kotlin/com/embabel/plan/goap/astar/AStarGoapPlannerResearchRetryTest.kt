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
package com.embabel.plan.goap.astar

import com.embabel.plan.common.condition.ConditionAction
import com.embabel.plan.common.condition.ConditionDetermination
import com.embabel.plan.common.condition.ConditionGoal
import com.embabel.plan.common.condition.WorldStateDeterminer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Research-retry tests at the pure GOAP layer.
 *
 * Reviewer responsibility:
 * this file answers the narrow planner question for issue 949:
 * can the retry path be bypassed purely because of the static GOAP graph, before runtime blackboard
 * evaluation or process execution semantics are involved?
 *
 * These are characterization and control tests, not the final process-level regression test.
 *
 * What this file proves:
 * - a short `researchWithGpt4 -> acceptReport` path is reproducible from static effects alone,
 * - the modeled repro shape does not collapse to an empty plan,
 * - the retry branch reappears when only critique can make the report satisfactory.
 *
 * What this file weakens:
 * - the "optimizer emptied the plan" explanation for the modeled repro shape,
 * - the idea that dynamic `@Condition` evaluation is required to reproduce the bypass.
 *
 * How it fits issue 949:
 * if a reviewer wants to know whether A* itself can be tricked by misleading static effects, this is the
 * first file to read.
 */
class AStarGoapPlannerResearchRetryTest {

    /**
     * Characterization test: with early actions publishing `reportSatisfactory = TRUE`, the planner can
     * choose `researchWithGpt4 -> acceptReport` from static effects alone.
     */
    @Test
    fun `issue 949 repro shape prefers short static satisfactory path over retry branch`() {
        val planner = planner(
            USER_INPUT to ConditionDetermination.TRUE,
            CATEGORIZATION to ConditionDetermination.TRUE,
            GPT4_REPORT to ConditionDetermination.FALSE,
            MERGED_REPORT to ConditionDetermination.TRUE,
            CRITIQUE to ConditionDetermination.TRUE,
            REPORT_SATISFACTORY to ConditionDetermination.FALSE,
            REPORT_UNSATISFACTORY to ConditionDetermination.TRUE,
            FINAL_RESEARCH_REPORT to ConditionDetermination.FALSE,
            HAS_RUN_ACCEPT_REPORT to ConditionDetermination.FALSE,
        )

        val plan = planner.planToGoal(
            listOf(
                researchWithGpt4(postSatisfactory = true, cost = 1.0),
                redoResearchWithGpt4(postSatisfactory = true, cost = 3.0),
                mergeReports(postSatisfactory = true, cost = 2.0),
                critiqueMergedReport(postSatisfactory = true, cost = 2.0),
                acceptReport(),
            ),
            finalReportGoal(),
        )

        assertNotNull(plan, "Planner should find a path to the goal")
        assertEquals(
            listOf("researchWithGpt4", "acceptReport"), plan!!.actions.map { it.name },
            "Static reportSatisfactory effects let the planner bypass the retry loop",
        )
    }

    /**
     * Control test: the modeled issue-949 graph stays non-empty, so this fixture does not support the
     * "optimizer returned an empty completed plan" explanation.
     */
    @Test
    fun `issue 949 repro shape does not collapse to empty plan when goal starts unsatisfied`() {
        val planner = planner(
            USER_INPUT to ConditionDetermination.TRUE,
            CATEGORIZATION to ConditionDetermination.TRUE,
            GPT4_REPORT to ConditionDetermination.FALSE,
            MERGED_REPORT to ConditionDetermination.TRUE,
            CRITIQUE to ConditionDetermination.TRUE,
            REPORT_SATISFACTORY to ConditionDetermination.FALSE,
            REPORT_UNSATISFACTORY to ConditionDetermination.TRUE,
            FINAL_RESEARCH_REPORT to ConditionDetermination.FALSE,
            HAS_RUN_ACCEPT_REPORT to ConditionDetermination.FALSE,
        )

        val goal = finalReportGoal()
        val plan = planner.planToGoal(
            listOf(
                researchWithGpt4(postSatisfactory = true, cost = 1.0),
                redoResearchWithGpt4(postSatisfactory = true, cost = 3.0),
                mergeReports(postSatisfactory = true, cost = 2.0),
                critiqueMergedReport(postSatisfactory = true, cost = 2.0),
                acceptReport(),
            ),
            goal,
        )

        assertFalse(goal.isAchievable(planner.worldState()), "Goal should not be satisfied in the initial state")
        assertNotNull(plan, "Planner should still produce a path")
        assertTrue(plan!!.actions.isNotEmpty(), "Optimizer should not reduce this repro shape to an empty plan")
    }

    /**
     * Control test: once only critique can establish `reportSatisfactory`, the planner selects the retry
     * branch, so the retry path itself is not inherently missing.
     */
    @Test
    fun `retry path is selected when critique is the only action that can make report satisfactory`() {
        val planner = planner(
            USER_INPUT to ConditionDetermination.TRUE,
            CATEGORIZATION to ConditionDetermination.TRUE,
            GPT4_REPORT to ConditionDetermination.FALSE,
            MERGED_REPORT to ConditionDetermination.FALSE,
            CRITIQUE to ConditionDetermination.TRUE,
            REPORT_SATISFACTORY to ConditionDetermination.FALSE,
            REPORT_UNSATISFACTORY to ConditionDetermination.TRUE,
            FINAL_RESEARCH_REPORT to ConditionDetermination.FALSE,
            HAS_RUN_ACCEPT_REPORT to ConditionDetermination.FALSE,
        )

        val plan = planner.planToGoal(
            listOf(
                researchWithGpt4(postSatisfactory = false, cost = 3.0),
                redoResearchWithGpt4(postSatisfactory = false, cost = 1.0),
                mergeReports(postSatisfactory = false, cost = 1.0),
                critiqueMergedReport(postSatisfactory = true, cost = 1.0),
                acceptReport(),
            ),
            finalReportGoal(),
        )

        assertNotNull(plan, "Planner should find a retry path")
        assertEquals(
            listOf("redoResearchWithGpt4", "mergeReports", "critiqueMergedReport", "acceptReport"),
            plan!!.actions.map { it.name },
            "Without misleading static satisfactory effects on earlier actions, the retry path should be required",
        )
    }

    /** Builds a planner whose starting world state is fully explicit in each test. */
    private fun planner(vararg conditions: Pair<String, ConditionDetermination>): AStarGoapPlanner =
        AStarGoapPlanner(WorldStateDeterminer.fromMap(conditions.toMap()))

    /**
     * Returns the acceptance goal used in all three planner tests.
     *
     * The goal intentionally mirrors how the issue is reasoned about at the GOAP layer:
     * the report must be satisfactory, the final report artifact must exist, and the acceptance
     * step itself must have run.
     */
    private fun finalReportGoal(): ConditionGoal =
        ConditionGoal(
            name = "acceptReportGoal",
            preconditions = mapOf(
                REPORT_SATISFACTORY to ConditionDetermination.TRUE,
                FINAL_RESEARCH_REPORT to ConditionDetermination.TRUE,
                HAS_RUN_ACCEPT_REPORT to ConditionDetermination.TRUE,
            ),
        )

    /**
     * Models the first research pass.
     *
     * The `postSatisfactory` flag is the key experimental switch in these tests: when true, this action
     * claims early success and can let the planner bypass critique-driven retry.
     */
    private fun researchWithGpt4(
        postSatisfactory: Boolean,
        cost: Double,
    ) = ConditionAction(
        name = "researchWithGpt4",
        preconditions = mapOf(
            USER_INPUT to ConditionDetermination.TRUE,
            CATEGORIZATION to ConditionDetermination.TRUE,
        ),
        effects = mutableMapOf(
            GPT4_REPORT to ConditionDetermination.TRUE,
        ).apply {
            if (postSatisfactory) {
                put(REPORT_SATISFACTORY, ConditionDetermination.TRUE)
            }
        },
        cost = { cost },
    )

    /**
     * Models the retry research step that should only be available after a negative critique.
     *
     * This is the branch the issue is expected to use once the report is known to be unsatisfactory.
     */
    private fun redoResearchWithGpt4(
        postSatisfactory: Boolean,
        cost: Double,
    ) = ConditionAction(
        name = "redoResearchWithGpt4",
        preconditions = mapOf(
            USER_INPUT to ConditionDetermination.TRUE,
            CATEGORIZATION to ConditionDetermination.TRUE,
            CRITIQUE to ConditionDetermination.TRUE,
            REPORT_UNSATISFACTORY to ConditionDetermination.TRUE,
        ),
        effects = mutableMapOf(
            GPT4_REPORT to ConditionDetermination.TRUE,
        ).apply {
            if (postSatisfactory) {
                put(REPORT_SATISFACTORY, ConditionDetermination.TRUE)
            }
        },
        cost = { cost },
    )

    /**
     * Models the merge step between drafting and critique.
     *
     * As with the research step, `postSatisfactory` lets the tests compare an honest graph against one
     * that incorrectly advertises success before critique runs.
     */
    private fun mergeReports(
        postSatisfactory: Boolean,
        cost: Double,
    ) = ConditionAction(
        name = "mergeReports",
        preconditions = mapOf(
            GPT4_REPORT to ConditionDetermination.TRUE,
        ),
        effects = mutableMapOf(
            MERGED_REPORT to ConditionDetermination.TRUE,
        ).apply {
            if (postSatisfactory) {
                put(REPORT_SATISFACTORY, ConditionDetermination.TRUE)
            }
        },
        cost = { cost },
    )

    /**
     * Models the critique step that produces the `CRITIQUE` fact and, in the control scenario, is the only
     * legitimate source of `reportSatisfactory = TRUE`.
     */
    private fun critiqueMergedReport(
        postSatisfactory: Boolean,
        cost: Double,
    ) = ConditionAction(
        name = "critiqueMergedReport",
        preconditions = mapOf(
            MERGED_REPORT to ConditionDetermination.TRUE,
        ),
        effects = mutableMapOf(
            CRITIQUE to ConditionDetermination.TRUE,
        ).apply {
            if (postSatisfactory) {
                put(REPORT_SATISFACTORY, ConditionDetermination.TRUE)
            }
        },
        cost = { cost },
    )

    /** Models the terminal acceptance step whose preconditions define the planner's success criteria. */
    private fun acceptReport() = ConditionAction(
        name = "acceptReport",
        preconditions = mapOf(
            MERGED_REPORT to ConditionDetermination.TRUE,
            CRITIQUE to ConditionDetermination.TRUE,
            REPORT_SATISFACTORY to ConditionDetermination.TRUE,
        ),
        effects = mapOf(
            FINAL_RESEARCH_REPORT to ConditionDetermination.TRUE,
            HAS_RUN_ACCEPT_REPORT to ConditionDetermination.TRUE,
        ),
        cost = { 1.0 },
    )

    companion object {
        private const val USER_INPUT = "userInput"
        private const val CATEGORIZATION = "categorization"
        private const val GPT4_REPORT = "gpt4Report"
        private const val MERGED_REPORT = "mergedReport"
        private const val CRITIQUE = "critique"
        private const val REPORT_SATISFACTORY = "reportSatisfactory"
        private const val REPORT_UNSATISFACTORY = "reportUnsatisfactory"
        private const val FINAL_RESEARCH_REPORT = "finalResearchReport"
        private const val HAS_RUN_ACCEPT_REPORT = "hasRun_acceptReport"
    }
}
