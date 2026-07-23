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
package com.embabel.agent.config.models.mistralai;

import java.util.List;
import java.util.Map;

/**
 * Flattens a Mistral reasoning model's structured assistant content to plain text.
 *
 * <p>Reasoning models ({@code magistral-*}) return an assistant {@code content} that is a list of
 * "thinking"/"text" chunks. {@code spring-ai-mistral-ai:1.1.7} assumes that content is a plain
 * {@code String} and throws on every call; this turns the chunk list back into the final answer text
 * so Spring AI can read it. Fixed natively in Spring AI 2.0.0 — this is temporary scaffolding.
 *
 * <p>Pure and side-effect free (no Spring, no HTTP), so it is unit-testable in isolation. Mirrors the
 * per-provider boundary-adapter style of {@code ToolResponseContentAdapter}, applied here to the
 * inbound (LLM&rarr;app) response rather than outbound tool content.
 */
final class MistralReasoningContent {

    private MistralReasoningContent() {
    }

    /**
     * Concatenates the final answer text from top-level {@code type=text} chunks. If the response is
     * pure reasoning with no final text chunk, falls back to the thinking text so nothing is lost.
     *
     * @param chunks the assistant message's {@code rawContent}, already known to be a list
     * @return the flattened text, possibly empty if no text could be extracted
     */
    static String flatten(List<?> chunks) {
        StringBuilder text = new StringBuilder();
        for (Object chunk : chunks) {
            if (chunk instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                append(text, map.get("text"));
            }
        }
        if (text.isEmpty()) {
            for (Object chunk : chunks) {
                if (chunk instanceof Map<?, ?> map && "thinking".equals(map.get("type"))
                        && map.get("thinking") instanceof List<?> thinking) {
                    for (Object part : thinking) {
                        if (part instanceof Map<?, ?> partMap && "text".equals(partMap.get("type"))) {
                            append(text, partMap.get("text"));
                        }
                    }
                }
            }
        }
        return text.toString();
    }

    /** Appends {@code value}, separating consecutive chunks with a newline so text isn't run together. */
    private static void append(StringBuilder sb, Object value) {
        if (value == null) {
            return;
        }
        String s = value.toString();
        if (s.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(s);
    }
}
