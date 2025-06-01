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
package com.embabel.examples.dogfood.presentation

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

val PARIS = """
            ---
            marp: true
            theme: default
            paginate: false
            class: invert
            size: 16:9
            style: |
              img {background-color: transparent!important;}
              a:hover, a:active, a:focus {text-decoration: none;}
              header a {color: #ffffff !important; font-size: 30px;}
              footer {color: #148ec8;}
            footer: "(c) Embabel 2025"
            ---

            # Paris 9th Arrondissement

            ## A 3-Day Itinerary

            ---

            # Introduction

            Welcome to the heart of Paris' 9th arrondissement.
            A blend of culture, cuisine, shopping, and history awaits.

            ---

            # Why the 9th Arrondissement?

            - Vibrant cultural scene
            - Historic landmarks
            - Authentic Parisian experiences
        """.trimIndent()

val PARIS_WITH_DIAGRAM = PARIS + """
    ---
    ```dot
    digraph PresentationMaker {
      PresentationRequest -> identifyResearchTopics -> researchTopics -> createDeck -> saveDeck -> convertToSlides
      identifyResearchTopics -> ResearchTopics
      researchTopics -> ResearchComplete
      createDeck -> Deck
      saveDeck -> FileSystem
      convertToSlides -> HTMLSlides
      PresentationMakerProperties -> PresentationMaker
      SlideFormatter -> convertToSlides
      FilePersister -> saveDeck
    }
    ```

""".trimIndent()

val PARIS_WITH_DIAGRAM_2 = PARIS + """

    ---
    dot digraph PresentationMaker {
      identifyResearchTopics -> researchTopics -> createDeck -> expandDigraphs -> loadWithDigraphs -> addIllustrations -> convertToSlides;
    }
""".trimIndent()

class SlideDeckTest {

    @Test
    fun `empty deck`() {
        val deck = SlideDeck("")
        assertEquals(0, deck.slideCount())
        assertEquals("", deck.header())
    }

    @Test
    fun `simple deck slideCount`() {
        val deck = SlideDeck(
            PARIS
        )
        assertEquals(3, deck.slideCount())
    }

    @Test
    fun `slide extraction`() {
        val deck = SlideDeck(
            PARIS
        )
        val s2 = deck.slides()[1]
        assertEquals(
            """

            # Introduction

            Welcome to the heart of Paris' 9th arrondissement.
            A blend of culture, cuisine, shopping, and history awaits.

            """.trimIndent().trim(),
            s2.content.trim(),
        )
    }

    @Test
    fun `header extraction`() {
        val deck = SlideDeck(
            PARIS
        )
        assertEquals(
            """
            marp: true
            theme: default
            paginate: false
            class: invert
            size: 16:9
            style: |
              img {background-color: transparent!important;}
              a:hover, a:active, a:focus {text-decoration: none;}
              header a {color: #ffffff !important; font-size: 30px;}
              footer {color: #148ec8;}
            footer: "(c) Embabel 2025"
        """.trimIndent().trim(), deck.header().trim()
        )
    }

    @Test
    fun `update with same header`() {
        val slideDeck = SlideDeck(PARIS)
        val updatedDeck = slideDeck.withHeader(
            slideDeck.header(),
        )
        assertEquals(
            removeWhiteSpace(slideDeck.deck.trim()),
            removeWhiteSpace(updatedDeck.deck.trim()),
        )
    }

    @Test
    fun `replace slide`() {
        val slideDeck = SlideDeck(PARIS)
        val slide = slideDeck.slides()[0]
        val newContent = "# Updated Title\n\nThis is a new slide content."
        val updatedDeck = slideDeck.replaceSlide(slide, newContent)

        assertEquals(3, updatedDeck.slideCount())
        assertEquals(newContent, updatedDeck.slides()[0].content)
        assertEquals(slideDeck.slides()[1].content, updatedDeck.slides()[1].content)
    }

    @Test
    fun `slide numbers are correct`() {
        val slideDeck = SlideDeck(PARIS)
        val slides = slideDeck.slides()

        assertEquals(1, slides[0].number)
        assertEquals(2, slides[1].number)
    }

    @Test
    fun `withHeader creates proper deck structure`() {
        val newHeader = "title: New Presentation"
        val emptyDeck = SlideDeck("")
        val withHeaderDeck = emptyDeck.withHeader(newHeader)

        assertEquals(newHeader, withHeaderDeck.header())
        assertEquals(0, withHeaderDeck.slideCount())
    }

    @Test
    fun `replace slide with invalid slide number`() {
        val slideDeck = SlideDeck(PARIS)
        val invalidSlide = Slide(999, "Invalid content")
        val updatedDeck = slideDeck.replaceSlide(invalidSlide, "New content")

        // Should return the original deck unchanged
        assertEquals(slideDeck.content, updatedDeck.content)
    }

    @Test
    fun `replace slide in empty deck`() {
        val emptyDeck = SlideDeck("")
        val invalidSlide = Slide(1, "")
        val updatedDeck = emptyDeck.replaceSlide(invalidSlide, "New content")

        // Should return the original empty deck
        assertEquals("", updatedDeck.content)
    }

