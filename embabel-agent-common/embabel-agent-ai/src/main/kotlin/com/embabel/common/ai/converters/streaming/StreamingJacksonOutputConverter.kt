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
package com.embabel.common.ai.converters.streaming

import com.embabel.common.ai.converters.FilteringJacksonOutputConverter
import com.embabel.common.ai.converters.streaming.support.ThinkingDetector
import com.embabel.common.core.streaming.StreamingEvent
import com.embabel.common.core.streaming.ThinkingState
import com.embabel.common.core.thinking.ThinkingTags
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import org.springframework.core.ParameterizedTypeReference
import reactor.core.publisher.Flux
import java.lang.reflect.Field
import java.util.function.Predicate

/**
 * Streaming output converter that extends FilteringJacksonOutputConverter to support JSONL format.
 *
 * This converter enables streaming LLM responses by:
 * - Converting JSONL (JSON Lines) input to reactive Flux<T> streams of typed objects
 * - Supporting mixed content streams with both objects and thinking content via StreamingEvent<T>
 * - Inheriting JSON schema injection and property filtering capabilities from parent FilteringJacksonOutputConverter
 * - Providing instructions to LLMs for proper JSONL output formatting
 *
 * Use cases:
 * - Streaming lists of objects from LLM responses in real-time
 * - Processing LLM reasoning (thinking) alongside structured outputs
 * - Real-time agent progress monitoring and incremental results
 *
 * The converter requests JSONL format from LLMs and parses each line as a separate
 * JSON object, emitting them as reactive stream events as they become available.
 */
class StreamingJacksonOutputConverter<T : Any> : FilteringJacksonOutputConverter<T> {

    private val thinkingEnabled: Boolean

    constructor(
        clazz: Class<T>,
        objectMapper: ObjectMapper,
        fieldFilter: Predicate<Field> = Predicate { true },
        thinkingEnabled: Boolean = true,
    ) : super(clazz, objectMapper, fieldFilter) {
        this.thinkingEnabled = thinkingEnabled
    }

    constructor(
        typeReference: ParameterizedTypeReference<T>,
        objectMapper: ObjectMapper,
        fieldFilter: Predicate<Field> = Predicate { true },
        thinkingEnabled: Boolean = true,
    ) : super(typeReference, objectMapper, fieldFilter) {
        this.thinkingEnabled = thinkingEnabled
    }

    /**
     * Convert streaming JSONL text to a Flux of typed objects.
     * Each line should be a valid JSON object matching the schema.
     * Uses resilient error handling - logs warnings for null conversions but continues processing other lines.
     */
    fun convertStream(jsonlContent: String): Flux<T> {
        return convertStreamWithThinking(jsonlContent)
            .filter { event -> event.isObject() }
            .map { event -> event.getObject()!! }
    }


    /**
     * Convert streaming text with thinking blocks into StreamingEvent objects.
     * Supports both object lines and thinking blocks.
     * Uses resilient error handling - logs warnings for individual line failures but continues processing.
     *
     * Before classification, a line holding a JSON object directly after a closing thinking tag
     * is split into two lines (see [splitAfterClosingThinkingTag]), so the object is emitted
     * instead of being lost inside the reasoning. The reactive pipeline below is unchanged:
     * each line still produces at most one event.
     */
    fun convertStreamWithThinking(text: String): Flux<StreamingEvent<T>> {
        return Flux.fromIterable(text.lines().flatMap { splitAfterClosingThinkingTag(it) })
            .filter { it.isNotBlank() }
            .handle { line, sink ->
                try {
                    // Detect thinking state for the line
                    val thinkingState = ThinkingDetector.detectThinkingState(line)

                    when (thinkingState) {
                        ThinkingState.NONE -> {
                            // Line is JSON content
                            val result = super.convert(line)
                            if (result != null) {
                                sink.next(StreamingEvent.Object(result))
                            }
                        }
                        else -> {
                            // Standalone code fence markers (```json, ```) between thinking and JSON
                            // are format artifacts — drop them to prevent leaking into reasoning blocks.
                            if (!line.trim().matches(Regex("^```\\w*$"))) {
                                val thinkingContent = ThinkingDetector.extractThinkingContent(line)
                                sink.next(StreamingEvent.Thinking(thinkingContent, thinkingState))
                            }
                        }
                    }
                } catch (e: Exception) {
                   sink.error(e)
                }
            }
    }

