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
package com.embabel.agent.rag.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NavigableContainerSectionTest {

    @Test
    fun `descendants returns empty iterable for container with no children`() {
        val container = DefaultMaterializedContainerSection(
            id = "container-1",
            title = "Empty Container",
            children = emptyList()
        )

        val descendants = container.descendants()

        assertEquals(0, descendants.count())
    }

    @Test
    fun `descendants returns all direct children when no nested containers`() {
        val leaf1 = LeafSection(id = "leaf-1", title = "Leaf 1", text = "Content 1")
        val leaf2 = LeafSection(id = "leaf-2", title = "Leaf 2", text = "Content 2")
        val leaf3 = LeafSection(id = "leaf-3", title = "Leaf 3", text = "Content 3")

        val container = DefaultMaterializedContainerSection(
            id = "container-1",
            title = "Parent Container",
            children = listOf(leaf1, leaf2, leaf3)
        )

        val descendants = container.descendants().toList()

        assertEquals(3, descendants.size)
        assertTrue(descendants.contains(leaf1))
        assertTrue(descendants.contains(leaf2))
        assertTrue(descendants.contains(leaf3))
    }

    @Test
    fun `descendants returns all nested children recursively`() {
        val leafA = LeafSection(id = "leaf-a", title = "Leaf A", text = "Content A")
        val leafB = LeafSection(id = "leaf-b", title = "Leaf B", text = "Content B")
        val leafC = LeafSection(id = "leaf-c", title = "Leaf C", text = "Content C")
        val leafD = LeafSection(id = "leaf-d", title = "Leaf D", text = "Content D")

        val subContainer1 = DefaultMaterializedContainerSection(
            id = "sub-1",
            title = "Sub Container 1",
            children = listOf(leafA, leafB)
        )

        val subContainer2 = DefaultMaterializedContainerSection(
            id = "sub-2",
            title = "Sub Container 2",
            children = listOf(leafC)
        )

        val rootContainer = DefaultMaterializedContainerSection(
            id = "root",
            title = "Root Container",
            children = listOf(subContainer1, leafD, subContainer2)
        )

        val descendants = rootContainer.descendants().toList()

        // Should include: subContainer1, leafD, subContainer2, leafA, leafB, leafC
        assertEquals(6, descendants.size)
        assertTrue(descendants.contains(subContainer1))
        assertTrue(descendants.contains(subContainer2))
        assertTrue(descendants.contains(leafA))
        assertTrue(descendants.contains(leafB))
        assertTrue(descendants.contains(leafC))
        assertTrue(descendants.contains(leafD))
    }

    @Test
    fun `descendants returns deeply nested children`() {
        val leafDeep = LeafSection(id = "leaf-deep", title = "Deep Leaf", text = "Deep content")

        val level3 = DefaultMaterializedContainerSection(
            id = "level-3",
            title = "Level 3",
            children = listOf(leafDeep)
        )

        val level2 = DefaultMaterializedContainerSection(
            id = "level-2",
            title = "Level 2",
            children = listOf(level3)
        )

        val level1 = DefaultMaterializedContainerSection(
            id = "level-1",
            title = "Level 1",
            children = listOf(level2)
        )

        val descendants = level1.descendants().toList()

        assertEquals(3, descendants.size)
        assertTrue(descendants.contains(level2))
        assertTrue(descendants.contains(level3))
        assertTrue(descendants.contains(leafDeep))
    }

    @Test
    fun `descendants iterable can be iterated multiple times`() {
        val leaf1 = LeafSection(id = "leaf-1", title = "Leaf 1", text = "Content 1")
        val leaf2 = LeafSection(id = "leaf-2", title = "Leaf 2", text = "Content 2")

        val container = DefaultMaterializedContainerSection(
            id = "container-1",
            title = "Container",
            children = listOf(leaf1, leaf2)
        )

        val descendants = container.descendants()

        // First iteration
        val firstList = descendants.toList()
        assertEquals(2, firstList.size)

        // Second iteration should produce same results
        val secondList = descendants.toList()
        assertEquals(2, secondList.size)
        assertEquals(firstList, secondList)
    }

    @Test
    fun `leaves returns empty iterable for container with no children`() {
        val container = DefaultMaterializedContainerSection(
            id = "container-1",
            title = "Empty Container",
            children = emptyList()
        )

        val leaves = container.leaves()

        assertEquals(0, leaves.count())
    }

    @Test
    fun `leaves returns only leaf sections ignoring containers`() {
        val leaf1 = LeafSection(id = "leaf-1", title = "Leaf 1", text = "Content 1")
        val leaf2 = LeafSection(id = "leaf-2", title = "Leaf 2", text = "Content 2")

        val emptyContainer = DefaultMaterializedContainerSection(
            id = "empty",
            title = "Empty Sub",
            children = emptyList()
        )

        val container = DefaultMaterializedContainerSection(
            id = "container-1",
            title = "Mixed Container",
            children = listOf(leaf1, emptyContainer, leaf2)
        )

        val leaves = container.leaves().toList()

        assertEquals(2, leaves.size)
        assertTrue(leaves.contains(leaf1))
        assertTrue(leaves.contains(leaf2))
    }

    @Test
    fun `leaves returns all leaf sections from nested structure`() {
        val leafA = LeafSection(id = "leaf-a", title = "Leaf A", text = "Content A")
        val leafB = LeafSection(id = "leaf-b", title = "Leaf B", text = "Content B")
        val leafC = LeafSection(id = "leaf-c", title = "Leaf C", text = "Content C")
        val leafD = LeafSection(id = "leaf-d", title = "Leaf D", text = "Content D")

        val subContainer1 = DefaultMaterializedContainerSection(
            id = "sub-1",
            title = "Sub Container 1",
            children = listOf(leafA, leafB)
        )

        val subContainer2 = DefaultMaterializedContainerSection(
            id = "sub-2",
            title = "Sub Container 2",
            children = listOf(leafC)
        )

        val rootContainer = DefaultMaterializedContainerSection(
            id = "root",
            title = "Root Container",
            children = listOf(subContainer1, leafD, subContainer2)
        )

        val leaves = rootContainer.leaves().toList()

        // Should only include leaf sections, not containers
        assertEquals(4, leaves.size)
        assertTrue(leaves.contains(leafA))
        assertTrue(leaves.contains(leafB))
        assertTrue(leaves.contains(leafC))
        assertTrue(leaves.contains(leafD))
        assertFalse((leaves as List<*>).contains(subContainer1))
        assertFalse((leaves as List<*>).contains(subContainer2))
    }

    @Test
    fun `leaves returns deeply nested leaf sections`() {
        val leafDeep = LeafSection(id = "leaf-deep", title = "Deep Leaf", text = "Deep content")
        val leafMid = LeafSection(id = "leaf-mid", title = "Mid Leaf", text = "Mid content")

        val level3 = DefaultMaterializedContainerSection(
            id = "level-3",
            title = "Level 3",
            children = listOf(leafDeep)
        )

        val level2 = DefaultMaterializedContainerSection(
            id = "level-2",
            title = "Level 2",
            children = listOf(level3, leafMid)
        )

        val level1 = DefaultMaterializedContainerSection(
            id = "level-1",
            title = "Level 1",
            children = listOf(level2)
        )

        val leaves = level1.leaves().toList()

        assertEquals(2, leaves.size)
        assertTrue(leaves.contains(leafDeep))
        assertTrue(leaves.contains(leafMid))
    }

    @Test
    fun `leaves iterable can be iterated multiple times`() {
        val leaf1 = LeafSection(id = "leaf-1", title = "Leaf 1", text = "Content 1")
        val leaf2 = LeafSection(id = "leaf-2", title = "Leaf 2", text = "Content 2")

        val container = DefaultMaterializedContainerSection(
            id = "container-1",
            title = "Container",
            children = listOf(leaf1, leaf2)
        )

        val leaves = container.leaves()

        // First iteration
        val firstList = leaves.toList()
        assertEquals(2, firstList.size)

        // Second iteration should produce same results
        val secondList = leaves.toList()
        assertEquals(2, secondList.size)
        assertEquals(firstList, secondList)
    }

    @Test
    fun `descendants and leaves work with complex mixed structure`() {
        val leaf1 = LeafSection(id = "leaf-1", title = "Leaf 1", text = "Content 1")
        val leaf2 = LeafSection(id = "leaf-2", title = "Leaf 2", text = "Content 2")
        val leaf3 = LeafSection(id = "leaf-3", title = "Leaf 3", text = "Content 3")
        val leaf4 = LeafSection(id = "leaf-4", title = "Leaf 4", text = "Content 4")
        val leaf5 = LeafSection(id = "leaf-5", title = "Leaf 5", text = "Content 5")

        val deepContainer = DefaultMaterializedContainerSection(
            id = "deep",
            title = "Deep",
            children = listOf(leaf5)
        )

        val midContainer = DefaultMaterializedContainerSection(
            id = "mid",
            title = "Mid",
            children = listOf(leaf3, deepContainer, leaf4)
        )

        val root = DefaultMaterializedContainerSection(
            id = "root",
            title = "Root",
            children = listOf(leaf1, midContainer, leaf2)
        )

        val descendants = root.descendants().toList()
        val leaves = root.leaves().toList()

        // Descendants should include all nodes
        assertEquals(7, descendants.size) // leaf1, midContainer, leaf2, leaf3, deepContainer, leaf4, leaf5

        // Leaves should only include leaf sections
        assertEquals(5, leaves.size)
        assertTrue(leaves.containsAll(listOf(leaf1, leaf2, leaf3, leaf4, leaf5)))
    }

    @Test
    fun `descendants preserves order of traversal`() {
        val leafA = LeafSection(id = "leaf-a", title = "Leaf A", text = "Content A")
        val leafB = LeafSection(id = "leaf-b", title = "Leaf B", text = "Content B")
        val leafC = LeafSection(id = "leaf-c", title = "Leaf C", text = "Content C")

        val subContainer = DefaultMaterializedContainerSection(
            id = "sub",
            title = "Sub Container",
            children = listOf(leafB, leafC)
        )

        val container = DefaultMaterializedContainerSection(
            id = "root",
            title = "Root Container",
            children = listOf(leafA, subContainer)
        )

        val descendants = container.descendants().toList()

        // Order should be: leafA, subContainer, leafB, leafC
        assertEquals(4, descendants.size)
        assertEquals(leafA, descendants[0])
        assertEquals(subContainer, descendants[1])
        assertEquals(leafB, descendants[2])
        assertEquals(leafC, descendants[3])
    }
}
