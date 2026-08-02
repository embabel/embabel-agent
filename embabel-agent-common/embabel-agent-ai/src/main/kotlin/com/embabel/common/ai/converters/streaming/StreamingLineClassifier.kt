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
 * Routes a single newline-delimited line from an LLM stream to a thinking event or drops it.
 *
 * Separates Reactor event-routing from pure thinking detection ([ThinkingDetector]),
 * so raw-string streaming shares the same dispatch logic in one place.
 * Object creation is deliberately outside this classifier's responsibility.
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
     * Every line arriving from the LLM is handled as follows:
     *  - **Any non-JSON line** — emitted as [StreamingEvent.Thinking]. Explicit thinking tags are
     *    extracted and assigned their matching [ThinkingState]; untagged text is emitted with
     *    [ThinkingState.CONTINUATION].
     *  - **A JSON object or JSON string** — dropped. Object creation belongs to the object-stream APIs.
     *  - **A bare code fence** (e.g. ` ```json ` or ` ``` `) — a formatting artifact emitted
     *    by some models between thinking blocks and output. Always dropped.
     *
     * Detection is delegated to [ThinkingDetector]; this class only owns the Reactor mapping.
     *
     * Examples:
     *  - `<think>reasoning</think>` -> `Thinking("reasoning", BOTH)`
     *  - `plain answer` -> `Thinking("plain answer", CONTINUATION)`
     *  - `{"answer":"done"}` -> no event
     *  - `"done"` -> no event
     *
     * @param line a complete newline-delimited line from the LLM stream (no trailing newline)
     * @return a [Flux] of at most one [StreamingEvent.Thinking], or empty when the line is dropped
     */
    fun classify(line: String): Flux<StreamingEvent<String>> {
        // NONE identifies structured output; every other state is emitted as thinking.
        val state = ThinkingDetector.detectThinkingState(line)

        return when (state) {
            // Structured JSON belongs to createObjectStream and is omitted here.
            ThinkingState.NONE -> Flux.empty()

            // Bare fences are formatting artifacts. All other non-JSON lines become Thinking.
            else -> if (!line.trim().matches(codeFencePattern))
                Flux.just(StreamingEvent.Thinking(ThinkingDetector.extractThinkingContent(line), state))
            else
                Flux.empty()
        }
    }
}
