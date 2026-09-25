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
package com.embabel.common.ai.classification

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClassificationTest {
    private val provenance = ModelProvenance("model", "provider")

    private enum class Animal {
        DOG, CAT, RABBIT;
        override fun toString() = "not an ID"
    }

    @Nested
    inner class Categories {
        @Test
        fun `request string excludes input and category descriptions`() {
            val input = "sensitive-input-sentinel"
            val description = "sensitive-category-description-sentinel"
            val request = ClassificationRequest(input, listOf(Category("dog", description)))
            assertFalse(request.toString().contains(input))
            assertFalse(request.toString().contains(description))
        }

        @Test
        fun `reject malformed category domains`() {
            assertThrows(IllegalArgumentException::class.java) { Category(" ", "description") }
            assertThrows(IllegalArgumentException::class.java) { ClassificationRequest("input", emptyList()) }
            assertThrows(IllegalArgumentException::class.java) {
                ClassificationRequest("input", listOf(Category("dog", "first"), Category("dog", "second")))
            }
            assertThrows(IllegalArgumentException::class.java) { CategoryMapping<Animal>(emptyMap()) }
            assertThrows(IllegalArgumentException::class.java) {
                CategoryMapping(mapOf(Category("dog", "first") to Animal.DOG, Category("dog", "second") to Animal.CAT))
            }
        }

        @Test
        fun `collections are copied and cannot be mutated through exposed views`() {
            val categories = mutableListOf(Category("dog", "Canine"))
            val request = ClassificationRequest("input", categories)
            categories.clear()
            assertEquals(1, request.categories.size)
            assertThrows(UnsupportedOperationException::class.java) {
                (request.categories as MutableList<Category>).clear()
            }
            val entries = mutableMapOf(Category("dog", "Canine") to Animal.DOG)
            val mapping = CategoryMapping(entries)
            entries.clear()
            assertEquals(1, mapping.categories.size)
            assertThrows(UnsupportedOperationException::class.java) {
                (mapping.categories as MutableList<Category>).clear()
            }
            val selected = mapping.request("woof").selected("dog", provenance)
            assertEquals(Animal.DOG, (mapping.map(selected) as MappedClassificationResult.Selected).value)
        }

        @Test
        fun `enum mapping uses names and shares category definitions with requests`() {
            val mapping = CategoryMapping.fromEnum(Animal::class.java) { "Description of ${it.name}" }
            assertEquals(listOf("DOG", "CAT", "RABBIT"), mapping.categories.map { it.id })
            assertEquals(mapping.categories, mapping.request("woof").categories)
            val selected = mapping.request("woof").selected("DOG", provenance, 0.8)
            val mapped = mapping.map(selected) as MappedClassificationResult.Selected
            assertEquals(Animal.DOG, mapped.value)
            assertSame(selected, mapped.selection)
        }
    }

    @Nested
    inner class Outcomes {
        @Test
        fun `unknown provider id fails at request and mapping boundaries`() {
            val mapping = CategoryMapping(mapOf(Category("dog", "Canine") to Animal.DOG))
            val request = mapping.request("woof")
            val invalid = ClassificationResult.Selected("cat", provenance)
            assertThrows(IllegalArgumentException::class.java) { request.selected("cat", provenance) }
            assertThrows(IllegalArgumentException::class.java) { request.validate(invalid) }
            assertThrows(IllegalArgumentException::class.java) { mapping.map(invalid) }
        }

        @Test
        fun `confidence must be provider supplied finite probability`() {
            for (score in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1)) {
                assertThrows(IllegalArgumentException::class.java) {
                    ClassificationResult.Selected("dog", provenance, score)
                }
            }
            assertNull(ClassificationResult.Selected("dog", provenance).confidence)
            for (score in listOf(0.0, 1.0)) {
                assertEquals(score, ClassificationResult.Selected("dog", provenance, score).confidence)
            }
            assertThrows(IllegalArgumentException::class.java) { ClassificationResult.Selected(" ", provenance) }
        }

        @Test
        fun `mapping retains distinct nonselection evidence unchanged`() {
            val mapping = CategoryMapping(mapOf(Category("dog", "Canine") to Animal.DOG))
            val outcomes = listOf(
                ClassificationResult.NoMatch(provenance),
                ClassificationResult.Inconclusive(provenance),
                ClassificationResult.Failure(FailureReason.UNAVAILABLE),
            )
            outcomes.forEach {
                assertSame(it, mapping.map(it))
                assertSame(it, mapping.request("input").validate(it))
            }
        }
    }
}
