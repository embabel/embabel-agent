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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StreamingPromptRunnerBuilder}.
 */
@ExtendWith(MockitoExtension.class)
class StreamingPromptRunnerBuilderTest {

    @Mock
    private PromptRunner mockRunner;

    @Mock
    private StreamingPromptRunner.Streaming mockStreaming;

    @Mock
    private PromptRunner.StreamingCapability mockCapability;

    private LlmOptions mockLlm;

    @BeforeEach
    void setUp() {
        mockLlm = new LlmOptions();
    }

    @Test
    @DisplayName("Should create record with runner and provide access to runner field")
    void createsRecordWithRunner() {
        // Arrange & Act
        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);

        // Assert
        assertEquals(mockRunner, builder.runner());
    }

    @Test
    @DisplayName("Should return streaming capability when runner supports streaming")
    void streamingReturnsCapabilityWhenSupported() {
        // Arrange
        when(mockRunner.supportsStreaming()).thenReturn(true);
        when(mockRunner.streaming()).thenReturn(mockStreaming);
        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);

        // Act
        StreamingPromptRunner.Streaming result = builder.streaming();

        // Assert
        assertNotNull(result);
        assertEquals(mockStreaming, result);
        verify(mockRunner).supportsStreaming();
        verify(mockRunner).streaming();
    }

    @Test
    @DisplayName("Should throw UnsupportedOperationException when streaming is not supported")
    void streamingThrowsWhenNotSupported() {
        // Arrange
        when(mockRunner.supportsStreaming()).thenReturn(false);
        when(mockRunner.getLlm()).thenReturn(mockLlm);
        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);

        // Act & Assert
        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                builder::streaming);
        assertTrue(exception.getMessage().contains("This LLM does not support streaming"));
        verify(mockRunner).supportsStreaming();
        verify(mockRunner).getLlm();
        verify(mockRunner, never()).streaming();
    }

    @Test
    @DisplayName("Should throw NullPointerException when LLM options are null")
    void streamingThrowsNullPointerWhenLlmIsNull() {
        // Arrange
        when(mockRunner.supportsStreaming()).thenReturn(false);
        when(mockRunner.getLlm()).thenReturn(null);
        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);

        // Act & Assert
        assertThrows(NullPointerException.class, builder::streaming);
        verify(mockRunner).supportsStreaming();
        verify(mockRunner).getLlm();
        verify(mockRunner, never()).streaming();
    }

    @Test
    @DisplayName("Should throw IllegalStateException when capability implementation is unexpected type")
    void streamingThrowsWhenCapabilityIsUnexpectedType() {
        // Arrange
        when(mockRunner.supportsStreaming()).thenReturn(true);
        when(mockRunner.streaming()).thenReturn(mockCapability);
        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);

        // Act & Assert
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                builder::streaming);

        assertTrue(exception.getMessage().contains("Unexpected streaming capability implementation"));
        verify(mockRunner).supportsStreaming();
        verify(mockRunner).streaming();
    }

    @Test
    @DisplayName("Should delegate withStreaming() to streaming() (deprecated method)")
    void withStreamingDelegatesToStreaming() {
        // Arrange
        when(mockRunner.supportsStreaming()).thenReturn(true);
        when(mockRunner.streaming()).thenReturn(mockStreaming);
        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);

        // Act
        @SuppressWarnings("deprecation")
        StreamingPromptRunner.Streaming result = builder.withStreaming();

        // Assert
        assertNotNull(result);
        assertEquals(mockStreaming, result);
        verify(mockRunner).supportsStreaming();
        verify(mockRunner).streaming();
    }
}
