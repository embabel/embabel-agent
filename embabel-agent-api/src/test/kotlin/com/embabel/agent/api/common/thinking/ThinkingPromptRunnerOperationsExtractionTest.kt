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
package com.embabel.agent.api.common.thinking

import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.SuppressThinkingConverter
import com.embabel.chat.ChatResponseWithThinking
import com.embabel.common.core.thinking.ThinkingTagType
import com.embabel.common.core.thinking.spi.extractAllThinkingBlocks
import com.embabel.common.core.thinking.spi.InternalThinkingApi
import org.springframework.ai.converter.BeanOutputConverter
import org.junit.jupiter.api.Test
import io.mockk.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(InternalThinkingApi::class)
/**
 * Business scenario extraction tests for thinking blocks functionality.
 *
 * Tests real thinking block extraction from raw LLM responses.
 * Each test covers a specific thinking block format scenario:
 *
 * 1. Single <think> block (TAG format only)
 * 2. Multiple TAG blocks (<think>, <analysis>, <diagnosis>)
 * 3. PREFIX format (//THINKING: lines only)
 * 4. NO_PREFIX format (raw content before JSON only)
 * 5. Mixed formats (TAG + PREFIX + NO_PREFIX combined)
 */
class ThinkingPromptRunnerOperationsExtractionTest {

    @Test
    fun `should extract single think TAG block from raw LLM response`() {
        // Scenario 1: Raw LLM response with ONLY single <think> tag + JSON
        val rawLlmResponse = """
            <think>
            Analyzing Q3 performance data:
            - Revenue down 8% vs Q2 due to supply chain issues
            - Customer satisfaction dropped from 4.2 to 3.8
            - Competition increased pricing pressure in EMEA region
            Need to focus on operational efficiency and customer retention
            </think>

            {
                "quarterlyTrend": "declining",
                "primaryConcerns": ["supply_chain", "customer_satisfaction", "competitive_pressure"],
                "confidenceLevel": 0.87
            }
        """.trimIndent()

        val result = executeThinkingExtraction(rawLlmResponse, "quarterly-analysis", QuarterlyAnalysis::class.java)

        // Then: Exactly 1 TAG block extracted, no PREFIX or NO_PREFIX
        assertEquals(1, result.thinkingBlocks.size)

        val thinkBlock = result.thinkingBlocks.first()
        assertEquals(ThinkingTagType.TAG, thinkBlock.tagType)
        assertEquals("think", thinkBlock.tagValue)
        assertTrue(thinkBlock.content.contains("Revenue down 8%"))
        assertTrue(thinkBlock.content.contains("Customer satisfaction dropped"))
        assertTrue(thinkBlock.content.contains("operational efficiency"))

        // Verify ONLY TAG type, no other formats
        assertEquals(1, result.thinkingBlocks.count { it.tagType == ThinkingTagType.TAG })
        assertEquals(0, result.thinkingBlocks.count { it.tagType == ThinkingTagType.PREFIX })
        assertEquals(0, result.thinkingBlocks.count { it.tagType == ThinkingTagType.NO_PREFIX })

        // Object converted correctly from raw response
        assertEquals("declining", result.result!!.quarterlyTrend)
        assertEquals(0.87, result.result.confidenceLevel)
    }

