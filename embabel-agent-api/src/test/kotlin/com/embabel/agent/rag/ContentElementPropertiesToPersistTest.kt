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
package com.embabel.agent.rag

import com.embabel.agent.rag.ingestion.DefaultMaterializedContainerSection
import com.embabel.agent.rag.ingestion.MaterializedDocument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContentElementPropertiesToPersistTest {

    @Test
    fun `test LeafSection propertiesToPersist includes all required properties`() {
        val leafSection = LeafSection(
            id = "leaf-1",
            uri = "http://example.com/doc",
            title = "Section Title",
            text = "This is the content of the section",
            parentId = "parent-1",
            metadata = mapOf(
                "author" to "John Doe",
                "tags" to listOf("important", "test")
            )
        )

        val properties = leafSection.propertiesToPersist()

        // Base ContentElement properties
        assertEquals("leaf-1", properties["id"])
        assertEquals("http://example.com/doc", properties["uri"])

        // Metadata properties should be included
        assertEquals("John Doe", properties["author"])
        assertEquals(listOf("important", "test"), properties["tags"])

        // HierarchicalContentElement properties
        assertEquals("parent-1", properties["parentId"])

        // Section properties
        assertEquals("Section Title", properties["title"])

        // LeafSection specific properties
        assertEquals("This is the content of the section", properties["text"])

        // Verify all expected keys are present
        val expectedKeys = setOf("id", "uri", "author", "tags", "parentId", "title", "text")
        assertEquals(expectedKeys, properties.keys)
    }

    @Test
    fun `test LeafSection propertiesToPersist handles null values correctly`() {
        val leafSection = LeafSection(
            id = "leaf-2",
            uri = null,
            title = "Title Only",
            text = "Content only",
            parentId = null,
            metadata = emptyMap()
        )

        val properties = leafSection.propertiesToPersist()

        assertEquals("leaf-2", properties["id"])
        assertNull(properties["uri"])
        assertNull(properties["parentId"])
        assertEquals("Title Only", properties["title"])
        assertEquals("Content only", properties["text"])

        // Should only have base properties when metadata is empty
        val expectedKeys = setOf("id", "uri", "parentId", "title", "text")
        assertEquals(expectedKeys, properties.keys)
    }

    @Test
    fun `test DefaultMaterializedContainerSection propertiesToPersist`() {
        val childSection = LeafSection(
            id = "child-1",
            title = "Child",
            text = "Child content"
        )

        val containerSection = DefaultMaterializedContainerSection(
            id = "container-1",
            uri = "http://example.com/container",
            title = "Container Title",
            children = listOf(childSection),
            parentId = "root-1",
            metadata = mapOf("type" to "container")
        )

        val properties = containerSection.propertiesToPersist()

        // Base ContentElement properties
        assertEquals("container-1", properties["id"])
        assertEquals("http://example.com/container", properties["uri"])

        // Metadata
        assertEquals("container", properties["type"])

        // HierarchicalContentElement properties
        assertEquals("root-1", properties["parentId"])

        // Section properties
        assertEquals("Container Title", properties["title"])

        // Note: children are not persisted in propertiesToPersist
        assertFalse(properties.containsKey("children"))

        val expectedKeys = setOf("id", "uri", "type", "parentId", "title")
        assertEquals(expectedKeys, properties.keys)
    }

    @Test
    fun `test MaterializedContentRoot propertiesToPersist`() {
        val childSection = LeafSection(
            id = "child-1",
            title = "Child",
            text = "Child content"
        )

        val contentRoot = MaterializedDocument(
            id = "root-1",
            uri = "http://example.com/document",
            title = "Document Title",
            children = listOf(childSection),
            metadata = mapOf(
                "document-type" to "article",
                "version" to "1.0"
            )
        )

        val properties = contentRoot.propertiesToPersist()

        // Base ContentElement properties
        assertEquals("root-1", properties["id"])
        assertEquals("http://example.com/document", properties["uri"])

        // Metadata
        assertEquals("article", properties["document-type"])
        assertEquals("1.0", properties["version"])

        // HierarchicalContentElement properties
        // ContentRoot always has null parentId
        assertNull(properties["parentId"])

        // Section properties
        assertEquals("Document Title", properties["title"])

        // Children are not persisted
        assertFalse(properties.containsKey("children"))

        val expectedKeys = setOf("id", "uri", "document-type", "version", "parentId", "title")
        assertEquals(expectedKeys, properties.keys)
    }

    @Test
    fun `test Chunk propertiesToPersist includes hierarchical properties`() {
        val chunk = Chunk(
            id = "chunk-1",
            text = "This is a chunk of text content",
            metadata = mapOf(
                "url" to "http://example.com/source",
                "chunk_index" to 0,
                "total_chunks" to 5
            ),
            parentId = "section-1"
        )

        val properties = chunk.propertiesToPersist()

        // Base ContentElement properties
        assertEquals("chunk-1", properties["id"])
        assertEquals("http://example.com/source", properties["uri"]) // Note: derived from metadata["url"]

        // Metadata properties should be included
        assertEquals("http://example.com/source", properties["url"])
        assertEquals(0, properties["chunk_index"])
        assertEquals(5, properties["total_chunks"])

        // HierarchicalContentElement properties
        assertEquals("section-1", properties["parentId"])

        val expectedKeys = setOf("id", "uri", "url", "chunk_index", "total_chunks", "parentId", "text")
        assertEquals(expectedKeys, properties.keys)
    }

    @Test
    fun `test Fact propertiesToPersist includes base properties`() {
        val fact = Fact(
            assertion = "The sky is blue",
            authority = "Weather Service",
            uri = "http://weather.gov/facts/1",
            metadata = mapOf(
                "confidence" to 0.95,
                "verified" to true
            ),
            id = "fact-1"
        )

        val properties = fact.propertiesToPersist()

        // Base ContentElement properties
        assertEquals("fact-1", properties["id"])
        assertEquals("http://weather.gov/facts/1", properties["uri"])

        // Metadata
        assertEquals(0.95, properties["confidence"])
        assertEquals(true, properties["verified"])

        // Note: Fact doesn't override propertiesToPersist to include assertion/authority
        // This might be a gap that should be addressed
        assertFalse(properties.containsKey("assertion"))
        assertFalse(properties.containsKey("authority"))

        val expectedKeys = setOf("id", "uri", "confidence", "verified")
        assertEquals(expectedKeys, properties.keys)
    }

    @Test
    fun `test propertiesToPersist inheritance chain works correctly`() {
        // Create a leaf section with comprehensive metadata
        val leafSection = LeafSection(
            id = "test-leaf",
            uri = "http://test.com/leaf",
            title = "Test Leaf",
            text = "Test content",
            parentId = "test-parent",
            metadata = mapOf(
                "custom1" to "value1",
                "custom2" to 42,
                "custom3" to null
            )
        )

        val properties = leafSection.propertiesToPersist()

        // Verify inheritance chain:
        // ContentElement -> HierarchicalContentElement -> Section -> LeafSection

        // Each level should contribute its properties:
        assertTrue(properties.containsKey("id"))          // ContentElement
        assertTrue(properties.containsKey("uri"))         // ContentElement
        assertTrue(properties.containsKey("custom1"))     // ContentElement (metadata)
        assertTrue(properties.containsKey("custom2"))     // ContentElement (metadata)
        assertTrue(properties.containsKey("custom3"))     // ContentElement (metadata)
        assertTrue(properties.containsKey("parentId"))    // HierarchicalContentElement
        assertTrue(properties.containsKey("title"))       // Section
        assertTrue(properties.containsKey("text"))     // LeafSection

        // Verify correct values
        assertEquals("test-leaf", properties["id"])
        assertEquals("http://test.com/leaf", properties["uri"])
        assertEquals("value1", properties["custom1"])
        assertEquals(42, properties["custom2"])
        assertNull(properties["custom3"]) // null values should be preserved
        assertEquals("test-parent", properties["parentId"])
        assertEquals("Test Leaf", properties["title"])
        assertEquals("Test content", properties["text"])
    }

    @Test
    fun `test empty metadata doesn't break propertiesToPersist`() {
        val leafSection = LeafSection(
            id = "empty-meta",
            uri = "http://example.com",
            title = "No Metadata",
            text = "text",
            parentId = "parent",
            metadata = emptyMap()
        )

        val properties = leafSection.propertiesToPersist()

        // Should still have all the required properties from the inheritance chain
        assertEquals(5, properties.size)
        assertEquals("empty-meta", properties["id"])
        assertEquals("http://example.com", properties["uri"])
        assertEquals("parent", properties["parentId"])
        assertEquals("No Metadata", properties["title"])
        assertEquals("text", properties["text"])
    }

    @Test
    fun `test metadata overrides behavior - WARNING this may be a bug`() {
        // Test case where metadata has keys that conflict with class properties
        // This documents the current behavior where metadata OVERRIDES class properties
        // This might be a bug in the implementation!
        val leafSection = LeafSection(
            id = "override-test",
            uri = "http://example.com",
            title = "Real Title",
            text = "Real Content",
            parentId = "real-parent",
            metadata = mapOf(
                "title" to "Metadata Title", // This DOES override the actual title (possibly bug!)
                "text" to "Metadata Content", // This DOES override the actual content (possibly bug!)
                "id" to "metadata-id" // This DOES override the actual id (definitely a bug!)
            )
        )

        val properties = leafSection.propertiesToPersist()

        assertEquals("metadata-id", properties["id"]) // BUG: metadata should not override id!
        assertEquals("http://example.com", properties["uri"]) // uri not in metadata, so works correctly
        assertEquals("real-parent", properties["parentId"]) // HierarchicalContentElement overrides metadata
        assertEquals("Real Title", properties["title"]) // Section overrides metadata
        assertEquals("Real Content", properties["text"]) // LeafSection overrides metadata
    }

    @Test
    fun `test proper metadata merge behavior when no conflicts`() {
        // Test that metadata works correctly when not conflicting with class properties
        val leafSection = LeafSection(
            id = "test-id",
            uri = "http://example.com",
            title = "Title",
            text = "text",
            parentId = "parent",
            metadata = mapOf(
                "author" to "John Doe",
                "tags" to listOf("tag1", "tag2"),
                "custom" to "value"
            )
        )

        val properties = leafSection.propertiesToPersist()

        // These should work correctly
        assertEquals("test-id", properties["id"])
        assertEquals("http://example.com", properties["uri"])
        assertEquals("parent", properties["parentId"])
        assertEquals("Title", properties["title"])
        assertEquals("text", properties["text"])
        assertEquals("John Doe", properties["author"])
        assertEquals(listOf("tag1", "tag2"), properties["tags"])
        assertEquals("value", properties["custom"])
    }

    @Test
    fun `test Chunk missing text property in propertiesToPersist - POTENTIAL BUG`() {
        // This test documents that Chunk doesn't include its 'text' property
        // in propertiesToPersist, which might be a bug since text is the main content
        val chunk = Chunk(
            id = "chunk-test",
            text = "Important chunk text content",
            metadata = mapOf("source" to "document"),
            parentId = "section-1"
        )

        val properties = chunk.propertiesToPersist()


        // But other properties are included correctly
        assertTrue(properties.containsKey("id"))
        assertTrue(properties.containsKey("parentId"))
        assertTrue(properties.containsKey("source"))

        // If this is intentional, there should be a comment explaining why
        // text content is not persisted with propertiesToPersist
    }

    @Test
    fun `test Fact missing assertion and authority properties - POTENTIAL BUG`() {
        // This test documents that Fact doesn't include its 'assertion' and 'authority'
        // properties in propertiesToPersist, which is likely a bug
        val fact = Fact(
            assertion = "Critical fact assertion",
            authority = "Authoritative source",
            uri = "http://facts.com/1",
            metadata = mapOf("confidence" to 0.9),
            id = "fact-test"
        )

        val properties = fact.propertiesToPersist()

        // The core Fact properties are NOT included - this is likely a bug!
        assertFalse(properties.containsKey("assertion"))
        assertFalse(properties.containsKey("authority"))

        // But base properties are included
        assertTrue(properties.containsKey("id"))
        assertTrue(properties.containsKey("uri"))
        assertTrue(properties.containsKey("confidence"))

        // Fact should probably override propertiesToPersist to include assertion and authority
    }

    @Test
    fun `test comprehensive property coverage for all hierarchy levels`() {
        // This test verifies that each level of the hierarchy contributes the expected properties
        val leafSection = LeafSection(
            id = "comprehensive-test",
            uri = "http://comprehensive.com",
            title = "Comprehensive Title",
            text = "Comprehensive Content",
            parentId = "comprehensive-parent",
            metadata = mapOf("level" to "comprehensive")
        )

        val properties = leafSection.propertiesToPersist()

        // ContentElement level (2 properties + metadata)
        assertTrue(properties.containsKey("id"))
        assertTrue(properties.containsKey("uri"))
        assertTrue(properties.containsKey("level")) // from metadata

        // HierarchicalContentElement level (1 property)
        assertTrue(properties.containsKey("parentId"))

        // Section level (1 property)
        assertTrue(properties.containsKey("title"))

        // LeafSection level (1 property)
        assertTrue(properties.containsKey("text"))

        // Total: 6 properties expected
        assertEquals(6, properties.size)

        // Verify values are correct
        assertEquals("comprehensive-test", properties["id"])
        assertEquals("http://comprehensive.com", properties["uri"])
        assertEquals("comprehensive", properties["level"])
        assertEquals("comprehensive-parent", properties["parentId"])
        assertEquals("Comprehensive Title", properties["title"])
        assertEquals("Comprehensive Content", properties["text"])
    }
}
