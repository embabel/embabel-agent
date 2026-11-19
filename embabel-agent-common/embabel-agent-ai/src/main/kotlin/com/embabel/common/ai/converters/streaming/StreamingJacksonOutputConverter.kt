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
     * Before classification, a line holding reasoning followed by a JSON object on the same line
     * is split into two lines (see [splitReasoningAndTrailingJson]), so the object is emitted
     * instead of being lost inside the reasoning. The reactive pipeline below is unchanged:
     * each line still produces at most one event.
     */
    fun convertStreamWithThinking(text: String): Flux<StreamingEvent<T>> {
        return Flux.fromIterable(text.lines().flatMap { splitReasoningAndTrailingJson(it) })
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
     * Splits a line that holds reasoning followed by a JSON object on the same line into two
     * lines: the reasoning, then the JSON object. See issue #2267.
     *
     * Two forms are handled:
     * - plain text before the JSON, e.g. `I'll check all three options.{"name": "x"}`. This
     *   typically forms in a tool-calling stream: text written before a tool call has no trailing
     *   line break, so the line buffer joins it to the first JSON object of the next turn.
     * - a thinking block before the JSON, e.g. `<think>reasoning</think>{"name": "x"}`.
     *
     * Without the split, the whole line is classified as thinking and the object is lost.
     *
     * The split only inserts the line break the model left out. It adds no classification rules:
     * both parts are classified afterwards by [ThinkingDetector], exactly as if the model had written
     * them on separate lines. The line is returned unchanged unless [isSplitPoint] accepts a split.
     *
     * Candidates are tried from the leftmost `{`, so a `{` inside the reasoning text, such as
     * `options {a, b}: {"name": "x"}`, does not prevent the split.
     *
     * @param line one complete line of the streamed response
     * @return the line unchanged, or the reasoning line followed by the JSON line
     */
    private fun splitReasoningAndTrailingJson(line: String): List<String> {
        // Work on the line without surrounding whitespace; rawChunksToLines already trims, tests may not.
        val trimmed = line.trim()
        // Only a line ending with '}' can end with a JSON object; anything else is returned as is.
        if (!trimmed.endsWith('}')) return listOf(line)
        // The detector already classifies this line as JSON: nothing to split.
        if (ThinkingDetector.detectThinkingState(trimmed) == ThinkingState.NONE) return listOf(line)
        // A legacy "//THINKING:" line is thinking up to its end, so JSON after it belongs to the reasoning.
        // Same literal as ThinkingDetector, which treats the whole line after the prefix as thinking (BOTH).
        if (trimmed.startsWith("//THINKING:")) {
            // Record why a line ending with '}' was kept whole, e.g. "//THINKING: text {"name":"x"}".
            logger.debug("Legacy thinking line ends with '}', not split: {}", trimmed)
            return listOf(line)
        }
        // First candidate: the first '{' after position 0, so the reasoning part is never empty.
        var start = trimmed.indexOf('{', startIndex = 1)
        // Try each '{' from left to right; the leftmost valid one gives the whole trailing object.
        while (start > 0) {
            // Everything before the candidate '{' is the reasoning, e.g. "my reasoning".
            val reasoning = trimmed.substring(0, start).trim()
            // Everything from the candidate '{' to the end of the line, e.g. {"name":"x"}.
            val json = trimmed.substring(start)
            // Split here only if the reasoning/JSON pair passes every check.
            if (isSplitPoint(reasoning, json)) {
                // Record the split, e.g. reasoning='my reasoning', json='{"name":"x"}'.
                logger.debug("Split reasoning and JSON object on the same line: reasoning='{}', json='{}'", reasoning, json)
                // Reasoning first, then JSON: the order the model wrote them.
                //   "my reasoning {"name":"x"}" -> ["my reasoning", "{"name":"x"}"]
                return listOf(reasoning, json)
            }
            // This '{' was inside the reasoning, e.g. "options {a, b}: {"name":"x"}"; try the next '{'.
            start = trimmed.indexOf('{', start + 1)
        }
        // No candidate qualified: keep today's behaviour, the whole line is reasoning.
        // Record it, e.g. "options {a, b}" or "text {"other":1}" (valid JSON, wrong shape for T).
        logger.debug("Line ends with '}' but no JSON object of the target type follows the reasoning, not split: {}", trimmed)
        return listOf(line)
    }

    /**
     * Whether splitting a line into [reasoning] and [json] is safe: after the split, ThinkingDetector
     * must classify [reasoning] as thinking and [json] as JSON, and [json] must convert to [T].
     * Each check returns false (no split) as soon as one fails.
     */
    private fun isSplitPoint(reasoning: String, json: String): Boolean {
        // 1. The reasoning must not leave a thinking block open; JSON inside an open block belongs to it.
        //      "my reasoning"       -> CONTINUATION (no tags)  -> pass
        //      "<think>r</think>"   -> BOTH (block closed)     -> pass
        //      "<think>example"     -> START (block open)      -> fail
        if (ThinkingDetector.detectThinkingState(reasoning) == ThinkingState.START) return false

        // 2. The detector must classify the JSON part as JSON, because handle uses the same detector.
        //    The detector checks for thinking tags before JSON, so valid JSON holding a tag is thinking to it.
        //      {"name":"x"}                  -> NONE -> pass
        //      {"name":"<think>a</think>"}   -> BOTH -> fail (handle would emit Thinking anyway)
        if (ThinkingDetector.detectThinkingState(json) != ThinkingState.NONE) return false

        // 3. The JSON part must parse as a non-empty JSON object. Cheap and silent, so braces in
        //    ordinary reasoning are rejected before conversion, which logs an ERROR when it fails.
        //      {"name":"x"}   -> pass
        //      {a, b}         -> fail (not JSON)
        if (!isJsonObject(json)) return false

        // 4. The JSON part must convert to T. handle converts it again, and a failure there would end
        //    the stream with an error, so splitting only on success adds no new error path.
        //      {"name":"x"}   -> pass
        //      {"other":1}    -> fail (valid JSON, wrong shape for T)
        return convertsToTarget(json)
    }

    /**
     * Whether [candidate] parses as a non-empty JSON object.
     *
     * Used as a cheap filter before [convertsToTarget], so that braces in ordinary reasoning
     * text, such as `{a, b}`, are rejected without attempting conversion. Conversion failures
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
     * so valid JSON of a different shape stays in the reasoning as before.
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
}
