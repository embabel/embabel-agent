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
package com.embabel.agent.rag.ingestion

import com.embabel.agent.rag.LeafSection
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path

class HierarchicalContentReaderTest {

    private val reader = HierarchicalContentReader()

    @Test
    fun `test parse simple markdown content`() {
        val markdown = """
            # Main Title
            This is the introduction.

            ## Section 1
            Content for section 1.

            ### Subsection 1.1
            Content for subsection 1.1.

            ## Section 2
            Content for section 2.
        """.trimIndent()

        val inputStream = ByteArrayInputStream(markdown.toByteArray())
        val metadata = Metadata().apply {
            set(TikaCoreProperties.RESOURCE_NAME_KEY, "test.md")
            set(TikaCoreProperties.CONTENT_TYPE_HINT, "text/markdown")
        }

        val result = reader.parseContent(inputStream, metadata, "test://example.md")

        assertEquals(4, result.children.size) // Introduction + Section 1 + Subsection 1.1 + Section 2
        assertEquals("test://example.md", result.url)
        assertNotNull(result.id)

        // Check that all sections have proper titles and content
        val titles = result.children.map { it.title }
        assertTrue(titles.contains("Main Title"))
        assertTrue(titles.contains("Section 1"))
        assertTrue(titles.contains("Subsection 1.1"))
        assertTrue(titles.contains("Section 2"))

        // Check that all sections have the same URL
        result.children.forEach { section ->
            assertEquals("test://example.md", section.url)
            assertNotNull(section.id)
        }
    }

    @Test
    fun `test parse markdown with nested structure`() {
        val markdown = """
            # Document Title
            Introduction paragraph.

            ## Chapter 1
            Chapter introduction.

            ### Section 1.1
            Section content here.

            #### Subsection 1.1.1
            Detailed content.

            ### Section 1.2
            More content.

            ## Chapter 2
            Second chapter content.
        """.trimIndent()

        val inputStream = ByteArrayInputStream(markdown.toByteArray())
        val metadata = Metadata().apply {
            set(TikaCoreProperties.RESOURCE_NAME_KEY, "document.md")
        }

        val result = reader.parseContent(inputStream, metadata)

        assertEquals(6, result.children.size) // All leaf sections
        assertNotNull(result.id)

        val titles = result.children.map { it.title }
        assertTrue(titles.contains("Document Title"))
        assertTrue(titles.contains("Chapter 1"))
        assertTrue(titles.contains("Section 1.1"))
        assertTrue(titles.contains("Subsection 1.1.1"))
        assertTrue(titles.contains("Section 1.2"))
        assertTrue(titles.contains("Chapter 2"))

        // Verify all sections have content
        result.children.forEach { section ->
            assertTrue((section as LeafSection).content.isNotBlank())
            assertNotNull(section.id)
        }
    }

    @Test
    fun `test parse plain text content`() {
        val text = """
            This is a simple text document.
            It has multiple lines.
            But no special formatting.
        """.trimIndent()

        val inputStream = ByteArrayInputStream(text.toByteArray())
        val metadata = Metadata().apply {
            set(TikaCoreProperties.CONTENT_TYPE_HINT, "text/plain")
        }

        val result = reader.parseContent(inputStream, metadata, "test://plain.txt")

        assertEquals(1, result.children.size)
        assertEquals("test://plain.txt", result.url)
        assertNotNull(result.id)

        val section = result.children.first() as LeafSection
        assertEquals("This is a simple text document.", section.title)
        assertEquals("test://plain.txt", section.url)
        assertEquals(text, section.content)
        assertNotNull(section.id)
    }

    @Test
    fun `test parse file from disk`(@TempDir tempDir: Path) {
        val markdownFile = tempDir.resolve("test.md").toFile()
        val markdown = """
            # Test Document
            This is a test document.

            ## First Section
            Content of the first section.

            ## Second Section
            Content of the second section.
        """.trimIndent()

        markdownFile.writeText(markdown)

        val result = reader.parseFile(markdownFile)

        assertEquals(3, result.children.size)
        assertNotNull(result.id)
        val titles = result.children.map { it.title }
        assertTrue(titles.contains("Test Document"))
        assertTrue(titles.contains("First Section"))
        assertTrue(titles.contains("Second Section"))

        result.children.forEach { section ->
            assertTrue(section.url!!.contains("test.md"))
            assertNotNull(section.id)
        }
    }

    @Test
    fun `test title extraction from metadata`() {
        val content = "Some content without markdown headers"

        val inputStream = ByteArrayInputStream(content.toByteArray())
        val metadata = Metadata().apply {
            set(TikaCoreProperties.TITLE, "Custom Title from Metadata")
        }

        val result = reader.parseContent(inputStream, metadata)

        assertEquals(1, result.children.size)
        assertEquals("Custom Title from Metadata", (result.children.first() as LeafSection).title)
    }

    @Test
    fun `test title extraction from first line when no headers`() {
        val content = """
            This should become the title
            And this is the rest of the content.
        """.trimIndent()

        val inputStream = ByteArrayInputStream(content.toByteArray())

        val result = reader.parseContent(inputStream)

        assertEquals(1, result.children.size)
        assertEquals("This should become the title", (result.children.first() as LeafSection).title)
    }

