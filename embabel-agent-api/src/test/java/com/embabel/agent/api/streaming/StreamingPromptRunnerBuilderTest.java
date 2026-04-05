package com.embabel.agent.api.streaming;

import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.streaming.StreamingPromptRunner;
import com.embabel.common.ai.model.LlmOptions;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("Should create record with runner and provide access to runner field")
    void createsRecordWithRunner() {
        StreamingPromptRunnerBuilder builder = new StreamingPromptRunnerBuilder(mockRunner);

        assertNotNull(builder);
        assertEquals(mockRunner, builder.runner());
    }

    @Test
    @DisplayName("Should return streaming capability when runner supports streaming")
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
    @DisplayName("Should throw UnsupportedOperationException when streaming is not supported")
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
    @DisplayName("Should throw NullPointerException when LLM options are null")
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
    @DisplayName("Should throw IllegalStateException when capability implementation is unexpected type")
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
    @DisplayName("Should delegate withStreaming() to streaming() (deprecated method)")
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
