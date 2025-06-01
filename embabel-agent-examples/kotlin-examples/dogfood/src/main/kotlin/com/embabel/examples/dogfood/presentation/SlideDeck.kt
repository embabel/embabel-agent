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

import com.embabel.agent.domain.library.ContentAsset
import com.embabel.common.util.loggerFor
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.time.Instant

/**
 * Projection of an individual slide within a SlideDeck
 * @param number the number of the slide, from 1
 */
data class Slide(
    val number: Int,
    val content: String,
)

/**
 * Domain object for a Markdown slide deck.
 */
data class SlideDeck(
    @get:JsonPropertyDescription("Raw content of the deck, in Markdown MARP format")
    val deck: String,
) : ContentAsset {

    override val content: String
        get() = deck

    override val timestamp: Instant = Instant.now()

    fun slideCount(): Int = slides().size

    fun slides(): List<Slide> {
        if (deck.isBlank()) return emptyList()
        // Split by separator, trim whitespace, filter out empty segments
        val parts = deck.split(Regex("^\\s*---+\\s*$", RegexOption.MULTILINE))
            .map { it.trim('\r', '\n', ' ', '\t') }
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        // If we only have one part and it's the header, return empty list
        if (parts.size == 1 && deck.trimStart().startsWith("---")) return emptyList()
        // Skip the header (first part) and map the rest to slides
        return parts.drop(1).mapIndexed { idx, content ->
            Slide(number = idx + 1, content = content)
        }
    }

    fun header(): String {
        if (deck.isBlank()) return ""
        val parts = deck.split(Regex("^\\s*---+\\s*$", RegexOption.MULTILINE))
            .map { it.trim('\r', '\n', ' ', '\t') }
            .filter { it.isNotBlank() }
        // The header is the first non-blank part after a separator
        return if (parts.isNotEmpty() && deck.trimStart().startsWith("---")) parts.first() else ""
    }

    fun withHeader(header: String): SlideDeck {
        val trimmedHeader = header.trim()
        val slides = slides()

        if (slides.isEmpty()) {
            // Only header, no slides
            return SlideDeck(
                """---
$trimmedHeader
""".trimEnd()
            ) // no trailing separator
        } else {
            val slideContents = slides.joinToString("\n---\n") { it.content }
            return SlideDeck(
                """---
$trimmedHeader
---
$slideContents
""".trimEnd()
            )
        }
    }

    fun replaceSlide(slide: Slide, newContent: String): SlideDeck {
        val slides = slides()
        if (slides.isEmpty() || slide.number < 1 || slide.number > slides.size) {
            return this // No slides to replace or invalid slide number
        }

        val updatedSlides = slides.map {
            if (it.number == slide.number) {
                Slide(it.number, newContent)
            } else {
                it
            }
        }

        val currentHeader = header()
        val slideContents = updatedSlides.joinToString("\n---\n") { it.content }

        return SlideDeck(
            """---
$currentHeader
---
$slideContents
""".trimEnd()
        )
    }

    /**
     * Expand diagrams and change text to reference them.
     * Diagrams will be in the same directory as the file
     */
    fun expandDigraphs(digraphExpander: DigraphExpander): SlideDeck {
        var result = content
        // Regex to ma\tch DOT graph blocks between ```dot and ``` markers
        val dotBlockRegex =
            Regex("""(```)?dot\s*digraph\s+(\w+)\s+(\{[\s\S;]*?\})\s*(```)?""", RegexOption.DOT_MATCHES_ALL)

        // Find all matches in the input
        val matches = dotBlockRegex.findAll(content)
        loggerFor<SlideDeck>().info("Found {} regex matches", matches.count())

        // Process each match
        var replacedDiagrams = 0
        matches.forEach { matchResult ->
            // Extract the dot string content (group 1) and the diagram name (group 2)
            val dotString = matchResult.groupValues[3]
            val diagramName = matchResult.groupValues[2]

            // Call the expandDotGraph function with the extracted values
            val diagramFile = digraphExpander.expandDiagram(
                dot = "digraph $dotString",
                fileBase = diagramName,
            )

            val imageReference = "\n![Diagram](./${diagramFile})\n"

            result = result.replace(matchResult.value, imageReference)

            ++replacedDiagrams
            loggerFor<SlideDeck>().info(
                "Replaced dot diagram {} with\n{}",
                diagramFile,
                matchResult.value,
            )
        }
        loggerFor<SlideDeck>().info("Replaced {} dot diagrams", replacedDiagrams)
        return SlideDeck(result)
    }

}
