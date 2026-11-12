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
import com.embabel.agent.rag.model.MaterializedDocument
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

/**
 * Parser for Markdown content that extracts sections based on heading hierarchy.
 *
 * A line that starts with `#` is a heading, unless it is inside a fenced code block.
 * A fence may open on a list item's line (`- ```bash`) or inside a list item, indented to the
 * item's content: there its closing fence is indented to the same column.
 * A heading may end with an id attribute, as pandoc and kramdown write it: `## Title {#title-id}`.
 * The id is removed from the section title, and the section URI is the document URI
 * with the id as its fragment. A section whose heading has no id has the URI of the nearest
 * section above it whose heading has one, or else the document URI: a site that gives ids only
 * to the upper levels of headings still links each section to the heading nearest to it.
 */
internal class MarkdownContentParser(
    private val logger: Logger,
) : ContentFormatParser {

    override fun parse(content: String, metadata: Metadata, uri: String): MaterializedDocument {
        val lines = content.lines()
        val leafSections = mutableListOf<LeafSection>()
        val currentSection = StringBuilder()
        var currentTitle = ""
        var sectionId = ""
        val rootId = UUID.randomUUID().toString()
        var parentId: String? = rootId
        val sectionStack = mutableMapOf<Int, String>() // level -> sectionId
        val uriStack = mutableMapOf<Int, String>() // level -> section URI
        var sectionUri = uri
        // The fence that opened the code block we are inside, or null when we are not in one
        var openFence: Fence? = null
        var openFenceLine = 0
        // The content columns of the list items the current line may belong to, innermost last
        val listColumns = ArrayDeque<Int>()

        for ((index, line) in lines.withIndex()) {
            if (openFence != null) {
                if (line.isNotBlank() && indentOf(line) < openFence.column) {
                    // A line left of the list item that holds the fence ends the item, and the code block
                    // with it. The line itself is read below like any line outside a code block.
                    openFence = null
                } else {
                    // Inside a code block every line is content. The block ends at a fence that closes the
                    // one that opened it: see Fence.closes for what that requires.
                    if (Fence.parse(line, openFence.column)?.closes(openFence) == true) {
                        openFence = null
                    }
                    currentSection.appendLine(line)
                    continue
                }
            }

            val item = ListItem.parse(line)
            if (line.isNotBlank()) {
                // A line belongs only to the list items whose content starts at or left of it
                val column = item?.indent ?: indentOf(line)
                while (listColumns.isNotEmpty() && listColumns.last() > column) {
                    listColumns.removeLast()
                }
            }
            // On a list item's own line the fence follows the marker, at the item's content column.
            // Otherwise it is indented to the content of the list item the line is in, or to nothing.
            val fence = if (item != null) {
                Fence.parse(item.content)?.copy(column = item.contentColumn)
            } else {
                Fence.parse(line, listColumns.lastOrNull() ?: 0)
            }
            if (item != null) {
                listColumns.addLast(item.contentColumn)
            }

            when {
                fence != null -> {
                    // A fence outside a code block opens one: the lines up to its closing fence are code
                    openFence = fence
                    openFenceLine = index + 1
                    currentSection.appendLine(line)
                }

                line.startsWith("#") -> {
                    listColumns.clear()
                    // Save previous section if it exists
                    if (currentTitle.isNotBlank()) {
                        leafSections.add(
                            ContentFormatParserUtils.createLeafSection(
                                sectionId,
                                currentTitle,
                                currentSection.toString().trim(),
                                parentId,
                                sectionUri,
                                metadata,
                                rootId
                            )
                        )
                    }

                    // Parse new heading
                    val level = line.takeWhile { it == '#' }.length
                    val heading = HeadingText.parse(line.substring(level))
                    currentTitle = heading.title
                    // The section URI is the document URI with the heading's id as fragment. A heading without
                    // an id takes the URI of the nearest heading above it, one level up or more, and the
                    // document URI when there is none.
                    sectionUri = heading.id?.let { withFragment(uri, it) }
                        ?: (level - 1 downTo 1).firstNotNullOfOrNull { uriStack[it] }
                        ?: uri
                    sectionId = UUID.randomUUID().toString()
                    currentSection.clear()

                    // Determine parent based on hierarchy
                    parentId = when {
                        level == 1 -> rootId
                        level > 1 -> {
                            // Find the most recent parent at level - 1
                            (level - 1 downTo 1).firstNotNullOfOrNull { sectionStack[it] } ?: rootId
                        }

                        else -> rootId
                    }

                    sectionStack[level] = sectionId
                    uriStack[level] = sectionUri
                    // Clear deeper levels
                    sectionStack.keys.filter { it > level }.forEach { sectionStack.remove(it) }
                    uriStack.keys.filter { it > level }.forEach { uriStack.remove(it) }
                }

                else -> {
                    if (line.isNotBlank() || currentSection.isNotEmpty()) {
                        currentSection.appendLine(line)
                    }
                }
            }
        }

        if (openFence != null) {
            logger.debug(
                "Code fence opened at line {} of {} is never closed: the rest of the document is read as code",
                openFenceLine,
                uri,
            )
        }

        // Add final section if exists
        if (currentTitle.isNotBlank()) {
            leafSections.add(
                ContentFormatParserUtils.createLeafSection(
                    sectionId,
                    currentTitle,
                    currentSection.toString().trim(),
                    parentId,
                    sectionUri,
                    metadata,
                    rootId
                )
            )
        }

        // If no sections were found, create a single section with the whole content
        if (leafSections.isEmpty() && content.isNotBlank()) {
            val title = extractTitle(lines, metadata) ?: "Document"
            leafSections.add(
                ContentFormatParserUtils.createLeafSection(
                    UUID.randomUUID().toString(),
                    title,
                    content.trim(),
                    rootId,
                    uri,
                    metadata,
                    rootId
                )
            )
        }

        logger.debug("Created {} leaf sections from markdown content", leafSections.size)

        // Build the hierarchical structure
        val documentTitle =
            extractTitle(lines, metadata)
                ?: metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY)
                ?: "Document"

        val hierarchicalSections = ContentFormatParserUtils.buildHierarchy(leafSections, rootId)

        return MaterializedDocument(
            id = rootId,
            uri = uri,
            title = documentTitle,
            ingestionTimestamp = Instant.now(),
            children = hierarchicalSections,
            metadata = ContentFormatParserUtils.extractMetadataMap(metadata)
        )
    }

    /**
     * The title from the metadata when it has one. Otherwise the title
     * [ContentFormatParserUtils.extractTitle] finds in the markdown: a heading without its trailing
     * `{#id}`, or the first line of text as it is, since only a heading has an id attribute.
     */
    private fun extractTitle(lines: List<String>, metadata: Metadata): String? {
        metadata.get(TikaCoreProperties.TITLE)?.let { return it }
        val title = ContentFormatParserUtils.extractTitle(lines, metadata) ?: return null
        val fromHeading = lines.firstOrNull { it.isNotBlank() }?.startsWith("#") == true
        return if (fromHeading) HeadingText.parse(title).title else title
    }

    companion object {

        /**
         * Returns [uri] with [fragment] as its fragment. A fragment already on [uri] is replaced.
         */
        @JvmStatic
        fun withFragment(uri: String, fragment: String): String =
            uri.substringBefore('#') + "#" + fragment
    }
}

