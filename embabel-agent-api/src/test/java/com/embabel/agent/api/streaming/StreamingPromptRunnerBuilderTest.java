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

import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.streaming.StreamingPromptRunner;
import com.embabel.common.ai.model.LlmOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StreamingPromptRunnerBuilder}.
 */
class StreamingPromptRunnerBuilderTest {

    private final PromptRunner mockRunner = mock(PromptRunner.class);
    private final StreamingPromptRunner.Streaming mockStreaming = mock(StreamingPromptRunner.Streaming.class);
    private final PromptRunner.StreamingCapability mockCapability = mock(PromptRunner.StreamingCapability.class);
    private final LlmOptions mockLlm = new LlmOptions();

    @Test
    void createsRecordWithRunner() {
        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);

        assertNotNull(builder);
        assertEquals(mockRunner, builder.runner());
    }

    @Test
    void streamingReturnsCapabilityWhenSupported() {
        when(mockRunner.supportsStreaming()).thenReturn(true);
        when(mockRunner.streaming()).thenReturn(mockStreaming);

        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);
        StreamingPromptRunner.Streaming result = builder.streaming();

        assertNotNull(result);
        assertEquals(mockStreaming, result);
        verify(mockRunner).supportsStreaming();
        verify(mockRunner).streaming();
    }

    @Test
    void streamingThrowsWhenNotSupported() {
        when(mockRunner.supportsStreaming()).thenReturn(false);
        when(mockRunner.getLlm()).thenReturn(mockLlm);

        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);

        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                builder::streaming);
        assertTrue(exception.getMessage().contains("This LLM does not support streaming"));
        verify(mockRunner).supportsStreaming();
        verify(mockRunner).getLlm();
        verify(mockRunner, never()).streaming();
    }

    @Test
    void streamingThrowsNullPointerWhenLlmIsNull() {
        when(mockRunner.supportsStreaming()).thenReturn(false);
        when(mockRunner.getLlm()).thenReturn(null);

        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);

        assertThrows(NullPointerException.class, builder::streaming);
        verify(mockRunner).supportsStreaming();
        verify(mockRunner).getLlm();
        verify(mockRunner, never()).streaming();
    }

    @Test
    void streamingThrowsWhenCapabilityIsUnexpectedType() {
        when(mockRunner.supportsStreaming()).thenReturn(true);
        when(mockRunner.streaming()).thenReturn(mockCapability);

        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                builder::streaming);

        assertTrue(exception.getMessage().contains("Unexpected streaming capability implementation"));
        verify(mockRunner).supportsStreaming();
        verify(mockRunner).streaming();
    }

    @Test
    void withStreamingDelegatesToStreaming() {
        when(mockRunner.supportsStreaming()).thenReturn(true);
        when(mockRunner.streaming()).thenReturn(mockStreaming);

        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);

        @SuppressWarnings("deprecation")
        StreamingPromptRunner.Streaming result = builder.withStreaming();

        assertNotNull(result);
        assertEquals(mockStreaming, result);
        verify(mockRunner).supportsStreaming();
        verify(mockRunner).streaming();
    }
}
