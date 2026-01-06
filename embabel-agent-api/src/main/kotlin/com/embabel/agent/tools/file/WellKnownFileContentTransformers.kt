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
package com.embabel.agent.tools.file

import com.embabel.common.util.StringTransformer
import com.embabel.common.util.loggerFor

/**
 * Provides common implementations of FileContentTransformer for reducing file content bloat.
 * These transformers can be used to clean up file content before sending it to LLMs or other
 * text processing systems to reduce token usage and improve performance by helping them to
 * focus on essential code.
 */
object WellKnownFileContentTransformers {

    private val logger = loggerFor<WellKnownFileContentTransformers>()

    /**
     * Removes Apache License headers commonly found at the top of source files.
     */
    val removeApacheLicenseHeader: StringTransformer = StringTransformer { content ->
        val apacheLicensePattern = Regex(
            "/\\*\\s*\\n" +
                    " \\* Copyright .*?\\n" +
                    "(?:.|\\n)*?" +  // Non-greedy match for any characters including newlines
                    " \\* limitations under the License\\.\\s*\\n" +
                    " \\*/\\s*\\n",
            RegexOption.MULTILINE
        )

        val result = apacheLicensePattern.replace(content, "")
        logger.debug("Apache License Header sanitizer: removed {} characters", content.length - result.length)
        result
    }

    /**
     * Removes documentation comments (/** ... */) from the code.
     */
    val removeDocComments: StringTransformer = StringTransformer { content ->
        val docCommentPattern = Regex(
            "/\\*\\*\\s*\\n" +
                    "(?:.|\\n)*?" +  // Non-greedy match for any characters including newlines
                    "\\s*\\*/",
            RegexOption.MULTILINE
        )

        val result = docCommentPattern.replace(content, "")
        logger.debug("Doc comments sanitizer: removed {} characters", content.length - result.length)
        result
    }

    /**
     * Removes single-line comments (// ...) from the code.
     */
    val removeSingleLineComments: StringTransformer = StringTransformer { content ->
        val singleLineCommentPattern = Regex("\\s*//.*$", RegexOption.MULTILINE)

        val result = singleLineCommentPattern.replace(content, "")
        logger.debug("Single line comments sanitizer: removed {} characters", content.length - result.length)
        result
    }

    /**
     * Removes import statements from the code.
     */
    val removeImports: StringTransformer = StringTransformer { content ->
        val importPattern = Regex("import .*$\\n", RegexOption.MULTILINE)

        val result = importPattern.replace(content, "")
        logger.debug("Import statements sanitizer: removed {} characters", content.length - result.length)
        result
    }

    /**
     * Removes empty lines from file content
     */
    val removeEmptyLines: StringTransformer = StringTransformer { content ->
        val emptyLinePattern = Regex("^\\s*$\\n", RegexOption.MULTILINE)

        val result = emptyLinePattern.replace(content, "")
        logger.debug("Empty lines sanitizer: removed {} characters", content.length - result.length)
        result
    }

    /**
     * Removes excessive whitespace from file content
     */
    val compressWhitespace: StringTransformer = StringTransformer { content ->
        // Replace multiple spaces with a single space
        val multipleSpacesPattern = Regex("[ \\t]+")
        // Replace multiple consecutive empty lines with a single empty line
        val multipleEmptyLinesPattern = Regex("\\n{3,}")

        val intermediate = multipleSpacesPattern.replace(content, " ")
        val result = multipleEmptyLinesPattern.replace(intermediate, "\n\n")

        logger.debug("Whitespace compression sanitizer: removed {} characters", content.length - result.length)
        result
    }

    /**
     * Returns all available sanitizers in a sensible order for maximum content reduction.
     * The order is important to ensure proper sanitization.
     */
    fun allSanitizers(): List<StringTransformer> {
        logger.debug("Creating all sanitizers list")
        return listOf(
            removeApacheLicenseHeader,
            removeDocComments,
            removeSingleLineComments,
            removeImports,
            removeEmptyLines,
            compressWhitespace,
        )
    }

    /**
     * Returns a minimal set of sanitizers that preserve code structure
     * while still reducing file size.
     */
    fun minimalSanitizers(): List<StringTransformer> {
        logger.debug("Creating minimal sanitizers list")
        return listOf(
            removeApacheLicenseHeader,
            removeDocComments,
            compressWhitespace
        )
    }

    /**
     * Returns sanitizers focused on comment removal only.
     */
    fun commentRemovalSanitizers(): List<StringTransformer> {
        logger.debug("Creating comment removal sanitizers list")
        return listOf(
            removeApacheLicenseHeader,
            removeDocComments,
            removeSingleLineComments
        )
    }

    /**
     * Returns sanitizers focused on whitespace cleanup only.
     */
    fun whitespaceCleanupSanitizers(): List<StringTransformer> {
        logger.debug("Creating whitespace cleanup sanitizers list")
        return listOf(
            removeEmptyLines,
            compressWhitespace
        )
    }
}
