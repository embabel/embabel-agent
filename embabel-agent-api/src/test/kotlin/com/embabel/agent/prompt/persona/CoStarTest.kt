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
package com.embabel.agent.prompt.persona

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoStarTest {

    @Test
    fun `should create CoStar with all required fields`() {
        val coStar = CoStar(
            context = "Software development team",
            objective = "Write clean code",
            style = "Professional",
            tone = "Helpful",
            audience = "Developers"
        )

        assertEquals("Software development team", coStar.context)
        assertEquals("Write clean code", coStar.objective)
        assertEquals("Professional", coStar.style)
        assertEquals("Helpful", coStar.tone)
        assertEquals("Developers", coStar.audience)
        assertEquals("Markdown", coStar.response) // default value
    }

    @Test
    fun `should create CoStar with custom response format`() {
        val coStar = CoStar(
            context = "Technical documentation",
            objective = "Explain complex concepts",
            style = "Clear and concise",
            tone = "Educational",
            audience = "Beginners",
            response = "JSON"
        )

        assertEquals("JSON", coStar.response)
    }

    @Test
    fun `should create CoStar with custom separator`() {
        val coStar = CoStar(
            context = "Test context",
            objective = "Test objective",
            style = "Test style",
            tone = "Test tone",
            audience = "Test audience",
            separator = "---"
        )

        val contribution = coStar.contribution()
        assertTrue(contribution.contains("---"))
        assertFalse(contribution.contains("#".repeat(12)))
    }

    @Test
    fun `should generate correct prompt contribution with default separator`() {
        val coStar = CoStar(
            context = "E-commerce platform",
            objective = "Improve user experience",
            style = "Data-driven",
            tone = "Analytical",
            audience = "Product managers"
        )

        val contribution = coStar.contribution()
        val separator = "#".repeat(12)

        assertTrue(contribution.contains("# CONTEXT #"))
        assertTrue(contribution.contains("E-commerce platform"))
        assertTrue(contribution.contains("# OBJECTIVE #"))
        assertTrue(contribution.contains("Improve user experience"))
        assertTrue(contribution.contains("# STYLE #"))
        assertTrue(contribution.contains("Data-driven"))
        assertTrue(contribution.contains("# TONE #"))
        assertTrue(contribution.contains("Analytical"))
        assertTrue(contribution.contains("# AUDIENCE #"))
        assertTrue(contribution.contains("Product managers"))
        assertTrue(contribution.contains("# RESPONSE FORMAT #"))
        assertTrue(contribution.contains("Markdown"))
        assertTrue(contribution.contains(separator))
    }

    @Test
    fun `should generate correct prompt contribution with custom response format`() {
        val coStar = CoStar(
            context = "API documentation",
            objective = "Document endpoints",
            style = "Technical",
            tone = "Precise",
            audience = "API consumers",
            response = "OpenAPI Specification"
        )

        val contribution = coStar.contribution()
        assertTrue(contribution.contains("# RESPONSE FORMAT #"))
        assertTrue(contribution.contains("OpenAPI Specification"))
        assertFalse(contribution.contains("Markdown"))
    }

    @Test
    fun `should handle empty strings in all fields`() {
        val coStar = CoStar(
            context = "",
            objective = "",
            style = "",
            tone = "",
            audience = "",
            response = ""
        )

        assertEquals("", coStar.context)
        assertEquals("", coStar.objective)
        assertEquals("", coStar.style)
        assertEquals("", coStar.tone)
        assertEquals("", coStar.audience)
        assertEquals("", coStar.response)

        val contribution = coStar.contribution()
        assertTrue(contribution.contains("# CONTEXT #"))
        assertTrue(contribution.contains("# OBJECTIVE #"))
        assertTrue(contribution.contains("# STYLE #"))
        assertTrue(contribution.contains("# TONE #"))
        assertTrue(contribution.contains("# AUDIENCE #"))
        assertTrue(contribution.contains("# RESPONSE FORMAT #"))
    }

    @Test
    fun `should handle special characters and newlines`() {
        val coStar = CoStar(
            context = "Multi-line\ncontext with & special chars",
            objective = "Handle edge cases @ runtime",
            style = "Defensive & robust",
            tone = "Careful, thorough",
            audience = "QA Engineers & Developers"
        )

        assertEquals("Multi-line\ncontext with & special chars", coStar.context)
        assertEquals("Handle edge cases @ runtime", coStar.objective)
        assertEquals("Defensive & robust", coStar.style)
        assertEquals("Careful, thorough", coStar.tone)
        assertEquals("QA Engineers & Developers", coStar.audience)
    }

    @Test
    fun `should handle multiline content properly in contribution`() {
        val coStar = CoStar(
            context = """
                Large enterprise application
                with complex business rules
                and multiple integrations
            """.trimIndent(),
            objective = """
                Refactor legacy code
                Improve maintainability
                Add comprehensive tests
            """.trimIndent(),
            style = "Incremental and methodical",
            tone = "Cautious but determined",
            audience = "Senior developers and architects"
        )

        val contribution = coStar.contribution()
        assertTrue(contribution.contains("Large enterprise application"))
        assertTrue(contribution.contains("with complex business rules"))
        assertTrue(contribution.contains("Refactor legacy code"))
        assertTrue(contribution.contains("Improve maintainability"))
    }

    @Test
    fun `should maintain equality for same content`() {
        val coStar1 = CoStar(
            context = "Same context",
            objective = "Same objective",
            style = "Same style",
            tone = "Same tone",
            audience = "Same audience"
        )
        val coStar2 = CoStar(
            context = "Same context",
            objective = "Same objective",
            style = "Same style",
            tone = "Same tone",
            audience = "Same audience"
        )

        assertEquals(coStar1, coStar2)
        assertEquals(coStar1.hashCode(), coStar2.hashCode())
    }

    @Test
    fun `should not be equal for different content`() {
        val coStar1 = CoStar(
            context = "Context 1",
            objective = "Same objective",
            style = "Same style",
            tone = "Same tone",
            audience = "Same audience"
        )
        val coStar2 = CoStar(
            context = "Context 2",
            objective = "Same objective",
            style = "Same style",
            tone = "Same tone",
            audience = "Same audience"
        )

        assertNotEquals(coStar1, coStar2)
    }

    @Test
    fun `should have proper toString representation`() {
        val coStar = CoStar(
            context = "Test context",
            objective = "Test objective",
            style = "Test style",
            tone = "Test tone",
            audience = "Test audience"
        )

        val toString = coStar.toString()
        assertTrue(toString.contains("Test context"))
        assertTrue(toString.contains("Test objective"))
        assertTrue(toString.contains("Test style"))
        assertTrue(toString.contains("Test tone"))
        assertTrue(toString.contains("Test audience"))
    }

    @Test
    fun `should support copy functionality`() {
        val original = CoStar(
            context = "Original context",
            objective = "Original objective",
            style = "Original style",
            tone = "Original tone",
            audience = "Original audience"
        )

        val copied = original.copy(context = "New context")

        assertEquals("New context", copied.context)
        assertEquals("Original objective", copied.objective)
        assertEquals("Original style", copied.style)
        assertEquals("Original tone", copied.tone)
        assertEquals("Original audience", copied.audience)
        assertNotEquals(original, copied)
    }

    @Test
    fun `should handle very long text content`() {
        val longText = "A".repeat(1000)
        val coStar = CoStar(
            context = longText,
            objective = longText,
            style = longText,
            tone = longText,
            audience = longText
        )

        assertEquals(longText, coStar.context)
        assertEquals(longText, coStar.objective)
        assertEquals(longText, coStar.style)
        assertEquals(longText, coStar.tone)
        assertEquals(longText, coStar.audience)

        val contribution = coStar.contribution()
        assertTrue(contribution.contains(longText))
    }

    @Test
    fun `should use default response format when not specified`() {
        val coStar = CoStar(
            context = "Test",
            objective = "Test",
            style = "Test",
            tone = "Test",
            audience = "Test"
        )

        assertEquals("Markdown", coStar.response)
        val contribution = coStar.contribution()
        assertTrue(contribution.contains("Markdown"))
    }

    @Test
    fun `should preserve separator in contribution`() {
        val customSeparator = "===="
        val coStar = CoStar(
            context = "Test",
            objective = "Test",
            style = "Test",
            tone = "Test",
            audience = "Test",
            separator = customSeparator
        )

        val contribution = coStar.contribution()
        val separatorCount = contribution.split(customSeparator).size - 1
        assertEquals(6, separatorCount) // Should appear 6 times (after each section except the last)
    }
}