    /**
     * Splits a line that holds a JSON object directly after a closing thinking tag into two lines:
     * the reasoning up to and including the tag, then the JSON object. See issue #2267.
     *
     * Two forms are handled:
     * - a complete thinking block, then JSON: `<think>reasoning</think>{"name": "x"}`
     * - the end of a multi-line thinking block, then JSON: `last step</think>{"name": "x"}`
     *
     * Without the split, the whole line is classified as thinking and the object is lost.
     *
     * Why only after a closing tag: [ThinkingDetector] is stateless and sees one line at a time,
     * so it cannot tell whether a thinking block was opened on an earlier line. A closing tag on
     * the same line proves the block has ended, so whatever follows it is outside any block.
     * Plain text followed by JSON, e.g. `Consider {"name": "x"}`, may be a line inside an open
     * block, where the JSON is only an example, so it is left unchanged. Handling it needs state
     * across lines (see discussion #1881).
     *
     * The split only inserts the line break the model left out. It adds no classification rules:
     * both parts are classified afterwards by [ThinkingDetector], exactly as if the model had written
     * them on separate lines. The line is returned unchanged unless [isSplitPoint] accepts the JSON.
     *
     * @param line one complete line of the streamed response
     * @return the line unchanged, or the reasoning line followed by the JSON line
     */
    private fun splitAfterClosingThinkingTag(line: String): List<String> {
        // Work on the line without surrounding whitespace; rawChunksToLines already trims, tests may not.
        val trimmed = line.trim()
        // Only a line ending with '}' can end with a JSON object; anything else is returned as is.
        if (!trimmed.endsWith('}')) return listOf(line)
        // The detector already classifies this line as JSON: nothing to split.
        if (ThinkingDetector.detectThinkingState(trimmed) == ThinkingState.NONE) return listOf(line)
        // Index just past the last closing thinking tag, or -1 if the line has none.
        //   "<think>r</think>{"name":"x"}"   -> 16, the index of '{'
        //   "last step</think>{"name":"x"}"  -> 17, the index of '{'
        //   "Consider {"name":"x"}"          -> -1, no closing tag
        val afterTag = indexAfterLastClosingTag(trimmed)
        // No closing tag: the line may be inside an open thinking block, so it is left unchanged (#1881).
        if (afterTag < 0) return listOf(line)
        // Everything up to and including the closing tag is the reasoning, e.g. "<think>r</think>".
        val reasoning = trimmed.substring(0, afterTag).trim()
        // Everything after the closing tag must be the whole JSON object, e.g. {"name":"x"}.
        val json = trimmed.substring(afterTag).trim()
        // Split only if the JSON part passes every check.
        if (!isSplitPoint(json)) {
            // Record why a line with a closing tag was kept whole, e.g. "<think>r</think>{"other":1}".
            logger.debug("Text after the closing thinking tag is not a JSON object of the target type, not split: {}", trimmed)
            return listOf(line)
        }
        // Record the split, e.g. reasoning='<think>r</think>', json='{"name":"x"}'.
        logger.debug("Split reasoning and JSON object on the same line: reasoning='{}', json='{}'", reasoning, json)
        // Reasoning first, then JSON: the order the model wrote them.
        //   "<think>r</think>{"name":"x"}" -> ["<think>r</think>", "{"name":"x"}"]
        return listOf(reasoning, json)
    }

    /**
     * Returns the index just past the last closing thinking tag in [line], or -1 if there is none.
     * When several closing tags appear, the rightmost one wins, so everything after it lies outside
     * any thinking block on this line.
     */
    private fun indexAfterLastClosingTag(line: String): Int {
        // Start with "not found".
        var result = -1
        // Check every tag family: </think>, </analysis>, </thought>, ...
        for (tag in CLOSING_TAGS) {
            // Where this tag last occurs, e.g. "</think>" in "<think>r</think>{...}" -> 8; -1 if absent.
            val tagStart = line.lastIndexOf(tag)
            // This tag doesn't occur in the line: try the next family.
            if (tagStart < 0) continue
            // Position right after the tag, e.g. 8 + "</think>".length (8) -> 16.
            val tagEnd = tagStart + tag.length
            // Keep the rightmost end seen so far, e.g. "<analysis>a</analysis><think>b</think>{...}" -> after </think>.
            if (tagEnd > result) result = tagEnd
        }
        // -1 if no closing tag was found, otherwise the index where the JSON candidate starts.
        return result
    }

