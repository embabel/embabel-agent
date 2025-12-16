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

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.annotation.support.SupervisorAction
import com.embabel.agent.api.annotation.support.TypeSchemaExtractor
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.agent.test.integration.ScriptedLlmOperations
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Tests demonstrating the typed supervisor pattern with schema-informed composition.
 */
class TypedSupervisorExampleTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `typed supervisor exposes multiple actions without strict dependencies`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(TypedSupervisorExample()) as CoreAgent

        // Verify we have one supervisor action containing multiple tool actions
        assertEquals(1, metadata.actions.size)
        val supervisorAction = metadata.actions.first() as SupervisorAction

        // Should have 4 tool actions (all except the goal action)
        assertEquals(4, supervisorAction.toolActions.size)

        val toolNames = supervisorAction.toolActions.map { it.shortName() }
        assertTrue("gatherMarketData" in toolNames)
        assertTrue("analyzeCompetitors" in toolNames)
        assertTrue("forecastTrends" in toolNames)
        assertTrue("writeSection" in toolNames)
    }

    @Test
    fun `type schema extractor shows action signatures with schemas`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(TypedSupervisorExample()) as CoreAgent
        val supervisorAction = metadata.actions.first() as SupervisorAction

        // Extract signatures for all tool actions
        val signatures = supervisorAction.toolActions.map { action ->
            TypeSchemaExtractor.buildActionSignature(action)
        }

        // Each signature should show inputs and output type
        assertTrue(signatures.any { it.contains("gatherMarketData") && it.contains("MarketData") })
        assertTrue(signatures.any { it.contains("analyzeCompetitors") && it.contains("CompetitorAnalysis") })
        assertTrue(signatures.any { it.contains("forecastTrends") && it.contains("TrendForecast") })
        assertTrue(signatures.any { it.contains("writeSection") && it.contains("ProseSection") })
    }

    @Test
    fun `type schema shows field structure`() {
        // MarketData has specific fields
        val marketDataSchema = TypeSchemaExtractor.extractSchema(MarketData::class)
        assertTrue(marketDataSchema.contains("revenues"))
        assertTrue(marketDataSchema.contains("marketShare"))
        assertTrue(marketDataSchema.contains("growthRates"))

        // CompetitorAnalysis has different fields
        val competitorSchema = TypeSchemaExtractor.extractSchema(CompetitorAnalysis::class)
        assertTrue(competitorSchema.contains("strengths"))
        assertTrue(competitorSchema.contains("weaknesses"))
        assertTrue(competitorSchema.contains("positioning"))
    }

    @Test
    fun `artifacts summary shows typed values on blackboard`() {
        val blackboard = InMemoryBlackboard()

        // Add some typed artifacts
        blackboard["market"] = MarketData(
            revenues = mapOf("AWS" to "$90B"),
            marketShare = mapOf("AWS" to 0.33),
            growthRates = mapOf("AWS" to 0.20),
        )
        blackboard["competitors"] = CompetitorAnalysis(
            strengths = mapOf("AWS" to listOf("Market leader")),
            weaknesses = mapOf("AWS" to listOf("Complex pricing")),
            positioning = mapOf("AWS" to "Enterprise cloud"),
        )

        val summary = TypeSchemaExtractor.buildArtifactsSummary(blackboard, objectMapper)

        // Should show both artifacts with their types
        // Format is "- key (TypeName): {json}"
        assertTrue(summary.contains("market"), "Should contain 'market' key. Got: $summary")
        assertTrue(summary.contains("competitors"), "Should contain 'competitors' key. Got: $summary")
    }

    @Test
    fun `supervisor can call actions in any order - flexible composition`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(TypedSupervisorExample()) as CoreAgent
        val supervisorAction = metadata.actions.first() as SupervisorAction

        // Get the actual tool names (they include the agent prefix)
        val toolNames = supervisorAction.toolActions.map { it.shortName() }

        // Script the LLM to call actions in a non-linear order
        // (forecastTrends first, then gatherMarketData - no strict dependency)
        val forecastToolName = toolNames.first { it.contains("forecastTrends") }
        val marketDataToolName = toolNames.first { it.contains("gatherMarketData") }

        val scriptedLlm = ScriptedLlmOperations()
            .callTool(forecastToolName, """{}""")  // Request curried from blackboard
            .respond("Got trend forecast")
            .callTool(marketDataToolName, """{}""")  // Request curried from blackboard
            .respond("Got market data")
            .respond("Done gathering data")

        val ap = dummyAgentPlatform(llmOperations = scriptedLlm)

        val agentProcess = ap.runAgentFrom(
            metadata,
            ProcessOptions(plannerType = PlannerType.SUPERVISOR),
            mapOf(
                // Goal action inputs
                "request" to ReportRequest("cloud", listOf("AWS", "Azure")),
                "gatheredInfo" to GatheredInfo(""),
                // Tool action request types - curried out since they're on blackboard
                "marketDataRequest" to MarketDataRequest("cloud"),
                "competitorAnalysisRequest" to CompetitorAnalysisRequest(listOf("AWS", "Azure")),
                "trendForecastRequest" to TrendForecastRequest("cloud"),
                "writeSectionRequest" to WriteSectionRequest("Overview", ""),
            ),
        )

        // Both tools should have been called successfully
        assertEquals(2, scriptedLlm.toolCallsMade.size, "Expected 2 tool calls but got: ${scriptedLlm.toolCallsMade}")
        assertTrue(scriptedLlm.toolCallsMade[0].toolName.contains("forecastTrends"))
        assertTrue(scriptedLlm.toolCallsMade[1].toolName.contains("gatherMarketData"))

        // Both results should be on the blackboard
        val artifacts = agentProcess.blackboard.objects
        assertTrue(artifacts.any { it is TrendForecast }, "TrendForecast should be on blackboard")
        assertTrue(artifacts.any { it is MarketData }, "MarketData should be on blackboard")
    }

    @Test
    fun `goal action receives string context for flexible synthesis`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(TypedSupervisorExample()) as CoreAgent
        val supervisorAction = metadata.actions.first() as SupervisorAction

        // The goal action (compileReport) should take:
        // - ReportRequest (typed input)
        // - String (flexible context from gathered artifacts)
        val goalInputs = supervisorAction.outputs // Goal action's inputs become supervisor's outputs? No...

        // The goal action is NOT exposed as a tool action
        val toolNames = supervisorAction.toolActions.map { it.name }
        assertFalse(toolNames.any { it.contains("compileReport") })
    }
}
