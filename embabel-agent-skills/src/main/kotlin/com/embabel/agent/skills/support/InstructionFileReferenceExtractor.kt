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

        val references = mutableSetOf<String>()

        // Extract markdown link targets that are local paths
        MARKDOWN_LINK_PATTERN.findAll(instructions).forEach { match ->
            val path = match.groupValues[2]
            if (isLocalPath(path)) {
                references.add(normalizePath(path))
            }
        }

        // Extract inline resource paths
        RESOURCE_PATH_PATTERN.findAll(instructions).forEach { match ->
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