    @Test
    fun `should extract multiple TAG blocks from raw LLM response`() {
        // Scenario 2: Raw LLM response with ONLY multiple TAG blocks + JSON (no PREFIX, no NO_PREFIX)
        val rawLlmResponse = """
            <think>
            I need to analyze this technical problem step by step.
            First, let me understand the current system state.
            </think>

            <analysis>
            The data shows clear performance issues in the database layer.
            Query response times have increased from 0.8s to 2.3s average.
            CPU utilization is normal but memory usage is at 89%.
            </analysis>

            <thought>
            Based on the analysis, this appears to be memory pressure affecting query cache.
            The database connection pool is likely misconfigured.
            We need immediate optimization of database connections.
            </thought>

            {
                "primaryIssue": "database_performance",
                "rootCause": "memory_pressure_affecting_query_cache",
                "confidence": 0.92,
                "recommendedActions": ["optimize_db_connections", "review_query_cache", "monitor_memory"]
            }
        """.trimIndent()

        val result = executeThinkingExtraction(rawLlmResponse, "technical-analysis", TechnicalAnalysis::class.java)

        // Then: Exactly 3 TAG blocks extracted, no PREFIX or NO_PREFIX
        assertEquals(3, result.thinkingBlocks.size)

        val thinkBlock = result.thinkingBlocks.find { it.tagValue == "think" }
        assertNotNull(thinkBlock)
        assertEquals(ThinkingTagType.TAG, thinkBlock.tagType)
        assertEquals("think", thinkBlock.tagValue)
        assertTrue(thinkBlock.content.contains("step by step"))

        val analysisBlock = result.thinkingBlocks.find { it.tagValue == "analysis" }
        assertNotNull(analysisBlock)
        assertEquals(ThinkingTagType.TAG, analysisBlock.tagType)
        assertEquals("analysis", analysisBlock.tagValue)
        assertTrue(analysisBlock.content.contains("Query response times"))

        val thoughtBlock = result.thinkingBlocks.find { it.tagValue == "thought" }
        assertNotNull(thoughtBlock)
        assertEquals(ThinkingTagType.TAG, thoughtBlock.tagType)
        assertEquals("thought", thoughtBlock.tagValue)
        assertTrue(thoughtBlock.content.contains("memory pressure affecting query cache"))

        // Verify ONLY TAG type blocks, no PREFIX or NO_PREFIX
        assertEquals(3, result.thinkingBlocks.count { it.tagType == ThinkingTagType.TAG })
        assertEquals(0, result.thinkingBlocks.count { it.tagType == ThinkingTagType.PREFIX })
        assertEquals(0, result.thinkingBlocks.count { it.tagType == ThinkingTagType.NO_PREFIX })

        // Object converted correctly
        assertEquals("database_performance", result.result!!.primaryIssue)
        assertEquals(0.92, result.result.confidence)
    }

    @Test
    fun `should extract PREFIX thinking from raw LLM response`() {
        // Scenario 3: Raw LLM response with ONLY PREFIX format (//THINKING:) + JSON (no TAG, no NO_PREFIX)
        val rawLlmResponse = """
            //THINKING: I need to evaluate the technical options systematically
            //THINKING: The current system has performance bottlenecks that need addressing
            //THINKING: Database optimization should be the first step before scaling
            //THINKING: Load balancing needs immediate attention to prevent outages

            {
                "primaryAction": "database_optimization",
                "secondaryAction": "horizontal_scaling",
                "confidence": 0.92,
                "estimatedDuration": "2_weeks"
            }
        """.trimIndent()

        val result = executeThinkingExtraction(rawLlmResponse, "technical-evaluation", TechnicalEvaluation::class.java)

        // Then: Only PREFIX thinking blocks extracted
        assertTrue(result.thinkingBlocks.size >= 1) // Should have at least 1 PREFIX block

        val prefixBlocks = result.thinkingBlocks.filter { it.tagType == ThinkingTagType.PREFIX }
        assertTrue(prefixBlocks.isNotEmpty())

        prefixBlocks.forEach { block ->
            assertEquals(ThinkingTagType.PREFIX, block.tagType)
            assertEquals("THINKING", block.tagValue)
            assertTrue(block.content.trim().isNotEmpty())
        }

        // Verify ONLY PREFIX type, no TAG or NO_PREFIX
        assertEquals(0, result.thinkingBlocks.count { it.tagType == ThinkingTagType.TAG })
        assertTrue(result.thinkingBlocks.count { it.tagType == ThinkingTagType.PREFIX } >= 1)
        assertEquals(0, result.thinkingBlocks.count { it.tagType == ThinkingTagType.NO_PREFIX })

        // Should contain the prefix reasoning content
        val allContent = result.thinkingBlocks.joinToString(" ") { it.content }
        assertTrue(allContent.contains("evaluate the technical options") || allContent.contains("technical options"))
        assertTrue(allContent.contains("performance bottlenecks") || allContent.contains("bottlenecks"))

        // Object converted correctly
        assertEquals("database_optimization", result.result!!.primaryAction)
        assertEquals(0.92, result.result!!.confidence)
    }

