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
package com.embabel.agent.api.event.observation;

import com.embabel.agent.api.event.ToolCallRequestEvent;
import com.embabel.agent.api.event.ToolCallResponseEvent;
import com.embabel.agent.core.AgentProcess;
import com.embabel.common.ai.model.LlmOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Java-facing contract for {@link ToolCallOutcomes}: it exists so the (Java) observability module can
 * read the Kotlin {@code Result} on a tool-call response without reflection. This locks that the
 * accessors are callable from Java and map success/failure to plain nullable types.
 *
 * <p>A Kotlin {@code Result} crosses to Java <em>unboxed</em> (as {@code Object}): a success unboxes
 * to the value itself, a failure to a {@code kotlin.Result.Failure} sentinel (built via
 * {@link kotlin.ResultKt#createFailure}). No Kotlin fixture is needed.
 */
class ToolCallOutcomesJavaTest {

    private ToolCallResponseEvent response(Object unboxedResult) {
        ToolCallRequestEvent request = new ToolCallRequestEvent(
                mock(AgentProcess.class), null, "someTool", null, "{}", mock(LlmOptions.class), "corr-1");
        return request.responseEvent(unboxedResult, Duration.ofMillis(1));
    }

    @Test
    void successExposesResultTextAndNoError() {
        ToolCallResponseEvent event = response("tool output"); // a success unboxes to the value itself
        assertEquals("tool output", ToolCallOutcomes.resultText(event));
        assertNull(ToolCallOutcomes.error(event), "a successful call must have no error");
    }

    @Test
    void failureExposesThrowableAndNoResultText() {
        IllegalStateException boom = new IllegalStateException("boom");
        ToolCallResponseEvent event = response(kotlin.ResultKt.createFailure(boom)); // failure sentinel
        assertNull(ToolCallOutcomes.resultText(event), "a failed call must have no result text");
        assertSame(boom, ToolCallOutcomes.error(event), "the original throwable must be exposed unchanged");
    }
}
