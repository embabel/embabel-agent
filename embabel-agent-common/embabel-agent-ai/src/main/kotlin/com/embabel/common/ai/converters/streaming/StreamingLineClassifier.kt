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

import com.embabel.common.ai.converters.streaming.support.ThinkingDetector
import com.embabel.common.core.streaming.StreamingEvent
import com.embabel.common.core.streaming.ThinkingState
import reactor.core.publisher.Flux

/**
 * Routes a single newline-delimited line from an LLM stream to the appropriate [StreamingEvent].
 *
 * Separates Reactor event-routing from pure thinking detection ([ThinkingDetector]),
 * so raw-string streaming shares the same dispatch logic in one place.
 */
object StreamingLineClassifier {

    // Matches bare markdown code-fence lines such as ```json or ``` that LLMs emit
    // as formatting artifacts between thinking blocks and JSON. These carry no content
    // and must be dropped before the thinking path sees them.
    // Aligns with the equivalent inline check in [StreamingJacksonOutputConverter.convertStreamWithThinking].
    private val codeFencePattern = Regex("^```\\w*$")

    /**
     * Classify a single [line] from a newline-delimited LLM stream into zero or one [StreamingEvent.Thinking].
     *
     * Every line arriving from the LLM is either:
     *  - **Thinking content** — wrapped in a tag such as `<think>...</think>` or a partial
     *    multi-line variant. These become [StreamingEvent.Thinking] events carrying the extracted
     *    text and a [ThinkingState] that tells the consumer whether this is a complete block,
     *    the start, a continuation, or the end of a multi-line block.
     *  - **A bare code fence** (e.g. ` ```json ` or ` ``` `) — a formatting artifact emitted
     *    by some models between thinking blocks and output. Always dropped.
     *  - **Anything else** — dropped; [StreamingEvent.Object] is not emitted in the
     *    raw-string streaming context this classifier serves.
     *
     * Detection is delegated to [ThinkingDetector]; this class only owns the Reactor mapping.
     *
     * @param line a complete newline-delimited line from the LLM stream (no trailing newline)
     * @return a [Flux] of at most one [StreamingEvent.Thinking], or empty when the line is dropped
     */
    fun classify(line: String): Flux<StreamingEvent<String>> {
        // Ask ThinkingDetector to classify the line. NONE means non-thinking content (dropped);
        // anything else (BOTH, START, CONTINUATION, END) means the line contains thinking markup.
        val state = ThinkingDetector.detectThinkingState(line)

        return when (state) {
            // Non-thinking line (e.g. a stray JSON line) — not expected in raw-string streaming.
            ThinkingState.NONE -> Flux.empty()

            // Thinking content line — but first filter out bare code fences (``` / ```json)
            // which are formatting artifacts that must not leak into thinking events.
            else -> if (!line.trim().matches(codeFencePattern))
                // Extract the actual thinking text (strips surrounding tags when present)
                // and emit a Thinking event with the detected state for multi-line tracking.
                Flux.just(StreamingEvent.Thinking(ThinkingDetector.extractThinkingContent(line), state))
            else
                // Bare code fence — drop silently.
                Flux.empty()
        }
    }
}
