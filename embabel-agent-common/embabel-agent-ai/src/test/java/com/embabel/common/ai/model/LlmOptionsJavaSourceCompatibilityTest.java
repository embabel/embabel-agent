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
package com.embabel.common.ai.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Java source written before {@code reasoningEffort} calls {@code copy} with twelve arguments.
 * This class compiling is the check; the assertion pins that such a copy keeps the effort.
 */
class LlmOptionsJavaSourceCompatibilityTest {

    @Test
    @SuppressWarnings("deprecation")
    void theTwelveArgumentCopyStillCompilesAndKeepsTheEffort() {
        var original = LlmOptions.withModel("m").withReasoningEffort(ReasoningEffort.LOW);
        var copied = original.copy(null, "m", null, 0.3, null, null, null, null, null, null, null, Map.of());
        assertEquals(0.3, copied.getTemperature());
        assertEquals(ReasoningEffort.LOW, copied.getReasoningEffort());
    }
}
