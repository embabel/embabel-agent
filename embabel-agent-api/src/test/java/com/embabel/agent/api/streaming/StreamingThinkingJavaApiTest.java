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
package com.embabel.agent.api.streaming;

import com.embabel.agent.api.common.streaming.StreamingPromptRunner;
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer;
import com.embabel.agent.spi.loop.streaming.LlmStreamChunk;
import com.embabel.common.core.streaming.StreamingEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingThinkingJavaApiTest {

    @Test
    void exposesThinkingAwareTextStreamingToJava() throws NoSuchMethodException {
        var method = StreamingPromptRunner.Streaming.class.getMethod("generateStreamWithThinking");

        assertThat(method.getReturnType()).isEqualTo(Flux.class);
    }

    Flux<StreamingEvent<String>> callFromJava(StreamingPromptRunner.Streaming streaming) {
        return streaming.generateStreamWithThinking();
    }

    @Test
    void keepsLlmMessageStreamerAJavaSamWithStructuredDefault() {
        LlmMessageStreamer streamer = (messages, tools, inspectors) -> Flux.just("answer");

        LlmStreamChunk chunk = streamer.streamWithThinking(java.util.List.of(), java.util.List.of(), java.util.List.of())
                .blockFirst();

        assertThat(chunk).isNotNull();
        assertThat(chunk.getTextContent()).isEqualTo("answer");
        assertThat(chunk.getThinkingContent()).isEmpty();
    }
}
