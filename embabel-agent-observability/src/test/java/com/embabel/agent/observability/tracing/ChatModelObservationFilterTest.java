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
package com.embabel.agent.observability.tracing;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ChatModelObservationFilter}. Built around the public {@link
 * ChatModelObservationFilter#map(Observation.Context)} surface: a real {@link
 * ChatModelObservationContext} is mapped and the resulting high-cardinality key values inspected.
 */
class ChatModelObservationFilterTest {

    private static Map<String, String> highCardinality(Observation.Context context) {
        return KeyValues.of(context.getHighCardinalityKeyValues())
                .stream()
                .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }

    private static ChatModelObservationContext contextWith(Prompt prompt) {
        return ChatModelObservationContext.builder()
                .prompt(prompt)
                .provider("test")
                .build();
    }

    @Nested
    @DisplayName("role spelling")
    class RoleSpelling {

        @Test
        @DisplayName("input.value bridge spells roles lowercase, matching the OTel gen_ai.input.messages convention")
        void inputValueRolesAreLowercase() {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage("be brief"),
                    new UserMessage("user question")));

            ChatModelObservationFilter filter = new ChatModelObservationFilter(4000);
            Map<String, String> kv = highCardinality(filter.map(contextWith(prompt)));

            // OpenInference bridge: lowercase roles, consistent with the GenAI convention below.
            assertEquals("[system]: be brief\n[user]: user question", kv.get("input.value"));
        }

        @Test
        @DisplayName("gen_ai.input.messages already uses lowercase roles (non-regression)")
        void genAiInputMessagesRolesAreLowercase() {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage("be brief"),
                    new UserMessage("user question")));

            ChatModelObservationFilter filter = new ChatModelObservationFilter(4000);
            Map<String, String> kv = highCardinality(filter.map(contextWith(prompt)));

            String inputMessages = kv.get("gen_ai.input.messages");
            assertTrue(inputMessages.contains("\"role\":\"system\""), inputMessages);
            assertTrue(inputMessages.contains("\"role\":\"user\""), inputMessages);
        }
    }
}
