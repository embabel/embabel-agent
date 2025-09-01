package com.embabel.agent.rag

import com.embabel.agent.rag.support.DocumentSimilarityResult
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

}