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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ChatModelObservationFilter}. Built around the public {@link
 * ChatModelObservationFilter#map(Observation.Context)} surface: a real {@link
 * ChatModelObservationContext} is mapped and the resulting key values inspected.
 */
class ChatModelObservationFilterTest {

    private static Map<String, String> highCardinality(Observation.Context context) {
        return KeyValues.of(context.getHighCardinalityKeyValues())
                .stream()
                .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }

    private static Map<String, String> lowCardinality(Observation.Context context) {
        return KeyValues.of(context.getLowCardinalityKeyValues())
                .stream()
                .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }

    private static ChatModelObservationContext contextWith(Prompt prompt) {
        return ChatModelObservationContext.builder()
                .prompt(prompt)
                .provider("test")
                .build();
    }

    private static ChatModelObservationContext contextWith(Prompt prompt, ChatResponse response) {
        ChatModelObservationContext context = contextWith(prompt);
        context.setResponse(response);
        return context;
    }

    private static ChatResponse assistantResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
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

    @Nested
    @DisplayName("output messages")
    class OutputMessages {

        @Test
        @DisplayName("output.value bridge carries the completion text")
        void outputValueCarriesCompletion() {
            ChatModelObservationContext context = contextWith(
                    new Prompt(List.of(new UserMessage("user question"))),
                    assistantResponse("the answer"));

            ChatModelObservationFilter filter = new ChatModelObservationFilter(4000);
            Map<String, String> kv = highCardinality(filter.map(context));

            assertEquals("the answer", kv.get("output.value"));
        }

        @Test
        @DisplayName("gen_ai.output.messages spells the assistant role lowercase, mirroring the input side")
        void outputMessagesRoleIsLowercaseAssistant() {
            ChatModelObservationContext context = contextWith(
                    new Prompt(List.of(new UserMessage("user question"))),
                    assistantResponse("the answer"));

            ChatModelObservationFilter filter = new ChatModelObservationFilter(4000);
            Map<String, String> kv = highCardinality(filter.map(context));

            String outputMessages = kv.get("gen_ai.output.messages");
            assertTrue(outputMessages.contains("\"role\":\"assistant\""), outputMessages);
            assertTrue(outputMessages.contains("\"content\":\"the answer\""), outputMessages);
        }
    }

    @Nested
    @DisplayName("content capture disabled")
    class ContentCaptureDisabled {

        // captureMessageContent = false is the PII opt-out recommended by the GenAI convention:
        // no prompt/response body must leak into the span.
        private ChatModelObservationContext context() {
            return contextWith(
                    new Prompt(List.of(new SystemMessage("be brief"), new UserMessage("user question"))),
                    assistantResponse("the answer"));
        }

        @Test
        @DisplayName("no message-content attributes are emitted when captureMessageContent=false")
        void noContentAttributesWhenDisabled() {
            ChatModelObservationFilter filter = new ChatModelObservationFilter(4000, false);
            Map<String, String> kv = highCardinality(filter.map(context()));

            assertNull(kv.get("input.value"));
            assertNull(kv.get("output.value"));
            assertNull(kv.get("gen_ai.input.messages"));
            assertNull(kv.get("gen_ai.output.messages"));
        }

        @Test
        @DisplayName("non-content GenAI attributes are still emitted when content capture is off")
        void stillEmitsNonContentAttributes() {
            ChatModelObservationFilter filter = new ChatModelObservationFilter(4000, false);
            Observation.Context mapped = filter.map(context());

            assertEquals("chat", lowCardinality(mapped).get("gen_ai.operation.name"));
            assertEquals("test", lowCardinality(mapped).get("gen_ai.provider.name"));
        }
    }

    @Nested
    @DisplayName("truncation")
    class Truncation {

        @Test
        @DisplayName("input.value is truncated to maxAttributeLength with an ellipsis")
        void inputValueTruncated() {
            String longText = "x".repeat(100);
            ChatModelObservationContext context = contextWith(new Prompt(List.of(new UserMessage(longText))));

            ChatModelObservationFilter filter = new ChatModelObservationFilter(10);
            Map<String, String> kv = highCardinality(filter.map(context));

            String inputValue = kv.get("input.value");
            assertEquals(10 + "...".length(), inputValue.length(), inputValue);
            assertTrue(inputValue.endsWith("..."), inputValue);
        }
    }

    @Nested
    @DisplayName("non-chat-model contexts")
    class NonChatModelContexts {

        @Test
        @DisplayName("a non-ChatModel context is returned unchanged, with no attributes added")
        void nonChatModelContextUnchanged() {
            Observation.Context plain = new Observation.Context();

            Observation.Context result = new ChatModelObservationFilter(4000).map(plain);

            assertSame(plain, result);
            assertFalse(result.getHighCardinalityKeyValues().iterator().hasNext());
            assertFalse(result.getLowCardinalityKeyValues().iterator().hasNext());
        }
    }
}
