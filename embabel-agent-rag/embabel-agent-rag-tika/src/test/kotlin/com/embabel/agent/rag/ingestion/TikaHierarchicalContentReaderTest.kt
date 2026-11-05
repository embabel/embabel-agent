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
package com.embabel.agent.rag.ingestion

import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.NavigableContainerSection
import com.embabel.agent.tools.file.FileReadTools
import io.mockk.every
import io.mockk.mockk
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class TikaHierarchicalContentReaderTest {

    private val reader = TikaHierarchicalContentReader()

    @Nested
    inner class MarkdownParsingTests {

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

            val result = reader.parseContent(inputStream, metadata = metadata, uri = "test://example.md")

            // Check descendants (all sections in hierarchy including preambles)
            val allDescendants = result.descendants()
            // Main Title container + preamble, Section 1 container + preamble, Subsection 1.1 leaf, Section 2 leaf = 6
            assertEquals(6, allDescendants.count())
            assertEquals("test://example.md", result.uri)
            assertNotNull(result.id)

            // Check that all sections have proper titles and content
            val titles = allDescendants.map { it.title }
            assertTrue(titles.contains("Main Title"))
            assertTrue(titles.contains("Section 1"))
            assertTrue(titles.contains("Subsection 1.1"))
            assertTrue(titles.contains("Section 2"))

            // Check that all sections have the same URL
            allDescendants.forEach { section ->
                assertEquals("test://example.md", section.uri)
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

            val result = reader.parseContent(inputStream, "x", metadata)

            // Check all descendants (all sections in hierarchy including preambles)
            val allDescendants = result.descendants()
            // Document Title, Chapter 1, Section 1.1, Subsection 1.1.1, Section 1.2, Chapter 2 (sections)
            // + preambles for Document Title, Chapter 1, Section 1.1 = 9 total
            assertEquals(9, allDescendants.count())
            assertNotNull(result.id)

            val titles = allDescendants.map { it.title }
            assertTrue(titles.contains("Document Title"))
            assertTrue(titles.contains("Chapter 1"))
            assertTrue(titles.contains("Section 1.1"))
            assertTrue(titles.contains("Subsection 1.1.1"))
            assertTrue(titles.contains("Section 1.2"))
            assertTrue(titles.contains("Chapter 2"))

            // Verify all leaf sections have content
            result.leaves().forEach { leaf ->
                assertTrue(leaf.content.isNotBlank())
                assertNotNull(leaf.id)
            }
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

            val result = reader.parseContent(inputStream, "x", metadata)

            val allDescendants = result.descendants()
            // Code Examples container + preamble + Another Section leaf = 3
            assertEquals(3, allDescendants.count())
            val titles = allDescendants.map { it.title }
            assertTrue(titles.contains("Code Examples"))
            assertTrue(titles.contains("Another Section"))

            // Find the Code Examples preamble section and verify it contains the code block
            val codeSection = result.leaves().find { it.title == "Code Examples" }
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

            val result = reader.parseContent(inputStream, "y", metadata)

            // Empty content should return empty content root
            assertTrue(result.children.toList().isEmpty())
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

            val result = reader.parseContent(inputStream, "a", metadata)

            assertEquals(1, result.children.toList().size)
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

            val result = reader.parseContent(inputStream, "uri")

            // Check all descendants (including preambles)
            val allDescendants = result.descendants()
            // Each level has content so gets preamble: L1, L1_preamble, L2, L2_preamble, L3, L3_preamble, L4 = 7
            assertEquals(7, allDescendants.count())
            // Note: titles will include duplicates due to preambles having same title as their container
            val uniqueTitles = allDescendants.map { it.title }.distinct()
            assertTrue(
                uniqueTitles.containsAll(
                    listOf(
                        "Level 1 Title",
                        "Level 2 Title",
                        "Level 3 Title",
                        "Level 4 Title"
                    )
                )
            )

            // Verify each leaf section has appropriate content
            result.leaves().forEach { leaf ->
                assertTrue(leaf.content.startsWith("Content under"))
                assertNotNull(leaf.id)
                assertNotNull(leaf.parentId) // All sections should have parent references
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

            val result = reader.parseContent(inputStream, "uri")

            // Check all descendants (including preambles)
            val allDescendants = result.descendants()
            // Main, Main_preamble, Sub, Sub_preamble, Deep = 5
            assertEquals(5, allDescendants.count())

            // All sections should have parent IDs set
            allDescendants.forEach { section ->
                assertNotNull(section.parentId)
            }

            // Verify content is correctly assigned (preambles preserve content)
            val leaves = result.leaves()
            // Main and Sub sections have preambles (same title), Deep is a leaf
            val mainSection = leaves.find { it.title == "Main Section" }
            assertEquals("Main content.", mainSection?.content?.trim())

            val subSection = leaves.find { it.title == "Sub Section" }
            assertEquals("Sub content.", subSection?.content?.trim())

            val deepSection = leaves.find { it.title == "Deep Section" }
            assertEquals("Deep content.", deepSection?.content?.trim())
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

            // Check all descendants (including preambles)
            val allDescendants = result.descendants()
            // Test Document container + preamble + First Section leaf + Second Section leaf = 4
            assertEquals(4, allDescendants.count())
            assertNotNull(result.id)
            val titles = allDescendants.map { it.title }
            assertTrue(titles.contains("Test Document"))
            assertTrue(titles.contains("First Section"))
            assertTrue(titles.contains("Second Section"))

            allDescendants.forEach { section ->
                assertTrue(section.uri!!.contains("test.md"))
                assertNotNull(section.id)
            }
        }

        @Test
        fun `test markdown builds proper hierarchical structure`() {
            val markdown = """
            # Chapter 1
            Chapter 1 introduction

            ## Section 1.1
            Content for section 1.1

            ### Subsection 1.1.1
            Content for subsection 1.1.1

            ## Section 1.2
            Content for section 1.2

            # Chapter 2
            Chapter 2 introduction
        """.trimIndent()

            val inputStream = ByteArrayInputStream(markdown.toByteArray())
            val metadata = Metadata().apply {
                set(TikaCoreProperties.RESOURCE_NAME_KEY, "test.md")
            }

            val result = reader.parseContent(inputStream, metadata = metadata, uri = "test://hierarchy.md")

            // Root document should have 2 direct children (Chapter 1 and Chapter 2)
            assertEquals(2, result.children.size, "Root should have 2 H1 chapters as direct children")

            // Find Chapter 1
            val chapter1 = result.children.find { it.title == "Chapter 1" }
            assertNotNull(chapter1, "Chapter 1 should exist")
            assertTrue(chapter1 is NavigableContainerSection, "Chapter 1 should be a ContainerSection")

            if (chapter1 is NavigableContainerSection) {
                // Chapter 1 should have 3 children (preamble + Section 1.1 and Section 1.2)
                assertEquals(
                    3,
                    chapter1.children.toList().size,
                    "Chapter 1 should have preamble + 2 H2 sections as children"
                )

                // Find Section 1.1
                val section11 = chapter1.children.find { it.title == "Section 1.1" }
                assertNotNull(section11, "Section 1.1 should exist")
                assertTrue(section11 is NavigableContainerSection, "Section 1.1 should be a ContainerSection")

                if (section11 is NavigableContainerSection) {
                    // Section 1.1 should have 2 children (preamble + Subsection 1.1.1)
                    assertEquals(
                        2,
                        section11.children.toList().size,
                        "Section 1.1 should have preamble + 1 H3 subsection as children"
                    )

                    val subsection111 = section11.children.find { it.title == "Subsection 1.1.1" }
                    assertNotNull(subsection111, "Subsection 1.1.1 should exist")
                    assertTrue(subsection111 is LeafSection, "Subsection 1.1.1 should be a LeafSection")
                }

                // Section 1.2 should be a leaf
                val section12 = chapter1.children.find { it.title == "Section 1.2" }
                assertNotNull(section12, "Section 1.2 should exist")
                assertTrue(section12 is LeafSection, "Section 1.2 should be a LeafSection")
            }

            // Chapter 2 should be a leaf
            val chapter2 = result.children.find { it.title == "Chapter 2" }
            assertNotNull(chapter2, "Chapter 2 should exist")
            assertTrue(chapter2 is LeafSection, "Chapter 2 should be a LeafSection")
        }

        @Test
        fun `test markdown with only top-level headings`() {
            val markdown = """
            # Section 1
            Content 1

            # Section 2
            Content 2

            # Section 3
            Content 3
        """.trimIndent()

            val inputStream = ByteArrayInputStream(markdown.toByteArray())
            val metadata = Metadata().apply {
                set(TikaCoreProperties.RESOURCE_NAME_KEY, "flat.md")
            }

            val result = reader.parseContent(inputStream, metadata = metadata, uri = "test://flat.md")

            // All top-level sections should be direct children and leaves
            assertEquals(3, result.children.size)
            result.children.forEach { section ->
                assertTrue(section is LeafSection, "${section.title} should be a LeafSection")
            }
        }

        @Test
        fun `test markdown hierarchy with leaves method`() {
            val markdown = """
            # Top
            Top content

            ## Mid1
            Mid1 content

            ### Deep1
            Deep1 content

            ## Mid2
            Mid2 content
        """.trimIndent()

            val inputStream = ByteArrayInputStream(markdown.toByteArray())
            val metadata = Metadata().apply {
                set(TikaCoreProperties.RESOURCE_NAME_KEY, "test.md")
            }

            val result = reader.parseContent(inputStream, metadata = metadata, uri = "test://test.md")

            // Test leaves() method (includes preambles)
            val allLeaves = result.leaves().toList()
            // Top preamble, Mid1 preamble, Deep1 leaf, Mid2 leaf = 4
            assertEquals(4, allLeaves.size, "Should have 4 leaves (Top preamble, Mid1 preamble, Deep1, and Mid2)")

            val leafTitles = allLeaves.map { it.title }
            assertTrue(leafTitles.contains("Deep1"))
            assertTrue(leafTitles.contains("Mid2"))
        }
    }

    @Nested
    inner class MarkdownCodeFenceTests {

        private fun parseMarkdown(markdown: String) =
            reader.parseContent(
                ByteArrayInputStream(markdown.toByteArray()),
                metadata = Metadata().apply { set(TikaCoreProperties.RESOURCE_NAME_KEY, "fences.md") },
                uri = "test://fences.md",
            )

        @Test
        fun `a hash comment inside a backtick fence is content, not a heading`() {
            val result = parseMarkdown(
                """
                ## Running the examples

                ```bash
                # Clone and run examples
                git clone https://github.com/embabel/embabel-agent-examples
                ```
                """.trimIndent()
            )

            val leaves = result.leaves().toList()
            assertEquals(listOf("Running the examples"), leaves.map { it.title })
            assertEquals(
                """
                ```bash
                # Clone and run examples
                git clone https://github.com/embabel/embabel-agent-examples
                ```
                """.trimIndent(),
                leaves.single().content,
            )
        }

        @Test
        fun `a hash comment inside a tilde fence is content, not a heading`() {
            val result = parseMarkdown(
                """
                ## Configuration

                ~~~yaml
                # The model to use
                model: gpt
                ~~~
                """.trimIndent()
            )

            val leaf = result.leaves().single()
            assertEquals("Configuration", leaf.title)
            assertTrue(leaf.content.contains("# The model to use"))
            assertTrue(leaf.content.endsWith("~~~"))
        }

        @Test
        fun `a heading after a closed fence starts a new section`() {
            val result = parseMarkdown(
                """
                ## First

                ```
                # not a heading
                ```

                ## Second
                Second content.
                """.trimIndent()
            )

            assertEquals(listOf("First", "Second"), result.leaves().map { it.title })
        }

        @Test
        fun `a shorter fence inside a four-backtick fence does not close it`() {
            val result = parseMarkdown(
                """
                ## Fences

                ````markdown
                ```bash
                # inside the inner example
                ```
                # still inside the outer fence
                ````

                ## After
                After content.
                """.trimIndent()
            )

            val leaves = result.leaves().toList()
            assertEquals(listOf("Fences", "After"), leaves.map { it.title })
            assertTrue(leaves[0].content.contains("# inside the inner example"))
            assertTrue(leaves[0].content.contains("# still inside the outer fence"))
            assertTrue(leaves[0].content.endsWith("````"))
        }

        @Test
        fun `a tilde line does not close a backtick fence`() {
            val result = parseMarkdown(
                """
                ## Mixed

                ```
                ~~~
                # still code
                ```
                """.trimIndent()
            )

            val leaf = result.leaves().single()
            assertEquals("Mixed", leaf.title)
            assertTrue(leaf.content.contains("# still code"))
        }

        @Test
        fun `a fence line with text after it does not close the fence`() {
            val result = parseMarkdown(
                """
                ## Example

                ```
                ```bash
                # still code
                ```
                """.trimIndent()
            )

            val leaf = result.leaves().single()
            assertEquals("Example", leaf.title)
            assertTrue(leaf.content.contains("# still code"))
        }

        @Test
        fun `three backticks with a backtick later on the line are not a fence`() {
            val result = parseMarkdown(
                """
                ## Inline
                ```inline code```

                ## Next
                Next content.
                """.trimIndent()
            )

            assertEquals(listOf("Inline", "Next"), result.leaves().map { it.title })
        }

        @Test
        fun `a fence that is never closed makes the rest of the document code`() {
            val result = parseMarkdown(
                """
                ## Unclosed

                ```bash
                # comment

                ## also code
                """.trimIndent()
            )

            val leaf = result.leaves().single()
            assertEquals("Unclosed", leaf.title)
            assertTrue(leaf.content.contains("## also code"))
        }

        @Test
        fun `a fence indented by up to three spaces opens and closes a code block`() {
            val result = parseMarkdown(
                """
                ## Indented

                   ```bash
                # comment
                   ```

                ## Next
                Next content.
                """.trimIndent()
            )

            val leaves = result.leaves().toList()
            assertEquals(listOf("Indented", "Next"), leaves.map { it.title })
            assertTrue(leaves[0].content.contains("# comment"))
        }

        @Test
        fun `backticks indented by four spaces are not a fence`() {
            val result = parseMarkdown(
                """
                ## Indented code

                    ```
                ## Next
                Next content.
                """.trimIndent()
            )

            assertEquals(listOf("Indented code", "Next"), result.leaves().map { it.title })
        }

        @Test
        fun `a closing fence longer than the opening fence closes it`() {
            val result = parseMarkdown(
                """
                ## Longer

                ```
                # comment
                `````

                ## Next
                Next content.
                """.trimIndent()
            )

            assertEquals(listOf("Longer", "Next"), result.leaves().map { it.title })
        }

        @Test
        fun `a closing fence followed by spaces closes the fence`() {
            val result = parseMarkdown(
                "## Trailing\n\n```\n# comment\n```   \n\n## Next\nNext content."
            )

            assertEquals(listOf("Trailing", "Next"), result.leaves().map { it.title })
        }

        @Test
        fun `a tilde fence can have a backtick in its info text`() {
            val result = parseMarkdown(
                """
                ## Tilde

                ~~~ `info`
                # comment
                ~~~
                """.trimIndent()
            )

            val leaf = result.leaves().single()
            assertEquals("Tilde", leaf.title)
            assertTrue(leaf.content.contains("# comment"))
        }

        @Test
        fun `a document whose only hash lines are inside a fence is one section with the whole content`() {
            val markdown = """
                Install the CLI.

                ```bash
                # Install
                brew install embabel
                ```
                """.trimIndent()

            val result = parseMarkdown(markdown)

            val leaf = result.leaves().single()
            assertEquals("Install the CLI.", leaf.title)
            assertEquals(markdown, leaf.content)
        }
    }

    @Nested
    inner class MarkdownHeadingIdTests {

        private fun parseMarkdown(markdown: String, uri: String = "https://docs.example.com/guide") =
            reader.parseContent(
                ByteArrayInputStream(markdown.toByteArray()),
                metadata = Metadata().apply { set(TikaCoreProperties.RESOURCE_NAME_KEY, "guide.md") },
                uri = uri,
            )

        @Test
        fun `a heading with an id gives the title without the id and the document URI with the id as fragment`() {
            val result = parseMarkdown(
                """
                ## ToolishRag lifecycle {#toolishrag-lifecycle}
                Lifecycle content.
                """.trimIndent()
            )

            val leaf = result.leaves().single()
            assertEquals("ToolishRag lifecycle", leaf.title)
            assertEquals("https://docs.example.com/guide#toolishrag-lifecycle", leaf.uri)
            assertEquals("Lifecycle content.", leaf.content)
        }

        @Test
        fun `a heading without an id keeps the document URI`() {
            val result = parseMarkdown(
                """
                ## Anchored {#anchored}
                Anchored content.

                ## Plain
                Plain content.
                """.trimIndent()
            )

            val plain = result.leaves().single { it.title == "Plain" }
            assertEquals("https://docs.example.com/guide", plain.uri)
        }

        @Test
        fun `a fragment already on the document URI is replaced by the heading id`() {
            val result = parseMarkdown(
                "## Section {#section}\nContent.",
                uri = "https://docs.example.com/guide#top",
            )

            assertEquals("https://docs.example.com/guide#section", result.leaves().single().uri)
        }

        @Test
        fun `whitespace around and inside the id attribute is ignored`() {
            val result = parseMarkdown(
                """
                ##   Spaced title   {#spaced}
                Content.

                ## Inner spaces { #inner }
                Content.
                """.trimIndent()
            )

            val leaves = result.leaves().toList()
            assertEquals(listOf("Spaced title", "Inner spaces"), leaves.map { it.title })
            assertEquals(
                listOf("https://docs.example.com/guide#spaced", "https://docs.example.com/guide#inner"),
                leaves.map { it.uri },
            )
        }

        @Test
        fun `braces in a title that are not an id attribute stay in the title`() {
            val result = parseMarkdown(
                """
                ## Map<String, {x}>
                Content.
                """.trimIndent()
            )

            val leaf = result.leaves().single()
            assertEquals("Map<String, {x}>", leaf.title)
            assertEquals("https://docs.example.com/guide", leaf.uri)
        }

        @Test
        fun `an id attribute with no title before it stays in the title`() {
            val result = parseMarkdown(
                """
                ## {#only-id}
                Content.
                """.trimIndent()
            )

            val leaf = result.leaves().single()
            assertEquals("{#only-id}", leaf.title)
            assertEquals("https://docs.example.com/guide", leaf.uri)
        }

        @Test
        fun `a container section and its preamble have the URI of their heading id`() {
            val result = parseMarkdown(
                """
                ## Parent {#parent}
                Parent content.

                ### Child {#child}
                Child content.
                """.trimIndent()
            )

            val parent = result.children.single()
            assertTrue(parent is NavigableContainerSection)
            assertEquals("https://docs.example.com/guide#parent", parent.uri)
            val leaves = result.leaves().toList()
            assertEquals(
                listOf("https://docs.example.com/guide#parent", "https://docs.example.com/guide#child"),
                leaves.map { it.uri },
            )
        }

        @Test
        fun `the document title does not include the id of the first heading`() {
            val result = parseMarkdown(
                """
                # Guide {#guide}
                Content.
                """.trimIndent()
            )

            assertEquals("Guide", result.title)
        }

        @Test
        fun `a title from the metadata is kept as it is, even when it ends with an id attribute`() {
            val result = reader.parseContent(
                ByteArrayInputStream("# Heading {#heading}\nContent.".toByteArray()),
                metadata = Metadata().apply {
                    set(TikaCoreProperties.RESOURCE_NAME_KEY, "guide.md")
                    set(TikaCoreProperties.TITLE, "Metadata title {#kept}")
                },
                uri = "https://docs.example.com/guide",
            )

            assertEquals("Metadata title {#kept}", result.title)
        }

        @Test
        fun `an id inside a fenced code block is not read`() {
            val result = parseMarkdown(
                """
                ## Example {#example}

                ```markdown
                ## Inner {#inner}
                ```
                """.trimIndent()
            )

            val leaf = result.leaves().single()
            assertEquals("https://docs.example.com/guide#example", leaf.uri)
            assertTrue(leaf.content.contains("## Inner {#inner}"))
        }

        @Test
        fun `chunks carry the section URLs with the heading ids`() {
            val result = parseMarkdown(
                """
                # Guide

                ## Lifecycle {#lifecycle}

                ### Start {#start}
                Start content.

                ### Stop {#stop}
                Stop content.
                """.trimIndent()
            )
            val lifecycle = result.children.single().children.single() as NavigableContainerSection

            // The default maximum chunk size puts the whole container in one chunk
            val containerChunk = InMemoryContentChunker().chunk(lifecycle).single()
            assertEquals(
                "https://docs.example.com/guide#lifecycle",
                containerChunk.metadata[ContentChunker.CONTAINER_SECTION_URL],
            )

            // A small maximum chunk size puts each leaf in a chunk of its own
            val leafChunks = InMemoryContentChunker(ContentChunker.Config(maxChunkSize = 30, overlapSize = 0))
                .chunk(lifecycle)
            assertEquals(
                listOf("https://docs.example.com/guide#start", "https://docs.example.com/guide#stop"),
                leafChunks.map { it.metadata[ContentChunker.LEAF_SECTION_URL] },
            )
        }
    }

    @Nested
    inner class HtmlParsingTests {

        @Test
        fun `test HTML autodetection without type hint`() {
            val html = """
            <html>
            <head><title>Autodetected HTML</title></head>
            <body>
                <h1>First Heading</h1>
                <p>Content under first heading.</p>
                <h2>Second Heading</h2>
                <p>Content under second heading.</p>
            </body>
            </html>
        """.trimIndent()

            val inputStream = ByteArrayInputStream(html.toByteArray())
            // No CONTENT_TYPE_HINT set - should autodetect as HTML
            val metadata = Metadata()

            val result = reader.parseContent(inputStream, metadata = metadata, uri = "test://autodetect.html")

            // Should detect HTML and parse headings hierarchically (including preambles)
            val allDescendants = result.descendants()
            // First Heading container + preamble + Second Heading leaf = 3
            assertEquals(3, allDescendants.count())
            val titles = allDescendants.map { it.title }
            assertTrue(titles.contains("First Heading"))
            assertTrue(titles.contains("Second Heading"))
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

            val result = reader.parseContent(inputStream, metadata = metadata, uri = "Test")

            // HTML headings should create hierarchical sections (including preambles)
            val allDescendants = result.descendants()
            // Main Heading container + preamble + Second Heading leaf = 3
            assertEquals(3, allDescendants.count())

            val titles = allDescendants.map { it.title }
            assertTrue(titles.contains("Main Heading"))
            assertTrue(titles.contains("Second Heading"))

            // Content should be cleaned HTML (no tags)
            result.leaves().forEach { leaf ->
                assertNotNull(leaf.title)
                assertFalse(leaf.content.contains("<"))
                assertFalse(leaf.content.contains(">"))
            }
        }

        @Test
        fun `test HTML with headings creates separate sections`() {
            val html = """
            <html>
            <head><title>HTML Document</title></head>
            <body>
                <h1>Main Heading</h1>
                <p>This is the introduction with <strong>bold</strong> text.</p>

                <h2>Second Heading</h2>
                <p>Content for section 2.</p>

                <h3>Sub Heading</h3>
                <p>Content for subsection.</p>

                <h2>Third Heading</h2>
                <p>More content here.</p>
            </body>
            </html>
        """.trimIndent()

            val inputStream = ByteArrayInputStream(html.toByteArray())
            val metadata = Metadata().apply {
                set(TikaCoreProperties.CONTENT_TYPE_HINT, "text/html")
            }

            val result = reader.parseContent(inputStream, metadata = metadata, uri = "test://example.html")

            // HTML headings should create hierarchical sections like markdown (including preambles)
            val allDescendants = result.descendants()
            // Main Heading container + preamble, Second Heading container + preamble, Sub Heading leaf, Third Heading leaf = 6
            assertEquals(6, allDescendants.count())

            val titles = allDescendants.map { it.title }
            assertTrue(titles.contains("Main Heading"))
            assertTrue(titles.contains("Second Heading"))
            assertTrue(titles.contains("Sub Heading"))
            assertTrue(titles.contains("Third Heading"))

            // Verify content is properly extracted without HTML tags
            result.leaves().forEach { leaf ->
                assertFalse(leaf.content.contains("<"))
                assertFalse(leaf.content.contains(">"))
                assertTrue(leaf.content.isNotBlank())
            }
        }

        @Test
        fun `test parseUrl with HTML headings`() {
            val html = """
            <html>
            <head><title>Test Page</title></head>
            <body>
                <h1>Introduction</h1>
                <p>Welcome to the page.</p>

                <h2>Features</h2>
                <ul>
                    <li>Feature 1</li>
                    <li>Feature 2</li>
                </ul>

                <h2>Conclusion</h2>
                <p>Thank you for reading.</p>
            </body>
            </html>
        """.trimIndent()

            // Create a temporary file to test parseUrl
            val tempFile = Files.createTempFile("test", ".html")
            Files.writeString(tempFile, html)

            val fileUrl = "file:${tempFile.toAbsolutePath()}"
            val result = reader.parseUrl(fileUrl)

            // Verify URI is correctly set on document
            assertEquals(fileUrl, result.uri, "Document URI should match the input file URL")

            val allDescendants = result.descendants()
            // Introduction container + preamble + Features leaf + Conclusion leaf = 4
            assertEquals(4, allDescendants.count())
            val titles = allDescendants.map { it.title }
            assertTrue(titles.contains("Introduction"))
            assertTrue(titles.contains("Features"))
            assertTrue(titles.contains("Conclusion"))

            // Verify all descendant sections also have the correct URI
            allDescendants.forEach { section ->
                assertEquals(fileUrl, section.uri, "Section '${section.title}' should have URI matching input file URL")
            }

            Files.deleteIfExists(tempFile)
        }

        @Test
        fun `test parseUrl preserves exact URI including custom schemes`() {
            val html = """
            <html>
            <body>
                <h1>Test</h1>
                <p>Content</p>
            </body>
            </html>
            """.trimIndent()

            // Test with file URL
            val tempFile = Files.createTempFile("test", ".html")
            Files.writeString(tempFile, html)
            val fileUrl = "file:${tempFile.toAbsolutePath()}"
            val fileResult = reader.parseUrl(fileUrl)
            assertEquals(fileUrl, fileResult.uri, "Document URI should exactly match input file URL")
            fileResult.descendants().forEach { section ->
                assertEquals(fileUrl, section.uri, "All sections should have matching URI")
            }
            Files.deleteIfExists(tempFile)

            // Test with classpath resource
            val classpathUrl = "classpath:application-test.properties"
            val classpathResult = reader.parseUrl(classpathUrl)
            assertEquals(classpathUrl, classpathResult.uri, "Document URI should exactly match input classpath URL")

            // Test with HTTP URL simulation via ByteArrayInputStream
            val httpUrl = "https://example.com/document.html"
            val inputStream = ByteArrayInputStream(html.toByteArray())
            val httpResult = reader.parseContent(inputStream, httpUrl)
            assertEquals(httpUrl, httpResult.uri, "Document URI should exactly match input HTTP URL")
            httpResult.descendants().forEach { section ->
                assertEquals(httpUrl, section.uri, "All sections should have matching URI")
            }
        }

        @Test
        fun `test HTML builds proper hierarchical structure not flat list`() {
            val html = """
            <html>
            <body>
                <h1>Chapter 1</h1>
                <p>Chapter 1 introduction</p>

                <h2>Section 1.1</h2>
                <p>Content for section 1.1</p>

                <h3>Subsection 1.1.1</h3>
                <p>Content for subsection 1.1.1</p>

                <h2>Section 1.2</h2>
                <p>Content for section 1.2</p>

                <h1>Chapter 2</h1>
                <p>Chapter 2 introduction</p>
            </body>
            </html>
        """.trimIndent()

            val inputStream = ByteArrayInputStream(html.toByteArray())
            val metadata = Metadata().apply {
                set(TikaCoreProperties.CONTENT_TYPE_HINT, "text/html")
            }

            val result = reader.parseContent(inputStream, metadata = metadata, uri = "test://hierarchy.html")

            // Root document should have 2 direct children (Chapter 1 and Chapter 2)
            assertEquals(2, result.children.size, "Root should have 2 H1 chapters as direct children")

            // Find Chapter 1
            val chapter1 = result.children.find { it.title == "Chapter 1" }
            assertNotNull(chapter1, "Chapter 1 should exist")
            assertTrue(chapter1 is NavigableContainerSection, "Chapter 1 should be a ContainerSection")

            if (chapter1 is NavigableContainerSection) {
                // Chapter 1 should have 3 children (preamble + Section 1.1 and Section 1.2)
                assertEquals(
                    3,
                    chapter1.children.toList().size,
                    "Chapter 1 should have preamble + 2 H2 sections as children"
                )

                // Find Section 1.1
                val section11 = chapter1.children.find { it.title == "Section 1.1" }
                assertNotNull(section11, "Section 1.1 should exist")
                assertTrue(section11 is NavigableContainerSection, "Section 1.1 should be a ContainerSection")

                if (section11 is NavigableContainerSection) {
                    // Section 1.1 should have 2 children (preamble + Subsection 1.1.1)
                    assertEquals(
                        2,
                        section11.children.toList().size,
                        "Section 1.1 should have preamble + 1 H3 subsection as children"
                    )

                    val subsection111 = section11.children.find { it.title == "Subsection 1.1.1" }
                    assertNotNull(subsection111, "Subsection 1.1.1 should exist")
                    assertTrue(subsection111 is LeafSection, "Subsection 1.1.1 should be a LeafSection")
                }

                // Section 1.2 should be a leaf
                val section12 = chapter1.children.find { it.title == "Section 1.2" }
                assertNotNull(section12, "Section 1.2 should exist")
                assertTrue(section12 is LeafSection, "Section 1.2 should be a LeafSection")
            }

            // Chapter 2 should be a leaf
            val chapter2 = result.children.find { it.title == "Chapter 2" }
            assertNotNull(chapter2, "Chapter 2 should exist")
            assertTrue(chapter2 is LeafSection, "Chapter 2 should be a LeafSection")
        }

        @Test
        fun `test HTML hierarchy with descendants and leaves`() {
            val html = """
            <html>
            <body>
                <h1>Top Level</h1>
                <p>Top content</p>

                <h2>Mid Level</h2>
                <p>Mid content</p>

                <h3>Deep Level</h3>
                <p>Deep content</p>
            </body>
            </html>
        """.trimIndent()

            val inputStream = ByteArrayInputStream(html.toByteArray())
            val metadata = Metadata().apply {
                set(TikaCoreProperties.CONTENT_TYPE_HINT, "text/html")
            }

            val result = reader.parseContent(inputStream, metadata = metadata, uri = "test://deep.html")

            // Test descendants() method (including preambles)
            val allDescendants = result.descendants()
            // Top Level container + preamble, Mid Level container + preamble, Deep Level leaf = 5
            assertEquals(5, allDescendants.count(), "Should have 5 total descendants")

            // Test leaves() method (includes preambles)
            val allLeaves = result.leaves().toList()
            // Top preamble, Mid preamble, Deep leaf = 3
            assertEquals(3, allLeaves.size, "Should have 3 leaves (Top preamble, Mid preamble, Deep Level)")
            assertTrue(allLeaves.any { it.title == "Deep Level" })
        }
    }

    @Nested
    inner class PlainTextParsingTests {

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

            val result = reader.parseContent(inputStream, "test://plain.txt", metadata)

            assertEquals(1, result.children.toList().size)
            assertEquals("test://plain.txt", result.uri)
            assertNotNull(result.id)

            val section = result.children.first() as LeafSection
            assertEquals("This is a simple text document.", section.title)
            assertEquals("test://plain.txt", section.uri)
            assertEquals(text, section.content)
            assertNotNull(section.id)
        }

        @Test
        fun `test title extraction from metadata`() {
            val content = "Some content without markdown headers"

            val inputStream = ByteArrayInputStream(content.toByteArray())
            val metadata = Metadata().apply {
                set(TikaCoreProperties.TITLE, "Custom Title from Metadata")
            }

            val result = reader.parseContent(inputStream, "x", metadata)

            assertEquals(1, result.children.toList().size)
            assertEquals("Custom Title from Metadata", (result.children.first() as LeafSection).title)
        }

        @Test
        fun `test title extraction from first line when no headers`() {
            val content = """
            This should become the title
            And this is the rest of the content.
        """.trimIndent()

            val inputStream = ByteArrayInputStream(content.toByteArray())

            val result = reader.parseContent(inputStream, uri = "foo")

            assertEquals(1, result.children.toList().size)
            assertEquals("This should become the title", (result.children.first() as LeafSection).title)
        }

        @Test
        fun `test error handling for empty content`() {
            // Create an input stream with empty content
            val inputStream = ByteArrayInputStream(ByteArray(0))

            val result = reader.parseContent(inputStream, "uri")

            // Should return empty content root for empty content
            assertTrue(result.children.toList().isEmpty())
            assertNotNull(result.id)
        }
    }

    @Nested
    inner class MetadataTests {

        @Test
        fun `test metadata preservation`() {
            val content = "Simple content"

            val inputStream = ByteArrayInputStream(content.toByteArray())
            val metadata = Metadata().apply {
                set(TikaCoreProperties.TITLE, "Test Title")
                set(TikaCoreProperties.CREATOR, "Test Author")
                set("custom-field", "custom-value")
            }

            val result = reader.parseContent(inputStream, "uri", metadata)

            assertEquals(1, result.children.toList().size)
            val section = result.children.first() as LeafSection
            val resultMetadata = section.metadata
            assertEquals("Test Author", resultMetadata[TikaCoreProperties.CREATOR.name])
            assertEquals("custom-value", resultMetadata["custom-field"])
        }

        @Test
        fun `test all leaf sections have required metadata for pathFromRoot`() {
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
            }

            val result = reader.parseContent(inputStream, "test://example.md", metadata)

            // Verify all leaf sections have the required metadata
            result.leaves().forEach { leafSection ->
                assertNotNull(
                    leafSection.metadata["root_document_id"],
                    "Section '${leafSection.title}' is missing root_document_id"
                )
                assertNotNull(
                    leafSection.metadata["container_section_id"],
                    "Section '${leafSection.title}' is missing container_section_id"
                )
                assertNotNull(
                    leafSection.metadata["leaf_section_id"],
                    "Section '${leafSection.title}' is missing leaf_section_id"
                )

                // Verify the values are correct
                assertEquals(result.id, leafSection.metadata["root_document_id"])
                // Note: leaf_section_id should match the leaf's own id, which might include _preamble suffix
                assertEquals(leafSection.id, leafSection.metadata["leaf_section_id"])
            }
        }

        @Test
        fun `test plain text leaf section has required metadata for pathFromRoot`() {
            val text = "This is a simple text document."

            val inputStream = ByteArrayInputStream(text.toByteArray())
            val result = reader.parseContent(inputStream, "test://plain.txt")

            assertEquals(1, result.children.toList().size)
            val leafSection = result.children.first() as LeafSection

            // Verify required metadata is present
            assertNotNull(leafSection.metadata["root_document_id"])
            assertNotNull(leafSection.metadata["container_section_id"])
            assertNotNull(leafSection.metadata["leaf_section_id"])

            // Verify values
            assertEquals(result.id, leafSection.metadata["root_document_id"])
            assertEquals(
                result.id,
                leafSection.metadata["container_section_id"]
            ) // For single section, container is root
            assertEquals(leafSection.id, leafSection.metadata["leaf_section_id"])
        }
    }

    @Nested
    inner class DirectoryParsingTests {

        @Test
        fun `test parseFromDirectory with mixed file types`(@TempDir tempDir: Path) {
            // Create test files
            val mdFile = tempDir.resolve("document.md")
            Files.writeString(
                mdFile,
                """
            # Test Document
            This is a test document.

            ## Section 1
            Content of section 1.
        """.trimIndent(), StandardOpenOption.CREATE
            )

            val txtFile = tempDir.resolve("readme.txt")
            Files.writeString(txtFile, "This is a simple text file.", StandardOpenOption.CREATE)

            val subdirPath = tempDir.resolve("subdir")
            Files.createDirectory(subdirPath)
            val subFile = subdirPath.resolve("sub.md")
            Files.writeString(
                subFile,
                """
            # Sub Document
            Content in subdirectory.
        """.trimIndent(), StandardOpenOption.CREATE
            )

            // Mock FileReadTools
            val fileTools = mockk<FileReadTools>()
            every { fileTools.listFiles("") } returns listOf("f:document.md", "f:readme.txt", "d:subdir")
            every { fileTools.listFiles("subdir") } returns listOf("f:sub.md")
            every { fileTools.resolvePath("document.md") } returns mdFile
            every { fileTools.resolvePath("readme.txt") } returns txtFile
            every { fileTools.resolvePath("subdir/sub.md") } returns subFile
            every { fileTools.safeReadFile("document.md") } returns Files.readString(mdFile)
            every { fileTools.safeReadFile("readme.txt") } returns Files.readString(txtFile)
            every { fileTools.safeReadFile("subdir/sub.md") } returns Files.readString(subFile)

            val config = DirectoryParsingConfig(
                includedExtensions = setOf("md", "txt"),
                maxFileSize = 1024 * 1024
            )

            val result = reader.parseFromDirectory(fileTools, config)

            assertTrue(result.success)
            assertEquals(3, result.totalFilesFound)
            assertEquals(3, result.filesProcessed)
            assertEquals(0, result.filesSkipped)
            assertEquals(0, result.filesErrored)
            assertEquals(3, result.contentRoots.size)
            assertTrue(result.errors.isEmpty())

            // Verify parsed content
            val documentRoot = result.contentRoots.find { it.title == "Test Document" }
            assertNotNull(documentRoot)
            // Test Document has preamble + Section 1 leaf = 2 leaves
            assertEquals(2, documentRoot!!.leaves().count())

            val readmeRoot = result.contentRoots.find { it.title == "This is a simple text file." }
            assertNotNull(readmeRoot)
            assertEquals(1, readmeRoot!!.leaves().count()) // 1 section in readme.txt

            val subRoot = result.contentRoots.find { it.title == "Sub Document" }
            assertNotNull(subRoot)
            assertEquals(1, subRoot!!.leaves().count()) // 1 section in sub.md
        }

        @Test
        fun `test parseFromDirectory with file size limits`(@TempDir tempDir: Path) {
            val smallFile = tempDir.resolve("small.md")
            Files.writeString(smallFile, "# Small\nSmall content", StandardOpenOption.CREATE)

            val largeFile = tempDir.resolve("large.md")
            Files.writeString(largeFile, "# Large\n" + "X".repeat(2000), StandardOpenOption.CREATE)

            val fileTools = mockk<FileReadTools>()
            every { fileTools.listFiles("") } returns listOf("f:small.md", "f:large.md")
            every { fileTools.resolvePath("small.md") } returns smallFile
            every { fileTools.resolvePath("large.md") } returns largeFile
            every { fileTools.safeReadFile("small.md") } returns Files.readString(smallFile)

            val config = DirectoryParsingConfig(
                includedExtensions = setOf("md"),
                maxFileSize = 100 // Very small limit to exclude large file
            )

            val result = reader.parseFromDirectory(fileTools, config)

            assertTrue(result.success)
            assertEquals(1, result.totalFilesFound) // Only small file should be discovered
            assertEquals(1, result.filesProcessed)
            assertEquals(0, result.filesSkipped)
            assertEquals(0, result.filesErrored)
            assertEquals(1, result.contentRoots.size)
        }

        @Test
        fun `test parseFromDirectory with excluded directories`(@TempDir tempDir: Path) {
            val normalFile = tempDir.resolve("normal.md")
            Files.writeString(normalFile, "# Normal\nNormal content", StandardOpenOption.CREATE)

            val gitDir = tempDir.resolve(".git")
            Files.createDirectory(gitDir)
            val gitFile = gitDir.resolve("config")
            Files.writeString(gitFile, "git config content", StandardOpenOption.CREATE)

            val fileTools = mockk<FileReadTools>()
            every { fileTools.listFiles("") } returns listOf("f:normal.md", "d:.git")
            every { fileTools.resolvePath("normal.md") } returns normalFile
            every { fileTools.safeReadFile("normal.md") } returns Files.readString(normalFile)

            val config = DirectoryParsingConfig(
                includedExtensions = setOf("md"),
                excludedDirectories = setOf(".git")
            )

            val result = reader.parseFromDirectory(fileTools, config)

            assertTrue(result.success)
            assertEquals(1, result.totalFilesFound) // Only normal.md should be found
            assertEquals(1, result.filesProcessed)
            assertEquals(1, result.contentRoots.size)

            val contentRoot = result.contentRoots.first()
            assertEquals("Normal", contentRoot.title)
        }

        @Test
        fun `test parseFromDirectory handles file read errors gracefully`(@TempDir tempDir: Path) {
            // Create a real file first so it passes the file size validation
            val errorFile = tempDir.resolve("error.md")
            Files.writeString(errorFile, "# Error\nSome content", StandardOpenOption.CREATE)

            val fileTools = mockk<FileReadTools>()
            every { fileTools.listFiles("") } returns listOf("f:error.md")
            every { fileTools.resolvePath("error.md") } returns errorFile
            every { fileTools.safeReadFile("error.md") } returns null // Simulate read error

            val result = reader.parseFromDirectory(fileTools, DirectoryParsingConfig())

            assertTrue(result.success) // Should still be successful overall
            assertEquals(1, result.totalFilesFound)
            assertEquals(0, result.filesProcessed)
            assertEquals(1, result.filesSkipped) // File should be skipped due to read error
            assertEquals(0, result.filesErrored)
            assertEquals(0, result.contentRoots.size)
        }

        @Test
        fun `test parseFromDirectory with custom extensions`(@TempDir tempDir: Path) {
            val kotlinFile = tempDir.resolve("Example.kt")
            Files.writeString(
                kotlinFile,
                """
            /**
             * Example Kotlin class
             */
            class Example {
                fun doSomething() = "Hello"
            }
        """.trimIndent(), StandardOpenOption.CREATE
            )

            val javaFile = tempDir.resolve("Main.java")
            Files.writeString(
                javaFile,
                """
            public class Main {
                public static void main(String[] args) {
                    System.out.println("Hello World");
                }
            }
        """.trimIndent(), StandardOpenOption.CREATE
            )

            val ignoredFile = tempDir.resolve("data.csv")
            Files.writeString(ignoredFile, "name,value\ntest,123", StandardOpenOption.CREATE)

            val fileTools = mockk<FileReadTools>()
            every { fileTools.listFiles("") } returns listOf("f:Example.kt", "f:Main.java", "f:data.csv")
            every { fileTools.resolvePath("Example.kt") } returns kotlinFile
            every { fileTools.resolvePath("Main.java") } returns javaFile
            every { fileTools.resolvePath("data.csv") } returns Path.of("data.csv")
            every { fileTools.safeReadFile("Example.kt") } returns Files.readString(kotlinFile)
            every { fileTools.safeReadFile("Main.java") } returns Files.readString(javaFile)

            val config = DirectoryParsingConfig(
                includedExtensions = setOf("kt", "java"), // Only include code files
                maxFileSize = 1024 * 1024
            )

            val result = reader.parseFromDirectory(fileTools, config)

            assertTrue(result.success)
            assertEquals(2, result.totalFilesFound) // Only kt and java files
            assertEquals(2, result.filesProcessed)
            assertEquals(2, result.contentRoots.size)

            val titles = result.contentRoots.map { it.title }
            // Since both are plain text files without markdown headers,
            // they will use the first line as title (truncated to 50 chars)
            assertTrue(titles.any { it.contains("/**") || it.contains("Example") }) // Kotlin file
            assertTrue(titles.any { it.contains("public class") || it.contains("Main") }) // Java file
        }

        @Test
        fun `test parseFromDirectory with max depth limit`(@TempDir tempDir: Path) {
            // Create nested directory structure
            val level1 = tempDir.resolve("level1")
            Files.createDirectory(level1)
            val level2 = level1.resolve("level2")
            Files.createDirectory(level2)

            val rootFile = tempDir.resolve("root.md")
            Files.writeString(rootFile, "# Root\nRoot content", StandardOpenOption.CREATE)

            val level1File = level1.resolve("level1.md")
            Files.writeString(level1File, "# Level1\nLevel1 content", StandardOpenOption.CREATE)

            val level2File = level2.resolve("level2.md")
            Files.writeString(level2File, "# Level2\nLevel2 content", StandardOpenOption.CREATE)

            val fileTools = mockk<FileReadTools>()
            every { fileTools.listFiles("") } returns listOf("f:root.md", "d:level1")
            every { fileTools.listFiles("level1") } returns listOf("f:level1.md", "d:level2")
            every { fileTools.resolvePath("root.md") } returns rootFile
            every { fileTools.resolvePath("level1/level1.md") } returns level1File
            every { fileTools.safeReadFile("root.md") } returns Files.readString(rootFile)
            every { fileTools.safeReadFile("level1/level1.md") } returns Files.readString(level1File)

            val config = DirectoryParsingConfig(
                includedExtensions = setOf("md"),
                maxDepth = 1 // Should stop at level1, not go to level2
            )

            val result = reader.parseFromDirectory(fileTools, config)

            assertTrue(result.success)
            assertEquals(2, result.totalFilesFound) // Only root.md and level1.md
            assertEquals(2, result.filesProcessed)
            assertEquals(2, result.contentRoots.size)

            val titles = result.contentRoots.map { it.title }
            assertTrue(titles.contains("Root"))
            assertTrue(titles.contains("Level1"))
            assertFalse(titles.contains("Level2")) // Should not be included due to depth limit
        }

        @Test
        fun `test all leaf sections from directory parsing have required metadata`(@TempDir tempDir: Path) {
            val mdFile = tempDir.resolve("test.md")
            val markdown = """
            # Test Document
            This is a test document.

            ## First Section
            Content of the first section.
        """.trimIndent()
            Files.writeString(mdFile, markdown, StandardOpenOption.CREATE)

            val fileTools = mockk<FileReadTools>()
            every { fileTools.listFiles("") } returns listOf("f:test.md")
            every { fileTools.resolvePath("test.md") } returns mdFile
            every { fileTools.safeReadFile("test.md") } returns Files.readString(mdFile)

            val result = reader.parseFromDirectory(fileTools)

            assertTrue(result.success)
            assertEquals(1, result.contentRoots.size)

            val document = result.contentRoots.first()
            val allLeaves = document.leaves()

            // Verify all leaves have required metadata
            allLeaves.forEach { leaf ->
                assertNotNull(
                    leaf.metadata["root_document_id"],
                    "Leaf '${leaf.title}' is missing root_document_id"
                )
                assertNotNull(
                    leaf.metadata["container_section_id"],
                    "Leaf '${leaf.title}' is missing container_section_id"
                )
                assertNotNull(
                    leaf.metadata["leaf_section_id"],
                    "Leaf '${leaf.title}' is missing leaf_section_id"
                )

                assertEquals(document.id, leaf.metadata["root_document_id"])
                // leaf_section_id equals the leaf's id (which may have _preamble suffix)
                assertEquals(leaf.id, leaf.metadata["leaf_section_id"])
            }
        }

        @Test
        fun `test parseFromDirectory with relativePath starting from subdirectory`(@TempDir tempDir: Path) {
            // Create directory structure
            val docsDir = tempDir.resolve("docs")
            Files.createDirectory(docsDir)
            val apiDir = docsDir.resolve("api")
            Files.createDirectory(apiDir)

            // Files in root (should be ignored)
            val rootFile = tempDir.resolve("root.md")
            Files.writeString(rootFile, "# Root\nRoot content", StandardOpenOption.CREATE)

            // Files in docs (should be included)
            val docsFile = docsDir.resolve("guide.md")
            Files.writeString(docsFile, "# Guide\nGuide content", StandardOpenOption.CREATE)

            // Files in docs/api (should be included)
            val apiFile = apiDir.resolve("reference.md")
            Files.writeString(apiFile, "# API Reference\nAPI content", StandardOpenOption.CREATE)

            val fileTools = mockk<FileReadTools>()
            every { fileTools.listFiles("docs") } returns listOf("f:guide.md", "d:api")
            every { fileTools.listFiles("docs/api") } returns listOf("f:reference.md")
            every { fileTools.resolvePath("docs/guide.md") } returns docsFile
            every { fileTools.resolvePath("docs/api/reference.md") } returns apiFile
            every { fileTools.safeReadFile("docs/guide.md") } returns Files.readString(docsFile)
            every { fileTools.safeReadFile("docs/api/reference.md") } returns Files.readString(apiFile)

            val config = DirectoryParsingConfig(
                relativePath = "docs",
                includedExtensions = setOf("md")
            )

            val result = reader.parseFromDirectory(fileTools, config)

            assertTrue(result.success)
            assertEquals(2, result.totalFilesFound) // Only files under docs/
            assertEquals(2, result.filesProcessed)
            assertEquals(2, result.contentRoots.size)

            val titles = result.contentRoots.map { it.title }
            assertTrue(titles.contains("Guide"))
            assertTrue(titles.contains("API Reference"))
            assertFalse(titles.contains("Root")) // Root file should not be included
        }

        @Test
        fun `test parseFromDirectory with nested relativePath`(@TempDir tempDir: Path) {
            // Create deep directory structure
            val projectDir = tempDir.resolve("project")
            Files.createDirectory(projectDir)
            val srcDir = projectDir.resolve("src")
            Files.createDirectory(srcDir)
            val mainDir = srcDir.resolve("main")
            Files.createDirectory(mainDir)

            // Files in project/src/main (target directory)
            val mainFile = mainDir.resolve("App.kt")
            Files.writeString(
                mainFile,
                """
                /**
                 * Main application
                 */
                class App {
                    fun run() = "Running"
                }
            """.trimIndent(), StandardOpenOption.CREATE
            )

            // Files outside target path (should be ignored)
            val projectFile = projectDir.resolve("README.md")
            Files.writeString(projectFile, "# Project\nProject readme", StandardOpenOption.CREATE)

            val fileTools = mockk<FileReadTools>()
            every { fileTools.listFiles("project/src/main") } returns listOf("f:App.kt")
            every { fileTools.resolvePath("project/src/main/App.kt") } returns mainFile
            every { fileTools.safeReadFile("project/src/main/App.kt") } returns Files.readString(mainFile)

            val config = DirectoryParsingConfig(
                relativePath = "project/src/main",
                includedExtensions = setOf("kt")
            )

            val result = reader.parseFromDirectory(fileTools, config)

            assertTrue(result.success)
            assertEquals(1, result.totalFilesFound)
            assertEquals(1, result.filesProcessed)
            assertEquals(1, result.contentRoots.size)

            val titles = result.contentRoots.map { it.title }
            assertTrue(titles.any { it.contains("Main application") || it.contains("/**") })
        }

        @Test
        fun `test parseFromDirectory with withRelativePath builder`(@TempDir tempDir: Path) {
            val subdir = tempDir.resolve("subdir")
            Files.createDirectory(subdir)

            val file = subdir.resolve("test.md")
            Files.writeString(file, "# Test\nTest content", StandardOpenOption.CREATE)

            val fileTools = mockk<FileReadTools>()
            every { fileTools.listFiles("subdir") } returns listOf("f:test.md")
            every { fileTools.resolvePath("subdir/test.md") } returns file
            every { fileTools.safeReadFile("subdir/test.md") } returns Files.readString(file)

            // Use builder method to set relative path
            val baseConfig = DirectoryParsingConfig(includedExtensions = setOf("md"))
            val config = baseConfig.withRelativePath("subdir")

            assertEquals("subdir", config.relativePath)

            val result = reader.parseFromDirectory(fileTools, config)

            assertTrue(result.success)
            assertEquals(1, result.totalFilesFound)
            assertEquals(1, result.filesProcessed)
        }

        @Test
        fun `test parseFromDirectory with empty relativePath uses root`(@TempDir tempDir: Path) {
            val file = tempDir.resolve("root-file.md")
            Files.writeString(file, "# Root File\nRoot content", StandardOpenOption.CREATE)

            val fileTools = mockk<FileReadTools>()
            every { fileTools.listFiles("") } returns listOf("f:root-file.md")
            every { fileTools.resolvePath("root-file.md") } returns file
            every { fileTools.safeReadFile("root-file.md") } returns Files.readString(file)

            // Explicitly set empty relativePath (default)
            val config = DirectoryParsingConfig(
                relativePath = "",
                includedExtensions = setOf("md")
            )

            val result = reader.parseFromDirectory(fileTools, config)

            assertTrue(result.success)
            assertEquals(1, result.totalFilesFound)
            assertEquals(1, result.filesProcessed)
            assertTrue(result.contentRoots.any { it.title == "Root File" })
        }
    }
}