/**
 * The column [line]'s text starts at. A tab moves to the next multiple of four, as in CommonMark.
 */
internal fun indentOf(line: String): Int {
    var column = 0
    for (c in line) {
        column = when (c) {
            ' ' -> column + 1
            '\t' -> column + 4 - column % 4
            else -> return column
        }
    }
    return column
}

/**
 * A line that starts a list item: a bullet (`-`, `+` or `*`) or an ordered marker (`1.` or `1)`),
 * then at least one space.
 *
 * @property indent the column the marker starts at
 * @property contentColumn the column the item's content starts at. Lines indented to it belong to the item.
 * @property content the line after the marker and the spaces that follow it
 */
internal data class ListItem(
    val indent: Int,
    val contentColumn: Int,
    val content: String,
) {

    companion object {

        /**
         * Group 1 is the indentation, group 2 the marker, group 3 the spaces after it, group 4 the content.
         */
        private val MARKER = Regex("""^( *)([-+*]|\d{1,9}[.)])( +)(.*)$""")

        /**
         * Returns the list item [line] starts, or null when it starts none. As in CommonMark, when
         * five or more spaces follow the marker the content starts one space after it, and the rest
         * of the spaces belong to the content.
         */
        @JvmStatic
        fun parse(line: String): ListItem? {
            val match = MARKER.matchEntire(line) ?: return null
            val (indent, marker, spaces, rest) = match.destructured
            val afterMarker = indent.length + marker.length
            return if (spaces.length > 4) {
                ListItem(indent.length, afterMarker + 1, spaces.substring(1) + rest)
            } else {
                ListItem(indent.length, afterMarker + spaces.length, rest)
            }
        }
    }
}

