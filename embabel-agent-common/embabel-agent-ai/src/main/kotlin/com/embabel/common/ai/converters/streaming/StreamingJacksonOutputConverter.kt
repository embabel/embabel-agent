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
import com.fasterxml.jackson.databind.ObjectMapper
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
class StreamingJacksonOutputConverter<T> : FilteringJacksonOutputConverter<T> {

    constructor(
        clazz: Class<T>,
        objectMapper: ObjectMapper,
        fieldFilter: Predicate<Field> = Predicate { true },
    ) : super(clazz, objectMapper, fieldFilter)

    constructor(
        typeReference: ParameterizedTypeReference<T>,
        objectMapper: ObjectMapper,
        fieldFilter: Predicate<Field> = Predicate { true },
    ) : super(typeReference, objectMapper, fieldFilter)

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
     */
    fun convertStreamWithThinking(text: String): Flux<StreamingEvent<T>> {
        return Flux.fromIterable(text.lines())
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
                            // Line contains thinking content with detected state
                            val thinkingContent = ThinkingDetector.extractThinkingContent(line)
                            sink.next(StreamingEvent.Thinking(thinkingContent, thinkingState))
                        }
                    }
                } catch (e: Exception) {
                   sink.error(e)
                }
            }
    }

    /**
     * Override format to request JSONL instead of single JSON.
     * Inherits schema injection from parent but modifies instructions for streaming.
     */
    override fun getFormat(): String =
        """|
           |Your response should be in JSONL (JSON Lines) format.
           |Each line must contain exactly one JSON object that strictly adheres to the provided schema.
           |Do not include any explanations in the JSON objects themselves.
           |Do not include markdown code blocks or wrap responses in arrays.
           |Ensure RFC7464 compliant JSON Lines, one valid JSON object per line.
           |
           |You may include reasoning content using thinking blocks.
           |Use EXACTLY the <think> tag format - do not use variations like <thinking>, <thought>, or <analysis>.
           |<think>your reasoning here
           |another line of thinking</think>
           |
           |Thinking blocks are separate from JSON objects and can appear before, between, or after JSON lines as needed for your analysis.
           |
           |Here is the JSON Schema instance each JSON object must adhere to:
           |```${jsonSchema}```
           |
           |Example format:
           |<think>analyzing the requirements</think>
           |{"field": "precise_value"}
           |<think>considering next item</think>
           |{"field": "another_precise_value"}
           |""".trimMargin()
}
