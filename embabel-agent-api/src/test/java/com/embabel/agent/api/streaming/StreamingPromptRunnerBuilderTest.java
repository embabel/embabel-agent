package com.embabel.agent.api.streaming;

import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.streaming.StreamingPromptRunnerOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class StreamingPromptRunnerBuilderTest {

    @Mock
    private PromptRunner mockRunner;

    @Mock
    private StreamingPromptRunnerOperations mockOperations;

    @Test
    void shouldCreateBuilderWithRunner() {
        var builder = new StreamingPromptRunnerBuilder(mockRunner);
        assertNotNull(builder);
        assertEquals(mockRunner, builder.runner());
    }

    @Test
    void shouldReturnStreamingOperationsWhenSupported() {
        when(mockRunner.supportsStreaming()).thenReturn(true);
        when(mockRunner.stream()).thenReturn(mockOperations);

        var builder = new StreamingPromptRunnerBuilder(mockRunner);
        var result = builder.withStreaming();

        assertNotNull(result);
        assertEquals(mockOperations, result);
    }

    @Test
    void shouldThrowWhenStreamingNotSupported() {
        when(mockRunner.supportsStreaming()).thenReturn(false);

        var builder = new StreamingPromptRunnerBuilder(mockRunner);

        // Just test that some exception is thrown - don't worry about exact type
        assertThrows(Exception.class, () -> {
            builder.withStreaming();
        });
    }
}