/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.annotation.support.supervisor

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PlannerType

/**
 * Domain objects for a market research report.
 * These types are DESCRIPTIVE - they document what each action produces.
 * They are NOT PRESCRIPTIVE - there's no declared dependency chain.
 */

// Request types - enable actions to work with any planner
data class MarketDataRequest(val topic: String)
data class CompetitorAnalysisRequest(val companies: List<String>)
data class TrendForecastRequest(val sector: String)
data class WriteSectionRequest(val title: String, val context: String)
data class ReportRequest(val topic: String, val companies: List<String>)
data class GatheredInfo(val context: String)

// Output types - structured results with schemas visible to LLM
data class MarketData(
    val revenues: Map<String, String>,
    val marketShare: Map<String, Double>,
    val growthRates: Map<String, Double>,
)

data class CompetitorAnalysis(
    val strengths: Map<String, List<String>>,
    val weaknesses: Map<String, List<String>>,
    val positioning: Map<String, String>,
)

data class TrendForecast(
    val trends: List<String>,
    val predictions: Map<String, String>,
    val confidence: Double,
)

data class ProseSection(
    val title: String,
    val content: String,
)

data class FinalReport(
    val title: String,
    val executiveSummary: String,
    val sections: List<ProseSection>,
    val conclusion: String,
)

/**
 * A typed supervisor example demonstrating schema-informed flexible composition.
 *
 * Key differences from GOAP:
 * - Actions don't declare preconditions/effects
 * - The supervisor LLM sees TYPE SCHEMAS and decides composition at runtime
 * - Results are still TYPED and validated, but ordering is flexible
 *
 * Using wrapper request types (e.g., MarketDataRequest) enables these actions
 * to work with any planner - GOAP, Supervisor, or others.
 *
 * The LLM sees something like:
 * ```
 * Available actions:
 * - gatherMarketData(request: MarketDataRequest) -> MarketData
 *     Schema: { revenues: Map, marketShare: Map, growthRates: Map }
 * - analyzeCompetitors(request: CompetitorAnalysisRequest) -> CompetitorAnalysis
 *     Schema: { strengths: Map, weaknesses: Map, positioning: Map }
 * - forecastTrends(request: TrendForecastRequest) -> TrendForecast
 * - writeSection(request: WriteSectionRequest) -> ProseSection
 *
 * Current artifacts on blackboard:
 * - MarketData: { revenues: {AWS: "$90B", ...}, marketShare: {...} }
 * - CompetitorAnalysis: { strengths: {AWS: ["market leader"], ...} }
 *
 * Goal: FinalReport
 * ```
 *
 * The supervisor decides: "I have market data and competitor analysis.
 * I should forecast trends next, then write sections, then compile the report."
 *
 * This is TYPED but FLEXIBLE - the LLM understands schemas without being
 * constrained by declared dependencies.
 */
@Agent(
    planner = PlannerType.SUPERVISOR,
    description = "Market research report generator - composes typed artifacts flexibly",
)
class TypedSupervisorExample {

    /**
     * Gather market data for a topic.
     * Returns structured MarketData - the LLM sees this schema.
     */
    @Action(description = "Gather market data including revenues, market share, and growth rates")
    @Suppress("UNUSED_PARAMETER")
    fun gatherMarketData(request: MarketDataRequest, ai: Ai): MarketData {
        // In real usage, this would call ai.withDefaultLlm().createObject()
        return MarketData(
            revenues = mapOf("CompanyA" to "$10B", "CompanyB" to "$8B"),
            marketShare = mapOf("CompanyA" to 0.4, "CompanyB" to 0.3),
            growthRates = mapOf("CompanyA" to 0.15, "CompanyB" to 0.12),
        )
    }

    /**
     * Analyze competitors.
     * The LLM can call this with companies it learned about from MarketData,
     * but there's no DECLARED dependency - just flexible composition.
     */
    @Action(description = "Analyze competitors: identify strengths, weaknesses, and market positioning")
    @Suppress("UNUSED_PARAMETER")
    fun analyzeCompetitors(request: CompetitorAnalysisRequest, ai: Ai): CompetitorAnalysis {
        return CompetitorAnalysis(
            strengths = request.companies.associateWith { listOf("Strong brand", "Market leader") },
            weaknesses = request.companies.associateWith { listOf("High costs", "Slow innovation") },
            positioning = request.companies.associateWith { "Premium segment" },
        )
    }

    /**
     * Forecast industry trends.
     * Could be called before or after other actions - supervisor decides.
     */
    @Action(description = "Forecast trends and predictions for a sector")
    @Suppress("UNUSED_PARAMETER")
    fun forecastTrends(request: TrendForecastRequest, ai: Ai): TrendForecast {
        return TrendForecast(
            trends = listOf("AI adoption", "Cloud migration", "Sustainability"),
            predictions = mapOf("2025" to "Market grows 20%", "2026" to "Consolidation expected"),
            confidence = 0.75,
        )
    }

    /**
     * Write a prose section for the report.
     * Takes any context string - the supervisor composes available artifacts.
     */
    @Action(description = "Write a prose section for the report given a title and context")
    @Suppress("UNUSED_PARAMETER")
    fun writeSection(request: WriteSectionRequest, ai: Ai): ProseSection {
        return ProseSection(
            title = request.title,
            content = "Based on the analysis: ${request.context}. This section provides insights into the market dynamics.",
        )
    }

    /**
     * The goal action - produce the final report.
     * The supervisor should call this when it has gathered enough artifacts.
     *
     * Note: GatheredInfo contains synthesized context from the supervisor.
     */
    @AchievesGoal(description = "Compile all gathered information into a final report")
    @Action(description = "Compile artifacts into the final report")
    @Suppress("UNUSED_PARAMETER")
    fun compileReport(request: ReportRequest, gatheredInfo: GatheredInfo, ai: Ai): FinalReport {
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