    @Test
    fun `test metadata preservation`() {
        val content = "Simple content"

        val inputStream = ByteArrayInputStream(content.toByteArray())
        val metadata = Metadata().apply {
            set(TikaCoreProperties.TITLE, "Test Title")
            set(TikaCoreProperties.CREATOR, "Test Author")
            set("custom-field", "custom-value")
        }

        val result = reader.parseContent(inputStream, metadata)

        assertEquals(1, result.children.size)
        val section = result.children.first() as LeafSection
        val resultMetadata = section.metadata
        assertEquals("Test Author", resultMetadata[TikaCoreProperties.CREATOR.name])
        assertEquals("custom-value", resultMetadata["custom-field"])
    }

    @Test
    fun `test error handling for empty content`() {
        // Create an input stream with empty content
        val inputStream = ByteArrayInputStream(ByteArray(0))

        val result = reader.parseContent(inputStream)

        // Should return empty content root for empty content
        assertTrue(result.children.isEmpty())
        assertNotNull(result.id)
    }

    @Test
    fun `test HTML content parsing`() {
        val html = """
            <html>
            <head><title>HTML Document</title></head>
            <body>
                <h1>Main Heading</h1>
                <p>This is a paragraph with <strong>bold</strong> text.</p>
                <h2>Second Heading</h2>
                <p>More content here.</p>
            </body>
            </html>
        """.trimIndent()

        val inputStream = ByteArrayInputStream(html.toByteArray())
        val metadata = Metadata().apply {
            set(TikaCoreProperties.CONTENT_TYPE_HINT, "text/html")
        }

        val result = reader.parseContent(inputStream, metadata)

        assertEquals(1, result.children.size) // HTML content treated as single section
        val section = result.children.first() as LeafSection
        assertNotNull(section.title)
        assertTrue(section.content.isNotBlank())
        // Content should be cleaned HTML
        assertFalse(section.content.contains("<"))
        assertFalse(section.content.contains(">"))
    }

    @Test
    fun `test markdown with code blocks`() {
        val markdown = """
            # Code Examples

            Here's some code:

            ```kotlin
            fun main() {
                println("Hello World")
            }
            ```

            ## Another Section
            More content after code.
        """.trimIndent()

        val inputStream = ByteArrayInputStream(markdown.toByteArray())
        val metadata = Metadata().apply {
            set(TikaCoreProperties.RESOURCE_NAME_KEY, "code.md")
        }

        val result = reader.parseContent(inputStream, metadata)

        assertEquals(2, result.children.size)
        val titles = result.children.map { it.title }
        assertTrue(titles.contains("Code Examples"))
        assertTrue(titles.contains("Another Section"))

        // Find the Code Examples section and verify it contains the code block
        val codeSection = result.children.find { it.title == "Code Examples" } as LeafSection?
        assertNotNull(codeSection)
        assertTrue(codeSection!!.content.contains("```kotlin"))
        assertTrue(codeSection.content.contains("fun main()"))
    }

    @Test
    fun `test empty markdown file`() {
        val markdown = ""

        val inputStream = ByteArrayInputStream(markdown.toByteArray())
        val metadata = Metadata().apply {
            set(TikaCoreProperties.RESOURCE_NAME_KEY, "empty.md")
        }

        val result = reader.parseContent(inputStream, metadata)

        // Empty content should return empty content root
        assertTrue(result.children.isEmpty())
        assertNotNull(result.id)
    }

    @Test
    fun `test markdown with only content no headers`() {
        val markdown = """
            This is just content.
            No headers at all.
            Just plain text.
        """.trimIndent()

        val inputStream = ByteArrayInputStream(markdown.toByteArray())
        val metadata = Metadata().apply {
            set(TikaCoreProperties.RESOURCE_NAME_KEY, "plain.md")
        }

        val result = reader.parseContent(inputStream, metadata)

        assertEquals(1, result.children.size)
        val section = result.children.first() as LeafSection
        assertEquals("This is just content.", section.title)
        assertEquals(markdown, section.content)
        assertNotNull(section.id)
    }

    @Test
    fun `test multiple markdown sections with different levels`() {
        val markdown = """
            # Level 1 Title
            Content under level 1.

            ## Level 2 Title
            Content under level 2.

            ### Level 3 Title
            Content under level 3.

            #### Level 4 Title
            Content under level 4.
        """.trimIndent()

        val inputStream = ByteArrayInputStream(markdown.toByteArray())

        val result = reader.parseContent(inputStream)

        assertEquals(4, result.children.size)
        val titles = result.children.map { it.title }
        assertEquals(listOf("Level 1 Title", "Level 2 Title", "Level 3 Title", "Level 4 Title"), titles)

        // Verify each section has appropriate content
        result.children.forEach { section ->
            val leafSection = section as LeafSection
            assertTrue(leafSection.content.startsWith("Content under"))
            assertNotNull(leafSection.id)
            assertNotNull(leafSection.parentId) // All sections should have parent references
        }
    }

    @Test
    fun `test section parent relationships are maintained`() {
        val markdown = """
            # Main Section
            Main content.

            ## Sub Section
            Sub content.

            ### Deep Section
            Deep content.
        """.trimIndent()

        val inputStream = ByteArrayInputStream(markdown.toByteArray())

        val result = reader.parseContent(inputStream)

        assertEquals(3, result.children.size)

        // All sections should have parent IDs set
        result.children.forEach { section ->
            assertNotNull(section.parentId)
        }

        // Verify content is correctly assigned
        val mainSection = result.children.find { it.title == "Main Section" } as LeafSection?
        assertEquals("Main content.", mainSection?.content?.trim())

        val subSection = result.children.find { it.title == "Sub Section" } as LeafSection?
        assertEquals("Sub content.", subSection?.content?.trim())

        val deepSection = result.children.find { it.title == "Deep Section" } as LeafSection?
        assertEquals("Deep content.", deepSection?.content?.trim())
    }
}
