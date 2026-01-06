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
package com.embabel.agent.api.common.reference

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LlmReferenceProvidersTest {

    @Test
    fun `parseLlmReferences should parse valid YAML file with multiple references`() {
        val references = LlmReferenceProviders.fromYmlFile("classpath:/test-references.yml")

        assertEquals(3, references.size, "Should parse 3 references from test file")

        // Check first reference (LiteralText)
        val firstRef = references[0]
        assertEquals("API Guide", firstRef.name)
        assertEquals("Quick reference for the payment API", firstRef.description)
        assertEquals("Use this when you need to understand payment processing endpoints", firstRef.notes())

        // Check second reference (WebPage)
        val secondRef = references[1]
        assertEquals("Payment API Docs", secondRef.name)
        assertEquals("Full API documentation for payment processing", secondRef.description)
        assertTrue(secondRef is WebPage)
        assertEquals("https://example.com/api-docs", (secondRef as WebPage).url)

        // Check third reference (LiteralText)
        val thirdRef = references[2]
        assertEquals("Security Policy", thirdRef.name)
        assertEquals("Security guidelines for API usage", thirdRef.description)
        assertEquals("Always check these guidelines before making API calls", thirdRef.notes())
    }

    @Test
    fun `parseLlmReferences should handle empty YAML file`() {
        val references = LlmReferenceProviders.fromYmlFile("classpath:/empty-references.yml")

        assertEquals(0, references.size, "Should return empty list for empty YAML")
    }

    @Test
    fun `parseLlmReferences should throw exception for non-existent resource`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            LlmReferenceProviders.fromYmlFile("classpath:/does-not-exist.yml")
        }

        assertTrue(
            exception.message?.contains("Resource not found") == true,
            "Exception should mention resource not found"
        )
    }

    @Test
    fun `parsed references should have valid tool prefixes`() {
        val references = LlmReferenceProviders.fromYmlFile("classpath:/test-references.yml")

        references.forEach { ref ->
            val toolPrefix = ref.toolPrefix()
            assertNotNull(toolPrefix, "Tool prefix should not be null")
            assertTrue(toolPrefix.isNotBlank(), "Tool prefix should not be blank")
            // Tool prefix should be lowercase and contain only alphanumeric, spaces, and underscores
            assertTrue(
                toolPrefix.matches(Regex("[a-z0-9_ ]+")),
                "Tool prefix '$toolPrefix' should be lowercase alphanumeric with spaces and underscores"
            )
        }
    }

    @Test
    fun `parsed references should have valid contributions`() {
        val references = LlmReferenceProviders.fromYmlFile("classpath:/test-references.yml")

        references.forEach { ref ->
            val contribution = ref.contribution()
            assertNotNull(contribution, "Contribution should not be null")
            assertTrue(contribution.contains(ref.name), "Contribution should contain reference name")
            assertTrue(contribution.contains(ref.description), "Contribution should contain description")
        }
    }

    @Test
    fun `LiteralText reference should implement LlmReferenceProvider correctly`() {
        val literalText = LiteralText(
            name = "Test Reference",
            description = "Test description",
            notes = "Test notes"
        )

        val reference = literalText.reference()

        assertSame(literalText, reference, "LiteralText should return itself as reference")
        assertEquals("Test Reference", reference.name)
        assertEquals("Test description", reference.description)
        assertEquals("Test notes", reference.notes())
    }

    @Test
    fun `WebPage reference should implement LlmReferenceProvider correctly`() {
        val webPage = WebPage(
            url = "https://example.com",
            name = "Example",
            description = "Example page"
        )

        val reference = webPage.reference()

        assertSame(webPage, reference, "WebPage should return itself as reference")
        assertEquals("Example", reference.name)
        assertEquals("Example page", reference.description)
        assertEquals("https://example.com", webPage.url)
    }

    @Test
    fun `parseLlmReferences should preserve reference types`() {
        val references = LlmReferenceProviders.fromYmlFile("classpath:/test-references.yml")

        val literalTextRefs = references.filterIsInstance<LiteralText>()
        val webPageRefs = references.filterIsInstance<WebPage>()

        assertEquals(2, literalTextRefs.size, "Should have 2 LiteralText references")
        assertEquals(1, webPageRefs.size, "Should have 1 WebPage reference")
    }
}
