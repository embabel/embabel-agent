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
package com.embabel.agent.rag

import com.embabel.agent.rag.service.SimilarityResults
import com.embabel.agent.rag.service.support.TokenBudgetRetrievableResultsFormatter
import com.embabel.agent.rag.service.spring.DocumentSimilarityResult
import com.embabel.common.ai.model.TokenCountEstimator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document

class TokenBudgetRetrievableResultsFormatterTest {

    private val charEstimator: TokenCountEstimator<String> = TokenCountEstimator { it.length }

    @Nested
    inner class BudgetFilteringTests {

        @Test
        fun `includes results that fit within budget`() {
            val doc1 = Document("short text A")
            val doc2 = Document("short text B")
            val doc3 = Document("short text C")
            val results = SimilarityResults.fromList(
                listOf(
                    DocumentSimilarityResult(doc1, 0.99),
                    DocumentSimilarityResult(doc2, 0.90),
                    DocumentSimilarityResult(doc3, 0.80),
                )
            )

            // Measure the full output for two results, then use that as the budget.
            // With the budget matching the two-result output exactly, the third should be excluded.
            val largeFormatter = TokenBudgetRetrievableResultsFormatter(charEstimator, tokenBudget = Int.MAX_VALUE)
            val twoResults = SimilarityResults.fromList(
                listOf(
                    DocumentSimilarityResult(doc1, 0.99),
                    DocumentSimilarityResult(doc2, 0.90),
                )
            )
            val twoOutputLength = largeFormatter.formatResults(twoResults).length

            val budgetedFormatter = TokenBudgetRetrievableResultsFormatter(charEstimator, tokenBudget = twoOutputLength)
            val output = budgetedFormatter.formatResults(results)
            assertTrue(output.contains("short text A"), "Should contain result A")
            assertTrue(output.contains("short text B"), "Should contain result B")
            assertFalse(output.contains("short text C"), "Should exclude result C when over budget")
            assertTrue(output.startsWith("2 results:"), "Header should reflect 2 results")
        }

        @Test
        fun `includes all results when budget is sufficient`() {
            val results = SimilarityResults.fromList(
                listOf(
                    DocumentSimilarityResult(Document("alpha"), 0.95),
                    DocumentSimilarityResult(Document("beta"), 0.85),
                    DocumentSimilarityResult(Document("gamma"), 0.75),
                )
            )
            val formatter = TokenBudgetRetrievableResultsFormatter(charEstimator, tokenBudget = Int.MAX_VALUE)
            val output = formatter.formatResults(results)
            assertTrue(output.startsWith("3 results:"), "Should include all 3 results")
            assertTrue(output.contains("alpha"))
            assertTrue(output.contains("beta"))
            assertTrue(output.contains("gamma"))
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `returns header only when budget is zero`() {
            val results = SimilarityResults.fromList(
                listOf(
                    DocumentSimilarityResult(Document("some text"), 0.90),
                )
            )
            val formatter = TokenBudgetRetrievableResultsFormatter(charEstimator, tokenBudget = 0)
            val output = formatter.formatResults(results)
            assertEquals("0 results:", output)
        }

        @Test
        fun `empty results returns header`() {
            val results = SimilarityResults.fromList<com.embabel.agent.rag.model.Chunk>(emptyList())
            val formatter = TokenBudgetRetrievableResultsFormatter(charEstimator, tokenBudget = 1000)
            val output = formatter.formatResults(results)
            assertEquals("0 results:", output)
        }
    }

    @Nested
    inner class OrderingTests {

        @Test
        fun `preserves similarity ordering`() {
            val docHigh = Document("high similarity result")
            val docLow = Document("low similarity result")
            val results = SimilarityResults.fromList(
                listOf(
                    DocumentSimilarityResult(docHigh, 0.99),
                    DocumentSimilarityResult(docLow, 0.10),
                )
            )
            // Measure full output for just the high-similarity result, use as budget
            val measureFormatter = TokenBudgetRetrievableResultsFormatter(charEstimator, tokenBudget = Int.MAX_VALUE)
            val singleHigh = SimilarityResults.fromList(listOf(DocumentSimilarityResult(docHigh, 0.99)))
            val singleOutputLength = measureFormatter.formatResults(singleHigh).length

            val formatter = TokenBudgetRetrievableResultsFormatter(charEstimator, tokenBudget = singleOutputLength)
            val output = formatter.formatResults(results)
            assertTrue(output.startsWith("1 results:"), "Should include exactly 1 result")
            assertTrue(output.contains("high similarity result"), "Should contain highest-similarity result")
            assertFalse(output.contains("low similarity result"), "Should exclude lower-similarity result")
        }
    }
}
