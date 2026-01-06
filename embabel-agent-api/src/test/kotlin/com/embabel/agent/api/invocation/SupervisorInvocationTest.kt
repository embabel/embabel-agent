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
package com.embabel.agent.api.invocation

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.EmbabelComponent
import com.embabel.agent.api.annotation.support.supervisor.*
import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.common.scope.AgentScopeBuilder
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.agent.test.integration.ScriptedLlmOperations
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Standalone @EmbabelComponent actions for supervisor orchestration.
 * These are NOT part of an @Agent - they're platform-level actions.
 */
@EmbabelComponent
class MarketDataActions {

    @Action(description = "Gather market data including revenues, market share, and growth rates")
    @Suppress("UNUSED_PARAMETER")
    fun gatherMarketData(
        request: MarketDataRequest,
        ai: Ai,
    ): MarketData {
        return MarketData(
            revenues = mapOf("CompanyA" to "$10B", "CompanyB" to "$8B"),
            marketShare = mapOf("CompanyA" to 0.4, "CompanyB" to 0.3),
            growthRates = mapOf("CompanyA" to 0.15, "CompanyB" to 0.12),
        )
    }
}

@EmbabelComponent
class CompetitorActions {

    @Action(description = "Analyze competitors: identify strengths, weaknesses, and market positioning")
    @Suppress("UNUSED_PARAMETER")
    fun analyzeCompetitors(
        request: CompetitorAnalysisRequest,
        ai: Ai,
    ): CompetitorAnalysis {
        return CompetitorAnalysis(
            strengths = request.companies.associateWith { listOf("Strong brand", "Market leader") },
            weaknesses = request.companies.associateWith { listOf("High costs", "Slow innovation") },
            positioning = request.companies.associateWith { "Premium segment" },
        )
    }
}

@EmbabelComponent
class TrendAndReportActions {

    @Action(description = "Forecast trends and predictions for a sector")
    @Suppress("UNUSED_PARAMETER")
    fun forecastTrends(
        request: TrendForecastRequest,
        ai: Ai,
    ): TrendForecast {
        return TrendForecast(
            trends = listOf("AI adoption", "Cloud migration", "Sustainability"),
            predictions = mapOf("2025" to "Market grows 20%", "2026" to "Consolidation expected"),
            confidence = 0.75,
        )
    }

    @Action(description = "Compile artifacts into the final report")
    @Suppress("UNUSED_PARAMETER")
    fun compileReport(
        request: ReportRequest,
        gatheredInfo: GatheredInfo,
        ai: Ai,
    ): FinalReport {
        return FinalReport(
            title = "Market Research Report: ${request.topic}",
            executiveSummary = "This report analyzes ${request.companies.joinToString(", ")} in the ${request.topic} sector.",
            sections = listOf(
                ProseSection("Overview", "Market overview based on: ${gatheredInfo.context}"),
                ProseSection("Analysis", "Competitive analysis findings"),
            ),
            conclusion = "The market shows strong growth potential with key players positioned for success.",
        )
    }
}

/**
 * Tests for [SupervisorInvocation] - running supervisor pattern against platform actions.
 */
class SupervisorInvocationTest {

    @Test
    fun `creates supervisor agent from platform actions`() {
        val scriptedLlm = ScriptedLlmOperations()
            .respond("Done")

        val ap = dummyAgentPlatform(llmOperations = scriptedLlm)

        val invocation = SupervisorInvocation.on(ap, FinalReport::class.java)
            .withGoalDescription("Create a comprehensive market research report")
            .withScope(
                AgentScopeBuilder.fromInstances(
                    MarketDataActions(),
                    CompetitorActions(),
                    TrendAndReportActions(),
                )
            )

        val agent = invocation.createSupervisorAgent()

        // Should have one supervisor action wrapping all tool actions
        assertEquals(1, agent.actions.size)
        assertTrue(agent.actions.first().name.contains("supervisor"))

        // Should have one goal targeting FinalReport
        assertEquals(1, agent.goals.size)
        assertTrue(agent.goals.first().outputType?.name?.contains("FinalReport") == true)
    }

    @Test
    fun `supervisor orchestrates platform actions to achieve goal`() {
        // Script the supervisor to call actions and then finish
        val scriptedLlm = ScriptedLlmOperations()
            .callTool("gatherMarketData", """{}""")
            .respond("Got market data")
            .callTool("forecastTrends", """{}""")
            .respond("Got trends")
            .respond("Done gathering data")

        val ap = dummyAgentPlatform(llmOperations = scriptedLlm)

        val invocation = SupervisorInvocation.on(ap, FinalReport::class.java)
            .withGoalDescription("Create a market research report")
            .withScope(
                AgentScopeBuilder.fromInstances(
                    MarketDataActions(),
                    CompetitorActions(),
                    TrendAndReportActions(),
                )
            )

        // Provide initial inputs
        val result = invocation.run(
            mapOf(
                "request" to ReportRequest("cloud", listOf("AWS", "Azure")),
                "gatheredInfo" to GatheredInfo("Initial context"),
                // Provide request types for tool actions
                "marketDataRequest" to MarketDataRequest("cloud"),
                "competitorAnalysisRequest" to CompetitorAnalysisRequest(listOf("AWS", "Azure")),
                "trendForecastRequest" to TrendForecastRequest("cloud"),
            )
        )

        // Should have called tools
        assertTrue(scriptedLlm.toolCallsMade.isNotEmpty(), "Should have called tools")

        // Should have produced artifacts
        val artifacts = result.blackboard.objects
        assertTrue(artifacts.any { it is MarketData }, "Should have MarketData on blackboard")
        assertTrue(artifacts.any { it is TrendForecast }, "Should have TrendForecast on blackboard")
    }

    @Test
    fun `corrects planner type if not SUPERVISOR`() {
        val scriptedLlm = ScriptedLlmOperations()
            .respond("Done")

        val ap = dummyAgentPlatform(llmOperations = scriptedLlm)

        // Create with wrong planner type
        val invocation = SupervisorInvocation.on(ap, MarketData::class.java)
            .withProcessOptions(ProcessOptions(plannerType = PlannerType.GOAP))
            .withScope(AgentScopeBuilder.fromInstances(MarketDataActions()))

        val agent = invocation.createSupervisorAgent()

        // Agent should still be valid
        assertNotNull(agent)
        assertEquals(1, agent.actions.size)
    }

    @Test
    fun `kotlin reified factory works`() {
        val scriptedLlm = ScriptedLlmOperations()
            .respond("Done")

        val ap = dummyAgentPlatform(llmOperations = scriptedLlm)

        // Use reified inline function
        val invocation = SupervisorInvocation
            .returning<MarketData>(ap)
            .withScope(AgentScopeBuilder.fromInstances(MarketDataActions()))

        val agent = invocation.createSupervisorAgent()
        assertTrue(agent.goals.first().outputType?.name?.contains("MarketData") == true)
    }
}
