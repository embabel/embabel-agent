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
package com.embabel.agent.openai;

import com.embabel.common.ai.model.LlmOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import static com.embabel.agent.openai.OpenAiReasoningEffort.getOpenAiReasoningEffort;
import static com.embabel.agent.openai.OpenAiReasoningEffort.withOpenAiReasoningEffort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenAiReasoningEffortJavaTest {

    @Test
    void javaCallersSetAndReadTheEffort() {
        var defaults = LlmOptions.withDefaultLlm();
        var configured = withOpenAiReasoningEffort(defaults, "low");

        assertEquals("low", getOpenAiReasoningEffort(configured));
        assertNull(getOpenAiReasoningEffort(defaults));
        var converter = new OpenAiReasoningEffortOptionsConverter(StandardOpenAiOptionsConverter.INSTANCE);
        var converted = (OpenAiChatOptions) converter.convertOptions(configured, "test-model");
        assertEquals("low", converted.getReasoningEffort());
    }
}
