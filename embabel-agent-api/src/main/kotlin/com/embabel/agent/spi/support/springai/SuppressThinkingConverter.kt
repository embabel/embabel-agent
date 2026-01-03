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
package com.embabel.agent.spi.support.springai

import org.slf4j.LoggerFactory
import org.springframework.ai.converter.StructuredOutputConverter

typealias ThinkBlockFinder = (String) -> String?

/**
 * A decorator for Spring AI's [StructuredOutputConverter] that cleans up LLM outputs by removing "thinking" blocks.
 *
 * Spring AI's [StructuredOutputConverter] is designed to parse structured formats (like JSON) from
 * LLM outputs, but it can fail if the output contains additional text like reasoning blocks.
 * For example, if an LLM returns:
 *
 * ```
 * <think>
 * Let me think about what information to include in this person object.
 * The name should be "John Doe" and the age should be 30.
 * </think>
 * {"name": "John Doe", "age": 30}
 * ```
 *
 * A standard converter would fail to parse this as valid JSON.
 *
 * This decorator sanitizes the input by removing any content enclosed in <think> tags before
 * passing it to the delegate converter, allowing reasoning models to be used with Spring AI's
 * structured output functionality.
 *
 * ## Usage
 * Wrap any existing [StructuredOutputConverter] with this class:
 * ```
 * val originalConverter = BeanOutputConverter(Person::class.java)
 * val thinkingAwareConverter = SuppressThinkingConverter(originalConverter)
 * ```
 *
 * @param T The target type that the delegate converter produces
 */
class SuppressThinkingConverter<T>(
    /**
     * The underlying converter that will process the sanitized output.
     * This delegate handles the actual conversion from the cleaned string to the target type T.
     */
    private val delegate: StructuredOutputConverter<T>,
    private val thinkBlockFinders: List<ThinkBlockFinder> = listOf(FindMarkupThinkBlock, FindPrefixThinkBlock),
) : StructuredOutputConverter<T> {
    private val logger = LoggerFactory.getLogger(SuppressThinkingConverter::class.java)

    /**
     * Converts the source string to the target type after removing any thinking blocks.
     *
     * This method performs the following steps:
     * 1. Calls [identifyThinkBlock] to sanitize the input by removing thinking blocks
     * 2. Logs any detected thinking blocks (for debugging/analysis purposes)
     * 3. Delegates the actual conversion to the wrapped converter
     *
     * @param source The raw string output from the LLM, potentially containing thinking blocks
     * @return The converted object of type T, or null if conversion fails
     */
    override fun convert(source: String): T? {
        val sanitization = identifyThinkBlock(source)
        sanitization.thinkBlock?.let {
            logger.trace(
                "Think block detected in input: '{}': Remaining content: '{}'",
                it,
                sanitization.cleaned,
            )
        }
        return delegate.convert(sanitization.cleaned)
    }

    /**
     * Returns the format description from the delegate converter.
     *
     * This method is part of the [StructuredOutputConverter] interface's [org.springframework.ai.converter.FormatProvider] functionality.
     * The format string provides instructions to the LLM about how to structure its response.
     * This implementation simply forwards to the delegate's format, maintaining the decorator pattern.
     *
     * @return The format description string from the delegate, or null if the delegate doesn't provide one
     */
    override fun getFormat(): String = delegate.format

    private fun identifyThinkBlock(input: String): ThinkBlockSanitization {
        // First try to parse the input as JSON to see if it is already clean
        try {
            delegate.convert(input)
            // If it succeeds, no need to sanitize
            return ThinkBlockSanitization(
                input = input,
                thinkBlock = null,
                cleaned = input,
            )
        } catch (e: Exception) {
            // If it fails, we assume there might be a think block to sanitize
            logger.debug("Failed to parse input as JSON, sanitizing for think blocks", e)
        }
        // Try to find and remove the think block markup
        return thinkBlockSanitization(thinkBlockFinders, input)
            ?: ThinkBlockSanitization(
                input = input,
                thinkBlock = null,
                cleaned = input,
            )
    }


}

val FindMarkupThinkBlock: ThinkBlockFinder = { input ->
    val thinkBlockRegex = "<think>(.*?)</think>".toRegex(RegexOption.DOT_MATCHES_ALL)
    thinkBlockRegex.find(input)?.value
}

val FindPrefixThinkBlock: ThinkBlockFinder = { input ->
    val thinkBlockRegex = "[^{]*".toRegex(RegexOption.DOT_MATCHES_ALL)
    thinkBlockRegex.find(input)?.value
}

fun stringWithoutThinkBlocks(
    source: String,
    thinkBlockFinders: List<ThinkBlockFinder> = listOf(FindMarkupThinkBlock),
): String {
    val sanitization = thinkBlockSanitization(thinkBlockFinders, source)
    return sanitization?.cleaned ?: source
}

internal fun thinkBlockSanitization(
    thinkBlockFinders: List<ThinkBlockFinder>,
    input: String,
): ThinkBlockSanitization? {
    // Apply all finders sequentially rather than stopping at first match
    var cleanedInput = input
    var thinkBlock: String? = null

    for (thinkBlockFinder in thinkBlockFinders) {
        // Apply finder to progressively cleaned up input
        thinkBlockFinder(cleanedInput)?.let { found ->
            if (found.isNotEmpty()) {
                thinkBlock = found
                cleanedInput = cleanedInput.replace(found, "")
            }
        }
    }

    return if (thinkBlock != null) {
        ThinkBlockSanitization(
            input = input,
            thinkBlock = thinkBlock,
            cleaned = cleanedInput,
        )
    } else {
        null
    }
}

/**
 * Data class representing the result of sanitizing an input string to remove thinking blocks.
 *
 * This class encapsulates all relevant information about the sanitization process:
 * - The original input (for reference/debugging)
 * - The extracted thinking block (if any was found)
 * - The cleaned output with thinking blocks removed
 *
 * @property input The original, unmodified input string
 * @property thinkBlock The extracted thinking block, or null if none was found
 * @property cleaned The sanitized input with thinking blocks removed
 */
internal data class ThinkBlockSanitization(
    val input: String,
    val thinkBlock: String?,
    val cleaned: String,
)
