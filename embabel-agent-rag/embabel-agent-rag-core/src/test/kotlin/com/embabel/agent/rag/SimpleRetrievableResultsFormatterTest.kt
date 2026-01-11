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

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.SimilarityResults
import com.embabel.agent.rag.service.SimpleRetrievableResultsFormatter
import com.embabel.agent.rag.service.spring.DocumentSimilarityResult
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import kotlin.test.assertEquals

class SimpleRetrievableResultsFormatterTest {

    @Test
    fun empty() {
        val results = SimilarityResults.fromList<Chunk>(emptyList())
        val output = SimpleRetrievableResultsFormatter.formatResults(results)
        assertEquals("0 results:", output)
    }

    @Test
    fun chunksOnly() {
        val results = SimilarityResults.fromList(
            listOf(
                DocumentSimilarityResult(
                    Document("foo"),
                    1.0,
                )
            )
        )
        val output = SimpleRetrievableResultsFormatter.formatResults(results)
        assertTrue(output.startsWith("1 results:"))
        assertTrue(output.contains("foo"))
    }

    @Test
    fun `chunk with url includes url header`() {
        val results = SimilarityResults.fromList(
            listOf(
                DocumentSimilarityResult(
                    Document("foo", mapOf("url" to "https://example.com/page")),
                    0.95,
                )
            )
        )
        val output = SimpleRetrievableResultsFormatter.formatResults(results)
        assertTrue(output.contains("url: https://example.com/page"))
        assertTrue(output.contains("0.95 - foo"))
    }

    @Test
    fun `chunks only with big content`() {
        val longContent = "foo ".repeat(10000).trim()
        val results = SimilarityResults.fromList(
            listOf(
                DocumentSimilarityResult(
                    Document(longContent),
                    1.0,
                )
            )
        )
        val output = SimpleRetrievableResultsFormatter.formatResults(results)
        assertTrue(output.contains(longContent))
    }

    @Test
    fun `does not expose entity embedding for SimpleNamedEntityData`() {
        val results = SimilarityResults.fromList(
            listOf(
                SimpleSimilaritySearchResult(
                    match = SimpleNamedEntityData(
                        "id",
                        name = "name1",
                        description = "descriptyThing",
                        labels = setOf("Label"),
                        properties = mapOf(
                            "embedding" to listOf(0.1, 0.2, 0.3),
                            "text" to "foo",
                            "name" to "name1",
                        )
                    ),
                    score = 1.0,
                )
            )
        )
        val output = SimpleRetrievableResultsFormatter.formatResults(results)
        assertTrue(output.contains("foo"), "Should contain properties: Have \n$output")
        assertFalse(output.contains("embedding"), "Should suppress embedding: Have \n$output")
        // Should contain name1 only once
        assertEquals(
            1, """\bname1\b""".toRegex().findAll(output).count(),
            "Should contain name once: Have \n$output"
        )
        // Should contain description only once
        assertEquals(
            1, """\bdescription\b""".toRegex().findAll(output).count(),
            "Should contain description once: Have \n$output"
        )

    }

}
