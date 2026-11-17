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
package com.embabel.common.ai.decision;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class DecisionCallJavaTest {

    private static final DecisionOption<String> END_USER =
        DecisionOption.of("acme", "end-user", String.class);

    @Test
    void chainReadsOptionsBackTyped() {
        DecisionSpec spec = DecisionSpec.of(Questions.named("urgent").proposition("Is this urgent?").build());

        DecisionCall call = DecisionCall.of(DecisionRequest.of("hello", spec))
            .withOption(END_USER, "user-1")
            .withOption(DecisionOptions.TIMEOUT, Duration.ofSeconds(5));

        String endUser = call.option(END_USER);
        Duration timeout = call.option(DecisionOptions.TIMEOUT);
        assertEquals("user-1", endUser);
        assertEquals(Duration.ofSeconds(5), timeout);
        assertEquals(new TextDecisionInput("hello"), call.getInput());
        assertEquals(spec, call.getSpec());
        assertFalse(call.getNativeJsonRequired());
        assertEquals(2, call.getOptionKeys().size());
    }
}
