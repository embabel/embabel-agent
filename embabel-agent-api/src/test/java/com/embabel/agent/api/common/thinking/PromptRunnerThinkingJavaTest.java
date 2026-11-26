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
package com.embabel.agent.api.common.thinking;

import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.support.DelegatingStreamingPromptRunner;
import com.embabel.agent.api.common.support.PromptExecutionDelegate;
import com.embabel.common.core.thinking.ThinkingResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shows that Java code calls thinking on a {@link PromptRunner} directly.
 */
class PromptRunnerThinkingJavaTest {

    @Test
    void javaCallerRunsThinkingOnThePlatformRunner() {
        PromptExecutionDelegate delegate = mock(PromptExecutionDelegate.class);
        ThinkingResponse<String> expected = new ThinkingResponse<>("answer", List.of());
        // The provider reports no thinking support, which thinking() does not consult
        when(delegate.supportsThinking()).thenReturn(false);
        when(delegate.createObjectWithThinking(anyList(), eq(String.class))).thenReturn(expected);
        PromptRunner runner = new DelegatingStreamingPromptRunner(delegate);

        // The runner from the platform implements thinking(), so the caller needs no check first
        ThinkingResponse<String> response = runner.thinking().createObject("question", String.class);

        // The response is the one the delegate produced
        assertEquals(expected, response);
    }

}
