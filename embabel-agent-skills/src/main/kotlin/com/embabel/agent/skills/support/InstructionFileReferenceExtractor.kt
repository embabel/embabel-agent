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
package com.embabel.agent.skills.support

/**
 * Extracts file references from skill instructions.
 *
 * Recognizes two patterns per the Agent Skills specification:
 * 1. Markdown links: `[text](path/to/file.ext)`
 * 2. Resource paths: `scripts/file.ext`, `references/file.ext`, `assets/file.ext`
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 */
object InstructionFileReferenceExtractor {

    private val RESOURCE_DIRS = setOf("scripts", "references", "assets")

    // Matches markdown links: [text](path)
    private val MARKDOWN_LINK_PATTERN = Regex("""\[([^\]]*)\]\(([^)]+)\)""")

    // Matches resource paths: scripts/file.ext, references/file.md, assets/image.png
    // Must start with a known resource directory and end with a word character
    // (to avoid capturing trailing punctuation like periods at end of sentences)
    private val RESOURCE_PATH_PATTERN = Regex(
        """(?:^|[^\w/])((scripts|references|assets)/[\w./-]*\w)""",
        RegexOption.MULTILINE
    )

    // Matches a CommonMark fenced code block — opening fence at line start
    // (allowing up to three leading spaces of indent) using ``` or ~~~,
    // through to the matching closing fence on its own line, OR end of
    // input if the fence is never closed (CommonMark allows this and
    // implicitly closes at EOF).
    //
    // Why this exists: skill bodies routinely embed code samples that
    // contain `[label](path)`-shaped strings (JS template literals,
    // markdown rendered as a string in code, etc.) and resource-dir-
    // prefixed strings ("scripts/legacy.py"). Those are illustrations,
    // not real file references, and validating them as files turns any
    // skill teaching code into a footgun.
    private val FENCED_CODE_BLOCK = Regex(
        """(?ms)^[ \t]{0,3}(`{3,}|~{3,})[^\n]*(?:\n|$)(?:.*?(?:\n[ \t]{0,3}\1[ \t]*(?:\n|$)|\z))?"""
    )

    /**
     * Extract all file references from instruction text.
     *
     * @param instructions the instruction text to scan
     * @return set of relative file paths referenced in the instructions
     */
    fun extract(instructions: String?): Set<String> {
        if (instructions.isNullOrBlank()) {
            return emptySet()
        }

        // Strip fenced code blocks BEFORE running the extractors so that
        // illustrative code samples don't pollute the reference set.
        val withoutCode = FENCED_CODE_BLOCK.replace(instructions, "")

        val references = mutableSetOf<String>()

        // Extract markdown link targets that are local paths
        MARKDOWN_LINK_PATTERN.findAll(withoutCode).forEach { match ->
            val path = match.groupValues[2]
            if (isLocalPath(path)) {
                references.add(normalizePath(path))
            }
        }

        // Extract inline resource paths
        RESOURCE_PATH_PATTERN.findAll(withoutCode).forEach { match ->
            val path = match.groupValues[1]
            references.add(normalizePath(path))
        }

        return references
    }

    /**
     * Check if a path is a local file reference (not a URL).
     */
    private fun isLocalPath(path: String): Boolean {
        return !path.startsWith("http://") &&
            !path.startsWith("https://") &&
            !path.startsWith("mailto:") &&
            !path.startsWith("#") &&
            !path.contains("://")
    }

    /**
     * Normalize a path by removing leading ./ and trailing whitespace.
     */
    private fun normalizePath(path: String): String {
        return path.trim()
            .removePrefix("./")
            .trimEnd('/')
    }
}