    /**
     * Whether [json], the text after the closing thinking tag, can be split off safely:
     * ThinkingDetector must classify it as JSON, and it must convert to [T].
     * Each check returns false (no split) as soon as one fails.
     */
    private fun isSplitPoint(json: String): Boolean {
        // 1. The detector must classify the JSON part as JSON, because handle uses the same detector.
        //    Text after the closing tag that is not a whole JSON object is not JSON to it.
        //      {"name":"x"}                  -> NONE         -> pass
        //      so <think>b {"name":"x"}      -> CONTINUATION -> fail (text that opens a new block)
        //      "}                            -> CONTINUATION -> fail (the last closing tag was inside
        //                                       the JSON, e.g. <think>r</think>{"name":"<think>a</think>"})
        if (ThinkingDetector.detectThinkingState(json) != ThinkingState.NONE) return false

        // 2. The JSON part must parse as a non-empty JSON object. Cheap and silent, so malformed text
        //    is rejected before conversion, which logs an ERROR when it fails.
        //      {"name":"x"}   -> pass
        //      {a, b}         -> fail (not JSON)
        if (!isJsonObject(json)) return false

        // 3. The JSON part must convert to T. handle converts it again, and a failure there would end
        //    the stream with an error, so splitting only on success adds no new error path.
        //      {"name":"x"}   -> pass
        //      {"other":1}    -> fail (valid JSON, wrong shape for T)
        return convertsToTarget(json)
    }

    /**
     * Whether [candidate] parses as a non-empty JSON object.
     *
     * Used as a cheap filter before [convertsToTarget], so that text after the closing tag that
     * is not JSON, such as `{a, b}`, is rejected without attempting conversion. Conversion failures
     * are logged as errors by the parent converter, so this keeps the logs clean.
     */
    private fun isJsonObject(candidate: String): Boolean =
        try {
            objectMapper.readTree(candidate).let { it.isObject && it.size() > 0 }
        } catch (_: JacksonException) {
            false
        }

    /**
     * Whether [candidate] converts to [T] with this converter's own conversion.
     *
     * Ensures a split only happens when the JSON part will later be emitted as an object,
     * so valid JSON of a different shape leaves the line unchanged, as before.
     */
    private fun convertsToTarget(candidate: String): Boolean =
        try {
            super.convert(candidate)
            true
        } catch (_: RuntimeException) {
            false
        }

    /**
     * Override format to request JSONL instead of single JSON.
     * Inherits schema injection from parent but modifies instructions for streaming.
     */
    override fun getFormat(): String {
        val thinkingInstructions = if (thinkingEnabled) {
            """|
               |You may include reasoning content using thinking blocks.
               |Use EXACTLY the <think> tag format - do not use variations like <thinking>, <thought>, or <analysis>.
               |<think>your reasoning here
               |another line of thinking</think>
               |
               |Thinking blocks are separate from JSON objects and can appear before, between, or after JSON lines as needed for your analysis.
               |DO NOT put thinking blocks and JSON objects on the same line.
               |""".trimMargin()
        } else ""

        val exampleFormat = if (thinkingEnabled) {
            """|
               |Example format:
               |<think>analyzing the requirements</think>
               |{"field": "precise_value"}
               |<think>considering next item</think>
               |{"field": "another_precise_value"}
               |""".trimMargin()
        } else ""

        return """|
           |Your response should be in JSONL (JSON Lines) format.
           |Each line must contain exactly one JSON object that strictly adheres to the provided schema.
           |Do not include any explanations in the JSON objects themselves.
           |Do not include markdown code blocks or wrap responses in arrays.
           |Ensure RFC7464 compliant JSON Lines, one valid JSON object per line.
           |$thinkingInstructions
           |
           |Here is the JSON Schema instance each JSON object must adhere to:
           |```${getJsonSchema()}```
           |$exampleFormat
           |""".trimMargin()
    }

    private companion object {
        /**
         * Closing thinking tags, e.g. </think>, </analysis>. Same source and filter as ThinkingDetector:
         * the legacy prefix and no-prefix entries are not block tags.
         */
        val CLOSING_TAGS: List<String> = ThinkingTags.TAG_DEFINITIONS
            .filterKeys { it !in listOf("legacy_prefix", "no_prefix") }
            .values
            .map { it.second }
    }
}