/**
 * The text of a heading after its `#` characters, split into the title and an optional id.
 *
 * @property title the heading text without the id attribute
 * @property id the id from a trailing `{#id}`, or null when the heading has none
 */
internal data class HeadingText(
    val title: String,
    val id: String?,
) {

    companion object {

        private val logger = LoggerFactory.getLogger(HeadingText::class.java)

        /**
         * Matches a title followed by `{#id}` at the end of the heading.
         * Group 1 is the title. Group 2 is the id, which has no whitespace and no braces.
         * Whitespace is allowed around the braces and inside them.
         */
        private val TRAILING_ID = Regex("""^(.*?)\s*\{\s*#([^\s{}]+)\s*\}$""")

        /**
         * Splits [text] into title and id. When [text] has no trailing `{#id}`, or nothing
         * comes before it, the whole trimmed text is the title and the id is null.
         */
        @JvmStatic
        fun parse(text: String): HeadingText {
            val trimmed = text.trim()
            val match = TRAILING_ID.matchEntire(trimmed)
            if (match == null) {
                return HeadingText(trimmed, null)
            }
            val title = match.groupValues[1].trim()
            if (title.isEmpty()) {
                logger.debug("Heading '{}' has an id attribute but no title: it is kept as the title, with no id", trimmed)
                return HeadingText(trimmed, null)
            }
            return HeadingText(title, match.groupValues[2])
        }
    }
}

/**
 * A line that opens or closes a fenced code block: up to three spaces of indentation past
 * [column], then three or more backticks or three or more tildes.
 *
 * @property marker the fence character, a backtick or a tilde
 * @property length how many times [marker] repeats
 * @property hasInfo whether text such as a language name follows the fence characters
 * @property column the column the fence is indented from: 0, or the content column of the list item it is in
 */
internal data class Fence(
    val marker: Char,
    val length: Int,
    val hasInfo: Boolean,
    val column: Int = 0,
) {

    /**
     * Whether this line closes the code block that [opening] started. The two need not be the same
     * line: a closing fence uses the same character, is at least as long as the opening fence (so
     * ```` ``` ```` inside a ```` ```` ```` block is content), and has nothing after it. An opening
     * fence may carry an info string such as `bash`; a closing one may not.
     */
    fun closes(opening: Fence): Boolean =
        marker == opening.marker && length >= opening.length && !hasInfo

    companion object {

        /**
         * Group 1 is the fence characters. Group 2 is the rest of the line.
         */
        private val FENCE = Regex("""^(`{3,}|~{3,})(.*)$""")

        /**
         * Returns the fence on [line] indented from [column], or null when [line] is not a fence there.
         * A backtick fence cannot have a backtick after it, so a line such as ```` ```code``` ````
         * is not a fence.
         */
        @JvmStatic
        @JvmOverloads
        fun parse(line: String, column: Int = 0): Fence? {
            val indent = indentOf(line)
            if (indent < column || indent - column > 3) {
                return null
            }
            val match = FENCE.matchEntire(line.trimStart()) ?: return null
            val chars = match.groupValues[1]
            val rest = match.groupValues[2]
            if (chars[0] == '`' && rest.contains('`')) {
                return null
            }
            return Fence(chars[0], chars.length, rest.isNotBlank(), column)
        }
    }
}
