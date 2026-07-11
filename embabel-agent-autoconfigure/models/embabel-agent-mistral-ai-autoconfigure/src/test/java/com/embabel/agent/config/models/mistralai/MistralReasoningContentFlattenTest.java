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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link MistralReasoningContent#flatten}, the parser that turns a reasoning
 * model's structured chunk list into plain text. No Spring context, no HTTP — unlike
 * {@code MistralAiReasoningContentTest}, which drives the whole decorator over a local server.
 *
 * <p>Same package as the class under test so the package-private entry point is visible.
 */
class MistralReasoningContentFlattenTest {

    @Test
    void topLevelTextChunkIsReturned() {
        var chunks = List.<Object> of(Map.of("type", "text", "text", "READY"));

        assertThat(MistralReasoningContent.flatten(chunks)).isEqualTo("READY");
    }

    @Test
    void finalTextWinsOverThinking() {
        var chunks = List.<Object> of(
                Map.of("type", "thinking",
                        "thinking", List.of(Map.of("type", "text", "text", "The user wants exactly READY.")),
                        "closed", true),
                Map.of("type", "text", "text", "READY"));

        assertThat(MistralReasoningContent.flatten(chunks)).isEqualTo("READY");
    }

    @Test
    void pureReasoningFallsBackToThinkingText() {
        var chunks = List.<Object> of(
                Map.of("type", "thinking",
                        "thinking", List.of(Map.of("type", "text", "text", "only thinking here")),
                        "closed", true));

        assertThat(MistralReasoningContent.flatten(chunks)).isEqualTo("only thinking here");
    }

    @Test
    void multipleTextChunksAreJoinedWithNewline() {
        var chunks = List.<Object> of(
                Map.of("type", "text", "text", "first"),
                Map.of("type", "text", "text", "second"));

        assertThat(MistralReasoningContent.flatten(chunks)).isEqualTo("first\nsecond");
    }

    @Test
    void emptyTextChunksDoNotProduceDanglingNewlines() {
        var chunks = List.<Object> of(
                Map.of("type", "text", "text", "kept"),
                Map.of("type", "text", "text", ""));

        assertThat(MistralReasoningContent.flatten(chunks)).isEqualTo("kept");
    }

    @Test
    void emptyChunkListYieldsEmptyText() {
        assertThat(MistralReasoningContent.flatten(List.of())).isEmpty();
    }

    @Test
    void unknownChunkTypesAreIgnored() {
        var chunks = List.<Object> of(Map.of("type", "redacted_reasoning", "data", "x"));

        assertThat(MistralReasoningContent.flatten(chunks)).isEmpty();
    }
}
