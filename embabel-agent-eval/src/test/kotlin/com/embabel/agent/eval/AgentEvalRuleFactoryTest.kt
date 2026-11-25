package com.example.agenteval

import com.embabel.agent.api.dsl.AgentBuilder
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.example.agenteval.rules.ActionRules
import com.example.agenteval.rules.CostRules
import com.example.agenteval.rules.StatusRules
import com.example.agenteval.rules.TimingRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

// --- domain types the agent's actions chain on ---
data class Intent(val topic: String = "eval")
data class Docs(val items: List<String> = listOf("doc-1"))
data class Answer(val text: String)

class AgentEvalRuleFactoryTest {

    private val factory = AgentEvalRuleFactory()

    // Dummy platform: no Spring, no real LLM needed (all actions are plain transformations)
    private val platform = IntegrationTestUtils.dummyAgentPlatform()

    // A real runnable agent: parse_intent -> retrieve_docs -> draft_answer, goal "answer"
    private val agent = AgentBuilder(
        name = "eval-pipeline-agent",
        description = "Three-step pipeline for evaluating AgentEvalRuleFactory",
    ).apply {
        transformation<UserInput, Intent>(name = "parse_intent") { Intent() }
        transformation<Intent, Docs>(name = "retrieve_docs") { Docs() }
        transformation<Docs, Answer>(name = "draft_answer") { Answer("the answer") }
        goal(
            name = "answer",
            description = "Produce a final answer",
            satisfiedBy = Answer::class,
        )
    }.build()

    private val expectedPlan = listOf("parse_intent", "retrieve_docs", "draft_answer")

    private lateinit var process: AgentProcess

    @BeforeEach
    fun runAgentOnce() {
        // NOT_STARTED -> run to completion; UserInput is bound on the blackboard as the entry object
        process = platform
            .createAgentProcessFrom(agent, ProcessOptions(), UserInput("evaluate me"))
            .apply { run() }
    }

    // ---------- status rules (StatusRules) ----------

    @Test
    fun `process is NOT_STARTED before run`() {
        val fresh = platform.createAgentProcessFrom(agent, ProcessOptions(), UserInput("hi"))
        assertEquals(AgentProcessStatusCode.NOT_STARTED, fresh.status)
        assertTrue(factory.byName("isStillRunning").evaluate(fresh))
        assertFalse(factory.byName("isFinished").evaluate(fresh))
        assertFalse(StatusRules.isFinished.evaluate(fresh))
    }

    @Test
    fun `run completes and is not failed`() {
        assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
        assertFalse(StatusRules.isFailed.evaluate(process))
        assertTrue(StatusRules.isCompleted.evaluate(process))
        assertTrue(factory.byName("isFinished").evaluate(process))
    }

    // ---------- action / plan rules (ActionRules) ----------

    @Test
    fun `agent followed its plan`() {
        assertTrue(ActionRules.followedActionsInOrder(expectedPlan).evaluate(process))
        assertTrue(ActionRules.followedExactPlan(expectedPlan).evaluate(process))
        assertTrue(ActionRules.executedAction("retrieve_docs").evaluate(process))
        assertTrue(ActionRules.firstActionWas("parse_intent").evaluate(process))
        assertTrue(ActionRules.lastActionWas("draft_answer").evaluate(process))
        assertTrue(ActionRules.onlyDeclaredActions.evaluate(process))

        // wrong order must fail the strict rule
        assertFalse(
            ActionRules.followedExactPlan(listOf("retrieve_docs", "parse_intent", "draft_answer"))
                .evaluate(process)
        )
    }

    // ---------- timing rules (TimingRules) ----------

    @Test
    fun `tooSlow threshold is changeable`() {
        // default: a fast dummy run is well under 30s
        assertFalse(TimingRules.tooSlow().evaluate(process))

        // user-changed value per call: any real run takes more than 1ms
        assertTrue(
            TimingRules.tooSlow(Duration.ofMillis(1)).evaluate(process),
            "run should take longer than 1ms"
        )
    }

    @Test
    fun `action succeeded in time`() {
        assertTrue(TimingRules.succeededWithinTimeLimit(Duration.ofSeconds(10)).evaluate(process))
        assertTrue(TimingRules.actionCompletedWithin("retrieve_docs", Duration.ofSeconds(5)).evaluate(process))
        assertFalse(TimingRules.actionCompletedWithin("retrieve_docs", Duration.ofSeconds(-1)).evaluate(process))
    }

    // ---------- token / cost rules (CostRules) ----------

    @Test
    fun `cost and token rules on a transformation-only run`() {
        val report = CostRules.costReport(process)
        assertEquals(0, report.llmCalls)              // dummy platform: no LLM calls
        assertTrue(CostRules.usedNoLlm.evaluate(process))
        assertFalse(factory.byName("overTokenBudget").evaluate(process))
        assertFalse(factory.byName("overCostBudget").evaluate(process))

        // user-changeable budgets: flip them tiny and the rules must fire
        assertTrue(CostRules.overTokenBudget(-1).evaluate(process))
        assertTrue(CostRules.overCostBudget(-1.0).evaluate(process))
    }


    // ---------- composition & reporting ----------

    @Test
    fun `goal achieved and composed rule passes`() {
        assertTrue(StatusRules.achievedGoal("answer").evaluate(process))

        val cleanRun = StatusRules.isFailed.not()
            .and(ActionRules.followedActionsInOrder(expectedPlan))
            .and(ActionRules.onlyDeclaredActions)
            .and(TimingRules.succeededWithinTimeLimit(Duration.ofSeconds(30)))
        assertTrue(cleanRun.evaluate(process))
    }

    @Test
    fun `evaluateAll gives a full report`() {
        val report = factory.evaluateAll(process)
        assertTrue(report.getValue("isFinished"))
        assertFalse(report.getValue("isFailed"))
        assertTrue(report.getValue("onlyDeclaredActions"))
        assertFalse(report.getValue("tooManyActions"))
        assertFalse(report.getValue("tooSlow"))
        assertFalse(report.getValue("overTokenBudget"))
        assertFalse(report.getValue("overCostBudget"))
    }
}