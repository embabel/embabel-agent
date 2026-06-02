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
package com.embabel.agent.rag.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EnhancementTypeTest {

    @Test
    fun `should have COMPRESSION value`() {
        // Act
        val type = EnhancementType.COMPRESSION

        // Assert
        assertEquals("COMPRESSION", type.name)
    }

    @Test
    fun `should have RERANKING value`() {
        // Act
        val type = EnhancementType.RERANKING

        // Assert
        assertEquals("RERANKING", type.name)
    }

    @Test
    fun `should have DEDUPLICATION value`() {
        // Act
        val type = EnhancementType.DEDUPLICATION

        // Assert
        assertEquals("DEDUPLICATION", type.name)
    }

    @Test
    fun `should have ENTITY_EXTRACTION value`() {
        // Act
        val type = EnhancementType.ENTITY_EXTRACTION

        // Assert
        assertEquals("ENTITY_EXTRACTION", type.name)
    }

    @Test
    fun `should have FACT_CHECKING value`() {
        // Act
        val type = EnhancementType.FACT_CHECKING

        // Assert
        assertEquals("FACT_CHECKING", type.name)
    }

    @Test
    fun `should have QUALITY_ASSESSMENT value`() {
        // Act
        val type = EnhancementType.QUALITY_ASSESSMENT

        // Assert
        assertEquals("QUALITY_ASSESSMENT", type.name)
    }

    @Test
    fun `should have CONTENT_SYNTHESIS value`() {
        // Act
        val type = EnhancementType.CONTENT_SYNTHESIS

        // Assert
        assertEquals("CONTENT_SYNTHESIS", type.name)
    }

    @Test
    fun `should have MULTIMODAL_PROCESSING value`() {
        // Act
        val type = EnhancementType.MULTIMODAL_PROCESSING

        // Assert
        assertEquals("MULTIMODAL_PROCESSING", type.name)
    }

    @Test
    fun `should have CUSTOM value`() {
        // Act
        val type = EnhancementType.CUSTOM

        // Assert
        assertEquals("CUSTOM", type.name)
    }

    @Test
    fun `should have exactly 9 values`() {
        // Act
        val values = EnhancementType.values()

        // Assert
        assertEquals(9, values.size)
    }

    @Test
    fun `should support valueOf for COMPRESSION`() {
        // Act
        val type = EnhancementType.valueOf("COMPRESSION")

        // Assert
        assertEquals(EnhancementType.COMPRESSION, type)
    }

    @Test
    fun `should support valueOf for RERANKING`() {
        // Act
        val type = EnhancementType.valueOf("RERANKING")

        // Assert
        assertEquals(EnhancementType.RERANKING, type)
    }

    @Test
    fun `should support valueOf for DEDUPLICATION`() {
        // Act
        val type = EnhancementType.valueOf("DEDUPLICATION")

        // Assert
        assertEquals(EnhancementType.DEDUPLICATION, type)
    }

    @Test
    fun `should support valueOf for ENTITY_EXTRACTION`() {
        // Act
        val type = EnhancementType.valueOf("ENTITY_EXTRACTION")

        // Assert
        assertEquals(EnhancementType.ENTITY_EXTRACTION, type)
    }

    @Test
    fun `should support valueOf for FACT_CHECKING`() {
        // Act
        val type = EnhancementType.valueOf("FACT_CHECKING")

        // Assert
        assertEquals(EnhancementType.FACT_CHECKING, type)
    }

    @Test
    fun `should support valueOf for QUALITY_ASSESSMENT`() {
        // Act
        val type = EnhancementType.valueOf("QUALITY_ASSESSMENT")

        // Assert
        assertEquals(EnhancementType.QUALITY_ASSESSMENT, type)
    }

    @Test
    fun `should support valueOf for CONTENT_SYNTHESIS`() {
        // Act
        val type = EnhancementType.valueOf("CONTENT_SYNTHESIS")

        // Assert
        assertEquals(EnhancementType.CONTENT_SYNTHESIS, type)
    }

    @Test
    fun `should support valueOf for MULTIMODAL_PROCESSING`() {
        // Act
        val type = EnhancementType.valueOf("MULTIMODAL_PROCESSING")

        // Assert
        assertEquals(EnhancementType.MULTIMODAL_PROCESSING, type)
    }

    @Test
    fun `should support valueOf for CUSTOM`() {
        // Act
        val type = EnhancementType.valueOf("CUSTOM")

        // Assert
        assertEquals(EnhancementType.CUSTOM, type)
    }

    @Test
    fun `should throw exception for invalid valueOf`() {
        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            EnhancementType.valueOf("INVALID")
        }
    }

    @Test
    fun `should support equality comparison`() {
        // Arrange
        val type1 = EnhancementType.COMPRESSION
        val type2 = EnhancementType.COMPRESSION
        val type3 = EnhancementType.RERANKING

        // Assert
        assertEquals(type1, type2)
        assertNotEquals(type1, type3)
    }

    @Test
    fun `should support toString`() {
        // Act
        val compression = EnhancementType.COMPRESSION.toString()
        val reranking = EnhancementType.RERANKING.toString()

        // Assert
        assertEquals("COMPRESSION", compression)
        assertEquals("RERANKING", reranking)
    }

    @Test
    fun `should support ordinal values`() {
        // Act
        val compression = EnhancementType.COMPRESSION
        val custom = EnhancementType.CUSTOM

        // Assert
        assertEquals(0, compression.ordinal)
        assertEquals(8, custom.ordinal)
    }

    @Test
    fun `values should contain all enum constants`() {
        // Act
        val values = EnhancementType.values()

        // Assert
        assertTrue(values.contains(EnhancementType.COMPRESSION))
        assertTrue(values.contains(EnhancementType.RERANKING))
        assertTrue(values.contains(EnhancementType.DEDUPLICATION))
        assertTrue(values.contains(EnhancementType.ENTITY_EXTRACTION))
        assertTrue(values.contains(EnhancementType.FACT_CHECKING))
        assertTrue(values.contains(EnhancementType.QUALITY_ASSESSMENT))
        assertTrue(values.contains(EnhancementType.CONTENT_SYNTHESIS))
        assertTrue(values.contains(EnhancementType.MULTIMODAL_PROCESSING))
        assertTrue(values.contains(EnhancementType.CUSTOM))
    }
}