    @Test
    fun `should extract NO_PREFIX content from raw LLM response`() {
        // Scenario 4: Raw LLM response with ONLY NO_PREFIX format (raw content before JSON, no tags, no //THINKING:)
        val rawLlmResponse = """
            This is a complex customer service scenario that requires careful analysis.
            The customer has been experiencing issues for 3 weeks now.
            We need to prioritize a resolution that addresses both the immediate problem
            and prevents future occurrences. The engineering team should be involved
            because this appears to be a systemic issue affecting multiple users.

            {
                "priority": "urgent",
                "assignTo": "engineering_team",
                "estimatedResolution": "48_hours",
                "followUpRequired": true
            }
        """.trimIndent()

        val result = executeThinkingExtraction(rawLlmResponse, "customer-support", CustomerSupport::class.java)

        // Then: Only NO_PREFIX thinking extracted
        assertTrue(result.thinkingBlocks.size >= 1) // Should have at least 1 NO_PREFIX block

        val noPrefixBlocks = result.thinkingBlocks.filter { it.tagType == ThinkingTagType.NO_PREFIX }
        assertTrue(noPrefixBlocks.isNotEmpty())

        noPrefixBlocks.forEach { block ->
            assertEquals(ThinkingTagType.NO_PREFIX, block.tagType)
            assertEquals("", block.tagValue) // Empty tag value for NO_PREFIX
        }

        // Verify ONLY NO_PREFIX type, no TAG or PREFIX
        assertEquals(0, result.thinkingBlocks.count { it.tagType == ThinkingTagType.TAG })
        assertEquals(0, result.thinkingBlocks.count { it.tagType == ThinkingTagType.PREFIX })
        assertTrue(result.thinkingBlocks.count { it.tagType == ThinkingTagType.NO_PREFIX } >= 1)

        val reasoningContent = noPrefixBlocks.first().content
        assertTrue(reasoningContent.contains("complex customer service scenario"))
        assertTrue(reasoningContent.contains("experiencing issues for 3 weeks"))
        assertTrue(reasoningContent.contains("engineering team should be involved"))

        // Object converted correctly
        assertEquals("urgent", result.result!!.priority)
        assertEquals("engineering_team", result.result.assignTo)
        assertEquals(true, result.result.followUpRequired)
    }

    @Test
    fun `should extract mixed formats from raw LLM response`() {
        // Scenario 5: Raw LLM response with ALL THREE formats combined (TAG + PREFIX + NO_PREFIX)
        val rawLlmResponse = """
            <think>
            This is a comprehensive analysis that requires multiple perspectives.
            I need to evaluate both technical and business considerations.
            </think>

            //THINKING: The technical constraints are significant but not insurmountable
            //THINKING: Budget limitations will affect our timeline choices

            Raw reasoning without specific formatting tags appears here.
            The stakeholder requirements are complex and sometimes conflicting.
            We need to find a balanced approach that satisfies core needs.

            <final>
            Final assessment: proceed with phased implementation.
            Phase 1 focuses on critical functionality, Phase 2 on optimization.
            </final>

            {
                "approach": "phased_implementation",
                "phase1Duration": "6_weeks",
                "phase2Duration": "4_weeks",
                "riskLevel": "medium",
                "stakeholderAlignment": "achieved"
            }
        """.trimIndent()

        val result = executeThinkingExtraction(rawLlmResponse, "comprehensive-analysis", ComprehensiveAnalysis::class.java)

        // Then: ALL three thinking types should be present
        val tagBlocks = result.thinkingBlocks.filter { it.tagType == ThinkingTagType.TAG }
        val prefixBlocks = result.thinkingBlocks.filter { it.tagType == ThinkingTagType.PREFIX }
        val noPrefixBlocks = result.thinkingBlocks.filter { it.tagType == ThinkingTagType.NO_PREFIX }

        assertTrue(tagBlocks.isNotEmpty(), "Should have TAG blocks")
        assertTrue(prefixBlocks.isNotEmpty(), "Should have PREFIX blocks")
        assertTrue(noPrefixBlocks.isNotEmpty(), "Should have NO_PREFIX blocks")

        // Verify TAG blocks
        val thinkBlock = tagBlocks.find { it.tagValue == "think" }
        assertNotNull(thinkBlock)
        assertEquals(ThinkingTagType.TAG, thinkBlock.tagType)
        assertEquals("think", thinkBlock.tagValue)
        assertTrue(thinkBlock.content.contains("comprehensive analysis"))

        val finalBlock = tagBlocks.find { it.tagValue == "final" }
        assertNotNull(finalBlock)
        assertEquals(ThinkingTagType.TAG, finalBlock.tagType)
        assertEquals("final", finalBlock.tagValue)
        assertTrue(finalBlock.content.contains("phased implementation"))

        // Verify PREFIX blocks
        prefixBlocks.forEach { block ->
            assertEquals(ThinkingTagType.PREFIX, block.tagType)
            assertEquals("THINKING", block.tagValue)
        }
        val allPrefixContent = prefixBlocks.joinToString(" ") { it.content }
        assertTrue(allPrefixContent.contains("technical constraints") || allPrefixContent.contains("constraints"))
        assertTrue(allPrefixContent.contains("Budget limitations") || allPrefixContent.contains("Budget"))

        // Verify NO_PREFIX blocks
        noPrefixBlocks.forEach { block ->
            assertEquals(ThinkingTagType.NO_PREFIX, block.tagType)
            assertEquals("", block.tagValue)
        }
        val rawReasoningContent = noPrefixBlocks.first().content
        assertTrue(rawReasoningContent.contains("Raw reasoning without specific formatting") ||
                  rawReasoningContent.contains("stakeholder requirements are complex"))

        // Verify we have all three types
        assertTrue(result.thinkingBlocks.count { it.tagType == ThinkingTagType.TAG } >= 2)
        assertTrue(result.thinkingBlocks.count { it.tagType == ThinkingTagType.PREFIX } >= 1)
        assertTrue(result.thinkingBlocks.count { it.tagType == ThinkingTagType.NO_PREFIX } >= 1)

        // Object converted correctly
        assertEquals("phased_implementation", result.result!!.approach)
        assertEquals("6_weeks", result.result.phase1Duration)
        assertEquals("medium", result.result.riskLevel)
        assertEquals("achieved", result.result.stakeholderAlignment)
    }

