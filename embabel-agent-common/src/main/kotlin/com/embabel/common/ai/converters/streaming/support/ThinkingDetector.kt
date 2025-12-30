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
package com.embabel.common.ai.converters.streaming.support

import com.embabel.common.core.streaming.ThinkingState
import com.embabel.common.core.thinking.ThinkingTags
import org.slf4j.LoggerFactory

/**
 * Utility functions for streaming content processing, particularly thinking content detection and extraction.
 *
 * Provides centralized logic for identifying and processing thinking content in various formats
 * used by different LLM models and reasoning systems. Uses ThinkingTags for consistent tag definitions.
 */
internal object ThinkingDetector {

    private val logger = LoggerFactory.getLogger(ThinkingDetector::class.java)

    /**
     * XML-style thinking tags for streaming processing.
     * Uses centralized ThinkingTags definitions, excluding special-purpose tags.
     */
    private val thinkingTags = ThinkingTags.TAG_DEFINITIONS
        .filterNot { it.key in listOf("legacy_prefix", "no_prefix") }

    /**
     * Detects if a line contains thinking content using flexible pattern matching.
     *
     * Uses ThinkingTags definitions to support multiple reasoning tag formats commonly used by different LLMs:
     * - XML-style tags: <think>, <analysis>, <thought>, <final>, <scratchpad>, <chain_of_thought>, <reasoning>
     * - Legacy prefix format: //THINKING: content
     *
     * @param line The complete line to check for thinking patterns
     * @return true if the line contains thinking content, false otherwise
     */
    fun isThinkingLine(line: String): Boolean {
        return thinkingPatterns.any { pattern ->
            pattern.containsMatchIn(line)
        }
    }

    /**
     * Extracts thinking content from a line, removing the markup tags.
     *
     * This method finds the first matching thinking pattern and extracts the content
     * while removing the surrounding markup tags or prefixes.
     *
     * @param line The complete line containing thinking markup
     * @return The extracted thinking content without markup, or the original line if no pattern matches
     */
    fun extractThinkingContent(line: String): String {
        // Try each pattern to find and extract content
        for (pattern in thinkingPatterns) {
            val match = pattern.find(line)
            if (match != null) {
                // For block patterns like <think>content</think>, extract group 1
                if (match.groupValues.size > 1) {
                    return match.groupValues[1].trim()
                }
                // For prefix patterns like //THINKING: content, remove the prefix
                if (line.startsWith("//THINKING:")) {
                    return line.removePrefix("//THINKING:").trim()
                }
                //  continue to next pattern
            }
        }
        // Fallback: return the line as-is if no pattern matches
        return line.trim()
    }

    /**
     * Detects the thinking state of a line to support multi-line thinking blocks.
     *
     * This method enables reactive streaming by processing thinking content as it flows,
     * rather than buffering entire blocks. Critical for multi-line thinking from models like Ollama.
     *
     * Algorithm:
     * 1. First check if line has any thinking markers
     * 2. If markers found: analyze start/end tag presence for state detection
     * 3. If no markers: classify as JSON or CONTINUATION based on content
     *
     * Examples:
     * - "<think>complete thought</think>" → ThinkingState.BOTH
     * - "<think>" → ThinkingState.START
     * - "continuing the thought..." → ThinkingState.CONTINUATION (when not JSON)
     * - "</think>" → ThinkingState.END
     * - '{"field": "value"}' → null (JSON content, not thinking)
     *
     * @param line The complete line to analyze for thinking state markers
     * @return ThinkingState classification: NONE for JSON content, other states for thinking content
     */
    fun detectThinkingState(line: String): ThinkingState {
        val trimmed = line.trim()
        logger.debug("Detecting thinking state for line: '{}'", trimmed.take(50))

        // Check for complete thinking patterns first
        if (isThinkingLine(line)) {
            logger.debug("Line matches complete thinking pattern")
            // Analyze which tags are present for state detection
            val hasStartTag = startTags.any { line.contains(it) }
            val hasEndTag = endTags.any { line.contains(it) }

            val state = when {
                hasStartTag && hasEndTag -> ThinkingState.BOTH
                hasStartTag -> ThinkingState.START
                hasEndTag -> ThinkingState.END
                line.startsWith("//THINKING:") -> ThinkingState.BOTH  // Legacy format
                else -> ThinkingState.CONTINUATION
            }

            logger.debug(
                "Complete pattern classification: hasStart={}, hasEnd={}, state={}",
                hasStartTag,
                hasEndTag,
                state
            )
            return state
        }

        // Check for partial tags (opening or closing tags only)
        when {
            startTags.any { trimmed == it } -> {
                logger.debug("Line is standalone start tag")
                return ThinkingState.START
            }

            endTags.any { trimmed == it } -> {
                logger.debug("Line is standalone end tag")
                return ThinkingState.END
            }

            startTags.any { trimmed.startsWith(it) && !endTags.any { end -> trimmed.contains(end) } } -> {
                logger.debug("Line starts with opening tag but no closing tag")
                return ThinkingState.START
            }

            endTags.any { trimmed.endsWith(it) && !startTags.any { start -> trimmed.contains(start) } } -> {
                logger.debug("Line ends with closing tag but no opening tag")
                return ThinkingState.END
            }
        }

        // No thinking markers found - classify based on content type
        val isJson = isValidJson(line)
        return if (isJson) {
            logger.debug("Line classified as JSON content")
            ThinkingState.NONE  // This is JSON content, not thinking
        } else {
            logger.debug("Line classified as thinking continuation")
            ThinkingState.CONTINUATION  // Raw text, assume thinking continuation
        }
    }

    /**
     * Fast heuristic validation for JSON content to avoid expensive parsing.
     *
     * Performance optimization using simple string checks rather than full JSON parsing
     * to quickly identify potential JSON lines in streaming content.
     *
     * Validation logic:
     * 1. Line must not be empty after trimming
     * 2. Must start with '{' (JSON object start)
     * 3. Must end with '}' (JSON object end)
     * 4. Must contain ':' (JSON objects require key-value pairs)
     *
     * Note: Intentionally simple heuristic - actual JSON parsing in the converter
     * will catch real JSON syntax errors.
     *
     * @param line The line to check for JSON format
     * @return true if line appears to be JSON object, false otherwise
     */
    private fun isValidJson(line: String): Boolean {
        val trimmed = line.trim()
        val isValid = trimmed.isNotEmpty() &&
                trimmed.startsWith("{") &&
                trimmed.endsWith("}") &&
                trimmed.contains(":")

        logger.debug("JSON validation for '{}': {}", trimmed.take(50), isValid)
        return isValid
    }

    /**
     * Get all start tags from the tag definitions.
     */
    private val startTags = thinkingTags.values.map { it.first }

    /**
     * Get all end tags from the tag definitions.
     */
    private val endTags = thinkingTags.values.map { it.second }

    /**
     * Regex patterns for detecting thinking content in various formats.
     * Generated from ThinkingTags definitions for consistency across the system.
     */
    private val thinkingPatterns = buildList {
        // Block-style thinking tags (capture content inside)
        thinkingTags.values.forEach { (start, end) ->
            val escapedStart = Regex.escape(start)
            val escapedEnd = Regex.escape(end)
            add("$escapedStart(.*?)$escapedEnd".toRegex(RegexOption.DOT_MATCHES_ALL))
        }
        // Prefix-style thinking markers (for legacy compatibility)
        add("^//THINKING:.*".toRegex(RegexOption.MULTILINE))
    }
}
