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

import com.embabel.common.core.types.SimilarityResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClusterTest {

    @Test
    fun `should create Cluster with anchor and similar items`() {
        // Arrange
        val anchor = "anchor-item"
        val similar = listOf(
            SimilarityResult("item1", 0.9),
            SimilarityResult("item2", 0.85)
        )

        // Act
        val cluster = Cluster(anchor = anchor, similar = similar)

        // Assert
        assertEquals("anchor-item", cluster.anchor)
        assertEquals(2, cluster.similar.size)
        assertEquals("item1", cluster.similar[0].match)
        assertEquals(0.9, cluster.similar[0].score, 0.001)
    }

    @Test
    fun `should create Cluster with empty similar list`() {
        // Arrange & Act
        val cluster = Cluster(anchor = "single-item", similar = emptyList<SimilarityResult<String>>())

        // Assert
        assertEquals("single-item", cluster.anchor)
        assertTrue(cluster.similar.isEmpty())
    }

    @Test
    fun `should support copy with modified anchor`() {
        // Arrange
        val original = Cluster(anchor = "original", similar = emptyList<SimilarityResult<String>>())

        // Act
        val modified = original.copy(anchor = "modified")

        // Assert
        assertEquals("modified", modified.anchor)
        assertEquals("original", original.anchor)
    }

    @Test
    fun `should support copy with modified similar list`() {
        // Arrange
        val original = Cluster(anchor = "anchor", similar = emptyList<SimilarityResult<String>>())
        val newSimilar = listOf(SimilarityResult("new-item", 0.95))

        // Act
        val modified = original.copy(similar = newSimilar)

        // Assert
        assertEquals(1, modified.similar.size)
        assertTrue(original.similar.isEmpty())
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val similar = listOf(SimilarityResult("item", 0.8))
        val cluster1 = Cluster("anchor", similar)
        val cluster2 = Cluster("anchor", similar)
        val cluster3 = Cluster("different", similar)

        // Assert
        assertEquals(cluster1, cluster2)
        assertNotEquals(cluster1, cluster3)
    }

    @Test
    fun `should work with different types`() {
        // Arrange
        val intCluster = Cluster(anchor = 42, similar = listOf(SimilarityResult(41, 0.9)))
        val stringCluster = Cluster(anchor = "text", similar = listOf(SimilarityResult("other", 0.85)))

        // Assert
        assertEquals(42, intCluster.anchor)
        assertEquals("text", stringCluster.anchor)
    }

    @Test
    fun `should handle multiple similar items`() {
        // Arrange
        val similar = (1..10).map { SimilarityResult("item$it", 1.0 - it * 0.05) }

        // Act
        val cluster = Cluster(anchor = "main", similar = similar)

        // Assert
        assertEquals(10, cluster.similar.size)
        assertEquals("item1", cluster.similar[0].match)
        assertEquals("item10", cluster.similar[9].match)
    }
}
