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
package com.embabel.common.ai.decision

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The golden ids below were computed outside this code base, with Python's hashlib over the
 * canonical JSON text written out by hand in each comment. A change to any of them breaks
 * every id already stored or sent by another writer.
 */
class DefinitionIdsTest {

    private val choiceEntries = listOf(
        "billing" to "Payments and invoices",
        "technical" to "Bugs and outages",
    )
    private val ratingEntries = listOf("Calm" to "Calm", "Annoyed" to "Annoyed", "Furious" to "Furious")

    private fun proposition() =
        DefinitionIds.question("proposition", "is_urgent", "The customer needs a reply today.", emptyList())

    private fun choice(entries: List<Pair<String, String>> = choiceEntries) =
        DefinitionIds.question("choice", "department", "Which team should handle the ticket?", entries)

    private fun rating() =
        DefinitionIds.question("rating", "frustration", "How frustrated is the customer?", ratingEntries)

    @Test
    fun `proposition id matches the golden value`() {
        // ["proposition","is_urgent","The customer needs a reply today.",[]]
        assertEquals("d1-81hzfR8Ooim5eJZok_AQMmXVY5l_p2bImz3be95T9z8", proposition())
    }

    @Test
    fun `choice id matches the golden value`() {
        // ["choice","department","Which team should handle the ticket?",[["billing","Payments and invoices"],["technical","Bugs and outages"]]]
        assertEquals("d1-VCw7xOoFzXwzwtRyNDmDI6fcN5Bw4wEOenUKfvrseYI", choice())
    }

    @Test
    fun `rating id matches the golden value`() {
        // ["rating","frustration","How frustrated is the customer?",[["Calm","Calm"],["Annoyed","Annoyed"],["Furious","Furious"]]]
        assertEquals("d1-XN6vR-XG4Qnj5iD3sivLES5KyeI8Kqao08T1D-JkkeU", rating())
    }

    @Test
    fun `non ASCII text is hashed as raw UTF-8 with supplementary characters in four bytes`() {
        // ["choice","getränk","Welches Getränk passt zu 寿司? 😀",[["thé","Thé vert, très chaud"],["café","Café noir"]]]
        val id = DefinitionIds.question(
            "choice",
            "getränk",
            "Welches Getränk passt zu 寿司? 😀",
            listOf("thé" to "Thé vert, très chaud", "café" to "Café noir"),
        )
        assertEquals("d1-03HXfEQrMvhBexyMTmLm0aJQEJdltBqcbEhO4XWz3yQ", id)
    }

    @Test
    fun `quotes and a newline in a description use the short JSON escapes`() {
        // ["rating","tone","Rate the tone.",[["calm","Says \"fine\"\nand moves on"],["angry","Writes \"NOW\" in capitals"]]]
        val id = DefinitionIds.question(
            "rating",
            "tone",
            "Rate the tone.",
            listOf("calm" to "Says \"fine\"\nand moves on", "angry" to "Writes \"NOW\" in capitals"),
        )
        assertEquals("d1-FmvzzdKf19KMVGKNja87X8rGHPrOQsRPr2ZfWgmiLAA", id)
    }

    @Test
    fun `slash stays literal while backslash, other controls and lone surrogates are escaped`() {
        // ["choice","path","a/b \\ c\td\u001F",[["x","\uD800 lone"]]]
        val id = DefinitionIds.question("choice", "path", "a/b \\ c\td\u001F", listOf("x" to "\uD800 lone"))
        assertEquals("d1-BzMv9fplC4ep8sQNrtFT5pRWy5XXeqQpDqdIJeioJuM", id)
    }

    @Test
    fun `spec id matches the golden value`() {
        // ["d1-81hzfR8Ooim5eJZok_AQMmXVY5l_p2bImz3be95T9z8","d1-VCw7xOoFzXwzwtRyNDmDI6fcN5Bw4wEOenUKfvrseYI","d1-XN6vR-XG4Qnj5iD3sivLES5KyeI8Kqao08T1D-JkkeU"]
        assertEquals(
            "s1-D665SD0CyRgb614QrVwuKjopknltvHzDqPb05S8RyCE",
            DefinitionIds.spec(listOf(proposition(), choice(), rating())),
        )
    }

    @Test
    fun `ids are a version prefix and 43 base64url characters`() {
        val shape = Regex("[ds]1-[A-Za-z0-9_-]{43}")
        for (id in listOf(proposition(), choice(), rating(), DefinitionIds.spec(listOf(choice())))) {
            assertTrue(shape.matches(id), id)
        }
        assertTrue(proposition().startsWith("d1-"))
        assertTrue(DefinitionIds.spec(listOf(proposition())).startsWith("s1-"))
    }

    @Test
    fun `equal inputs give equal ids`() {
        assertEquals(choice(), choice(choiceEntries.map { (id, description) -> String(id.toCharArray()) to description }))
        assertEquals(proposition(), proposition())
        assertEquals(
            DefinitionIds.spec(listOf(proposition(), choice())),
            DefinitionIds.spec(mutableListOf(proposition(), choice())),
        )
    }

    @Test
    fun `changing any question field changes the id`() {
        val base = choice()
        val variants = listOf(
            DefinitionIds.question("rating", "department", "Which team should handle the ticket?", choiceEntries),
            DefinitionIds.question("choice", "team", "Which team should handle the ticket?", choiceEntries),
            DefinitionIds.question("choice", "department", "Which team handles the ticket?", choiceEntries),
            choice(listOf("billing" to "Payments and invoices", "tech" to "Bugs and outages")),
            choice(listOf("billing" to "Payments and invoices", "technical" to "Bugs, outages")),
            choice(choiceEntries.reversed()),
            choice(choiceEntries.take(1)),
            choice(choiceEntries + ("sales" to "New business")),
        )
        for (variant in variants) assertNotEquals(base, variant)
        assertEquals(variants.size, variants.toSet().size)
    }

    @Test
    fun `text moving between adjacent fields changes the id`() {
        assertNotEquals(
            DefinitionIds.question("choice", "ab", "c", listOf("x" to "y")),
            DefinitionIds.question("choice", "a", "bc", listOf("x" to "y")),
        )
        assertNotEquals(
            DefinitionIds.question("choice", "n", "i", listOf("x" to "y z")),
            DefinitionIds.question("choice", "n", "i", listOf("x y" to "z")),
        )
    }

    @Test
    fun `changing the question order or membership changes the spec id`() {
        val base = DefinitionIds.spec(listOf(proposition(), choice(), rating()))
        assertNotEquals(base, DefinitionIds.spec(listOf(choice(), proposition(), rating())))
        assertNotEquals(base, DefinitionIds.spec(listOf(proposition(), choice())))
        assertNotEquals(base, DefinitionIds.spec(listOf(proposition(), choice(), rating(), rating())))
    }

    @Test
    fun `kind must be a lower-case wire name and a proposition has no entries`() {
        for (kind in listOf("PROPOSITION", "Choice", "", "scale")) {
            assertThrows(IllegalArgumentException::class.java) {
                DefinitionIds.question(kind, "n", "i", emptyList())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DefinitionIds.question("proposition", "n", "i", listOf("x" to "y"))
        }
    }
}