    @Test
    fun `slide numbering is sequential`() {
        val multiSlideDeck = SlideDeck(
            """
            ---
            title: Test Deck
            ---
            # Slide 1
            ---
            # Slide 2
            ---
            # Slide 3
            ---
            # Slide 4
        """.trimIndent()
        )

        val slides = multiSlideDeck.slides()
        assertEquals(4, slides.size)
        for (i in 0 until slides.size) {
            assertEquals(i + 1, slides[i].number)
        }
    }

    @Test
    fun `header manipulation preserves slide content`() {
        val originalDeck = SlideDeck(PARIS)
        val newHeader = "title: Updated Presentation"
        val updatedDeck = originalDeck.withHeader(newHeader)

        assertEquals(newHeader, updatedDeck.header())
        assertEquals(originalDeck.slideCount(), updatedDeck.slideCount())

        // Verify slide content is preserved
        for (i in 0 until originalDeck.slideCount()) {
            assertEquals(
                originalDeck.slides()[i].content.trim(),
                updatedDeck.slides()[i].content.trim()
            )
        }
    }

    @Test
    fun `slide extraction with extra separators and blank lines`() {
        val deckStr = """
            ---
            title: Deck With Blanks
            ---

            # Slide 1

            ---

            # Slide 2

            ---

            # Slide 3
        """.trimIndent()
        val deck = SlideDeck(deckStr)
        assertEquals(3, deck.slideCount())
        assertEquals("# Slide 1", deck.slides()[0].content.trim().lines().first())
        assertEquals("# Slide 2", deck.slides()[1].content.trim().lines().first())
        assertEquals("# Slide 3", deck.slides()[2].content.trim().lines().first())
    }

    @Test
    fun `replace first and last slide edge cases`() {
        val baseDeck = SlideDeck(
            """
            ---
            title: Edge Deck
            ---
            Slide 1 content
            ---
            Slide 2 content
            ---
            Slide 3 content
        """.trimIndent()
        )
        val replacedFirst = baseDeck.replaceSlide(baseDeck.slides().first(), "First replaced")
        assertEquals("First replaced", replacedFirst.slides().first().content)
        val replacedLast = baseDeck.replaceSlide(baseDeck.slides().last(), "Last replaced")
        assertEquals("Last replaced", replacedLast.slides().last().content)
    }

    @Test
    fun `withHeader with slides and no trailing separators`() {
        val deck = SlideDeck(
            """
            ---
            original header
            ---
            Slide 1
            ---
            Slide 2
        """.trimIndent()
        )
        val newDeck = deck.withHeader("new header")
        assertEquals("new header", newDeck.header())
        assertEquals(2, newDeck.slideCount())
        assertEquals("Slide 1", newDeck.slides()[0].content.trim())
        assertEquals("Slide 2", newDeck.slides()[1].content.trim())
    }

    @Test
    fun `deck with only header and extra blank lines`() {
        val deck = SlideDeck(
            """
            ---
            header only


        """.trimIndent()
        )
        assertEquals("header only", deck.header())
        assertEquals(0, deck.slideCount())
    }

    @Nested
    inner class ExpandDiagrams {

        @Test
        fun `no diagrams to expand`() {
            val paris = SlideDeck(PARIS)
            val mockDigraphExpander = mockk<DigraphExpander>()
            val paris2 = paris.expandDigraphs(mockDigraphExpander)
            assertEquals(PARIS, paris2.content, "Expanding no diagrams should have made no change")
        }

        @Test
        fun `expand diagram with backticks`() {
            val paris = SlideDeck(PARIS_WITH_DIAGRAM)
            val p2 = expandWithDiagram(paris)
            assertFalse(p2.content.contains("`"), "Should have no backticks:\n${paris.content}")
        }

        @Test
        fun `expand diagram without backticks`() {
            val paris = SlideDeck(PARIS_WITH_DIAGRAM.replace("```", ""))
            val p2 = expandWithDiagram(paris)
            assertFalse(p2.content.contains("`"), "Should have no backticks")

        }

        @Test
        fun `expand minimal diagram`() {
            val paris = SlideDeck(PARIS_WITH_DIAGRAM_2)
            expandWithDiagram(paris)
        }

        private fun expandWithDiagram(deck: SlideDeck): SlideDeck {
            val mockDigraphExpander = mockk<DigraphExpander>()
            every { mockDigraphExpander.expandDiagram("PresentationMaker", any()) } returns "PresentationMaker.svg"
            val expanded = deck.expandDigraphs(mockDigraphExpander)
            assertFalse(expanded.content.contains("dot"), "Digraph should have been removed\n${expanded.content}")
            assertNotEquals(deck.content, expanded.content, "Diagram should have been expanded")
            assertTrue(expanded.content.contains("![Diagram](./PresentationMaker.svg)"))
            assertEquals(deck.slideCount(), expanded.slideCount(), "Should have the same slide count")
            return expanded
        }
    }
}

private fun removeWhiteSpace(s: String): String {
    return s.replace("\\s+".toRegex(), " ")
        .replace("\n", "")
        .replace("\r", "")
        .replace("\t", "")
        .trim()
}
