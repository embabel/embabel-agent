package com.embabel.agent.rag

import com.embabel.agent.rag.model.SimpleEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.RagRequest
import com.embabel.agent.rag.service.RagResponse
import com.embabel.agent.rag.service.RagService
import com.embabel.agent.rag.service.SimpleRagResponseFormatter
import com.embabel.agent.rag.service.support.DocumentSimilarityResult
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import kotlin.test.assertEquals

class SimpleRagRagResponseFormatterTest {

    @Test
    fun empty() {
        val rr = RagService.empty()
        val results = rr.search(RagRequest("any query at all"))
        val output = SimpleRagResponseFormatter.format(results)
        assertEquals(SimpleRagResponseFormatter.NO_RESULTS_FOUND, output)
    }

    @Test
    fun chunksOnly() {
        val results = RagResponse(
            request = RagRequest("any query at all"),
            service = "test",
            results = listOf(
                DocumentSimilarityResult(
                    Document("foo"),
                    1.0,
                )
            )
        )
        val output = SimpleRagResponseFormatter.format(results)
        assertTrue(output.contains("foo"))
    }

    @Test
    fun `chunks only with big content`() {
        val longContent = "foo ".repeat(10000).trim()
        val results = RagResponse(
            request = RagRequest("any query at all"),
            service = "test",
            results = listOf(
                DocumentSimilarityResult(
                    Document(longContent),
                    1.0,
                )
            )
        )
        val output = SimpleRagResponseFormatter.format(results)
        assertTrue(output.contains(longContent))
    }

    @Test
    fun `does not expose entity embedding for SimpleEntityData`() {
        val results = RagResponse(
            request = RagRequest("any query at all"),
            service = "test",
            results = listOf(
                SimpleSimilaritySearchResult(
                    match = SimpleEntityData(
                        "id",
                        labels = setOf("Label"),
                        properties = mapOf(
                            "embedding" to listOf(0.1, 0.2, 0.3),
                            "text" to "foo"
                        )
                    ),
                    score = 1.0,
                )
            )
        )
        val output = SimpleRagResponseFormatter.format(results)
        assertTrue(output.contains("foo"))
        assertFalse(output.contains("embedding"), "Should suppress embedding, have \n$output")
    }

    @Test
    fun `does not expose entity embedding for SimpleNamedEntityData`() {
        val results = RagResponse(
            request = RagRequest("any query at all"),
            service = "test",
            results = listOf(
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
        val output = SimpleRagResponseFormatter.format(results)
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