    // Data classes for proper object conversion testing
    data class QuarterlyAnalysis(
        val quarterlyTrend: String,
        val primaryConcerns: List<String>,
        val confidenceLevel: Double
    )

    data class TechnicalAnalysis(
        val primaryIssue: String,
        val rootCause: String,
        val confidence: Double,
        val recommendedActions: List<String>
    )

    data class TechnicalEvaluation(
        val primaryAction: String,
        val secondaryAction: String,
        val confidence: Double,
        val estimatedDuration: String
    )

    data class CustomerSupport(
        val priority: String,
        val assignTo: String,
        val estimatedResolution: String,
        val followUpRequired: Boolean
    )

    data class ComprehensiveAnalysis(
        val approach: String,
        val phase1Duration: String,
        val phase2Duration: String,
        val riskLevel: String,
        val stakeholderAlignment: String
    )

    // Helper method to execute thinking extraction consistently across tests
    private fun <T> executeThinkingExtraction(
        rawLlmResponse: String,
        operationName: String,
        outputClass: Class<T>
    ): ChatResponseWithThinking<T> {
        val mockOperationRunner = mockk<OperationContextPromptRunner>()
        val mockContext = mockk<com.embabel.agent.api.common.OperationContext>()
        val mockPlatform = mockk<com.embabel.agent.core.AgentPlatform>()
        val mockServices = mockk<PlatformServices>()
        val mockChatClientOps = mockk<ChatClientLlmOperations>()

        setupMockContext(mockContext, mockPlatform, mockServices, mockChatClientOps, operationName)

        // Test real extraction from raw LLM response using existing SuppressThinkingConverter
        every {
            mockChatClientOps.doTransformWithThinking<T>(
                any<List<com.embabel.chat.Message>>(),
                any<com.embabel.agent.spi.LlmInteraction>(),
                any<Class<T>>(),
                isNull()
            )
        } answers {
            // Use Spring AI BeanOutputConverter for proper structured conversion
            val beanConverter = BeanOutputConverter(outputClass)
            val converter = SuppressThinkingConverter(beanConverter)

            val thinkingBlocks = extractAllThinkingBlocks(rawLlmResponse)
            val result = converter.convert(rawLlmResponse)

            ChatResponseWithThinking(
                result = result,
                thinkingBlocks = thinkingBlocks
            )
        }

        val runner = createRunner(mockContext)

        return runner.withThinking().createObject(
            prompt = "Test prompt for $operationName",
            outputClass = outputClass
        )
    }

    // Helper methods
    private fun setupMockContext(
        mockContext: com.embabel.agent.api.common.OperationContext,
        mockPlatform: com.embabel.agent.core.AgentPlatform,
        mockServices: PlatformServices,
        mockChatClientOps: ChatClientLlmOperations,
        operationName: String
    ) {
        every { mockContext.agentPlatform() } returns mockPlatform
        every { mockContext.operation } returns mockk<com.embabel.agent.core.Operation> {
            every { name } returns operationName
        }
        every { mockContext.processContext } returns mockk<com.embabel.agent.core.ProcessContext> {
            every { agentProcess } returns mockk()
        }
        every { mockPlatform.platformServices } returns mockServices
        every { mockServices.llmOperations } returns mockChatClientOps
    }

    private fun createRunner(mockContext: com.embabel.agent.api.common.OperationContext): OperationContextPromptRunner {
        val mockLlmOptions = mockk<com.embabel.common.ai.model.LlmOptions>()
        every { mockLlmOptions.withThinking(any()) } returns mockLlmOptions

        return OperationContextPromptRunner(
            context = mockContext,
            llm = mockLlmOptions,
            toolGroups = setOf(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = null,
        )
    }
}
