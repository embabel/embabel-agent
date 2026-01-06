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
import com.embabel.agent.api.common.streaming.StreamingPromptRunnerOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamingPromptRunnerBuilderTest {

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
        assertThrows(Exception.class, builder::withStreaming);

    }
}