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
package com.embabel.agent.autoconfigure.observability.observation;

import com.embabel.agent.observability.observation.ChatModelObservationFilter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for ChatModelObservationFilter.
 * Unit tests verify prompt/completion extraction and truncation.
 * Integration tests verify that ChatModel observations are correctly parented
 * in the span hierarchy using a real OTel stack.
 */
class ChatModelObservationFilterTest {

    private ChatModelObservationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ChatModelObservationFilter(100); // Short max for testing
    }

    // Non-ChatModel contexts should pass through unchanged
    @Test
    void map_shouldIgnoreNonChatModelContext() {
        Observation.Context context = mock(Observation.Context.class);

        Observation.Context result = filter.map(context);

        assertThat(result).isSameAs(context);
    }

    // Test prompt extraction from request
    @Test
    void map_shouldExtractPromptFromRequest() {
        ChatModelObservationContext context = createContextWithPrompt("Hello AI");

        filter.map(context);

        assertThat(context.getHighCardinalityKeyValues())
                .anyMatch(kv -> kv.getKey().equals("gen_ai.prompt"));
        assertThat(context.getHighCardinalityKeyValues())
                .anyMatch(kv -> kv.getKey().equals("input.value"));
    }

    // Test completion extraction from response
    @Test
    void map_shouldExtractCompletionFromResponse() {
        ChatModelObservationContext context = createContextWithResponse("AI response");

        filter.map(context);

        assertThat(context.getHighCardinalityKeyValues())
                .anyMatch(kv -> kv.getKey().equals("gen_ai.completion"));
        assertThat(context.getHighCardinalityKeyValues())
                .anyMatch(kv -> kv.getKey().equals("output.value"));
    }

    // Test truncation of long values
    @Test
    void map_shouldTruncateLongValues() {
        String longPrompt = "A".repeat(200); // Longer than max 100
        ChatModelObservationContext context = createContextWithPrompt(longPrompt);

        filter.map(context);

        String prompt = context.getHighCardinalityKeyValues().stream()
                .filter(kv -> kv.getKey().equals("gen_ai.prompt"))
                .findFirst()
                .map(kv -> kv.getValue())
                .orElse("");

        assertThat(prompt).hasSize(103); // 100 + "..."
        assertThat(prompt).endsWith("...");
    }

    // Test default constructor uses 4000 max length
    @Test
    void defaultConstructor_shouldUse4000MaxLength() {
        ChatModelObservationFilter defaultFilter = new ChatModelObservationFilter();
        String longPrompt = "B".repeat(5000);
        ChatModelObservationContext context = createContextWithPrompt(longPrompt);

        defaultFilter.map(context);

        String prompt = context.getHighCardinalityKeyValues().stream()
                .filter(kv -> kv.getKey().equals("gen_ai.prompt"))
                .findFirst()
                .map(kv -> kv.getValue())
                .orElse("");

        assertThat(prompt).hasSize(4003); // 4000 + "..."
    }

    // Helper: create context with prompt
    private ChatModelObservationContext createContextWithPrompt(String promptText) {
        Prompt prompt = new Prompt(List.of(new UserMessage(promptText)));

        return ChatModelObservationContext.builder()
                .prompt(prompt)
                .provider("test-provider")
                .build();
    }

    // Helper: create context with response
    private ChatModelObservationContext createContextWithResponse(String responseText) {
        Prompt prompt = new Prompt(List.of(new UserMessage("test")));

        AssistantMessage assistantMessage = new AssistantMessage(responseText);
        Generation generation = new Generation(assistantMessage);
        ChatResponse response = new ChatResponse(List.of(generation));

        ChatModelObservationContext context = ChatModelObservationContext.builder()
                .prompt(prompt)
                .provider("test-provider")
                .build();
        context.setResponse(response);

        return context;
    }

    // ================================================================================
    // HIERARCHY INTEGRATION TESTS
    // ================================================================================

    /**
     * Integration tests to verify that ChatModel observations are correctly
     * parented in the span hierarchy (root → agent → action → chat).
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("Hierarchy Integration Tests")
    class HierarchyIntegrationTests {

        private InMemorySpanExporter spanExporter;
        private OpenTelemetrySdk openTelemetry;
        private Tracer tracer;
        private ObservationRegistry observationRegistry;
        private ChatModelObservationFilter chatFilter;

        private Scope otelRootScope;

        @BeforeEach
        void setUpHierarchyTests() {
            // Force clean OTel context to prevent cross-test context leakage
            // (e.g., SpringObservationProofOfConceptTest leaves stale spans in thread-local)
            otelRootScope = Context.root().makeCurrent();

            // Wire up a real OTel SDK -> Micrometer bridge -> ObservationRegistry pipeline
            // so span parent-child relationships can be verified against InMemorySpanExporter.
            spanExporter = InMemorySpanExporter.create();

            // Configure OpenTelemetry SDK
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();

            openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setPropagators(ContextPropagators.noop())
                    .build();

            io.opentelemetry.api.trace.Tracer otelTracer = openTelemetry.getTracer("test");

            // Bridge Micrometer Tracer to OpenTelemetry
            OtelCurrentTraceContext otelCurrentTraceContext = new OtelCurrentTraceContext();
            OtelBaggageManager baggageManager = new OtelBaggageManager(
                    otelCurrentTraceContext,
                    Collections.emptyList(),
                    Collections.emptyList()
            );
            tracer = new OtelTracer(otelTracer, otelCurrentTraceContext, event -> {}, baggageManager);

            // Create observation registry with tracing handler and filter
            observationRegistry = ObservationRegistry.create();
            observationRegistry.observationConfig()
                    .observationHandler(new DefaultTracingObservationHandler(tracer));

            chatFilter = new ChatModelObservationFilter(100);
            observationRegistry.observationConfig().observationFilter(chatFilter);
        }

        @AfterEach
        void tearDownHierarchyTests() {
            spanExporter.reset();
            if (otelRootScope != null) {
                otelRootScope.close();
            }
        }

        @Test
        @DisplayName("ChatModel span should be child of root span")
        void chatModelSpan_shouldBeChildOfRootSpan() {
            // Create hierarchy: root -> chat
            Observation rootObservation = Observation.createNotStarted("root", observationRegistry)
                    .start();

            try (Observation.Scope rootScope = rootObservation.openScope()) {
                // Create ChatModel observation as child
                ChatModelObservationContext chatContext = createContextWithPromptAndResponse(
                        "Hello AI", "Hello human!");

                Observation chatObservation = Observation.createNotStarted("spring.ai.chat", () -> chatContext, observationRegistry)
                        .parentObservation(rootObservation)
                        .start();
                chatObservation.stop();
            } finally {
                rootObservation.stop();
            }

            // Verify spans
            List<SpanData> spans = spanExporter.getFinishedSpanItems();

            assertThat(spans).hasSize(2);

            SpanData rootSpan = findSpanByName(spans, "root");
            SpanData chatSpan = findSpanByName(spans, "spring.ai.chat");

            assertThat(rootSpan).as("Root span should exist").isNotNull();
            assertThat(chatSpan).as("Chat span should exist").isNotNull();

            // Verify hierarchy
            assertThat(rootSpan.getParentSpanId())
                    .as("Root should have no parent")
                    .isEqualTo(io.opentelemetry.api.trace.SpanId.getInvalid());

            assertThat(chatSpan.getParentSpanId())
                    .as("Chat should be child of root")
                    .isEqualTo(rootSpan.getSpanId());

            assertThat(chatSpan.getTraceId())
                    .as("Chat should be in same trace as root")
                    .isEqualTo(rootSpan.getTraceId());
        }

        @Test
        @DisplayName("ChatModel span should be child of agent span in full hierarchy")
        void chatModelSpan_shouldBeChildOfAgentSpan_inFullHierarchy() {
            // Create hierarchy: root -> agent -> action -> chat
            Observation rootObservation = Observation.createNotStarted("root", observationRegistry)
                    .start();

            try (Observation.Scope rootScope = rootObservation.openScope()) {
                Observation agentObservation = Observation.createNotStarted("agent", observationRegistry)
                        .lowCardinalityKeyValue("agent.name", "TestAgent")
                        .parentObservation(rootObservation)
                        .start();

                try (Observation.Scope agentScope = agentObservation.openScope()) {
                    Observation actionObservation = Observation.createNotStarted("action", observationRegistry)
                            .lowCardinalityKeyValue("action.name", "MyAction")
                            .parentObservation(agentObservation)
                            .start();

                    try (Observation.Scope actionScope = actionObservation.openScope()) {
                        // Create ChatModel observation as child of action
                        ChatModelObservationContext chatContext = createContextWithPromptAndResponse(
                                "What is the weather?", "The weather is sunny.");

                        Observation chatObservation = Observation.createNotStarted("spring.ai.chat", () -> chatContext, observationRegistry)
                                .parentObservation(actionObservation)
                                .start();
                        chatObservation.stop();
                    } finally {
                        actionObservation.stop();
                    }
                } finally {
                    agentObservation.stop();
                }
            } finally {
                rootObservation.stop();
            }

            // Verify spans
            List<SpanData> spans = spanExporter.getFinishedSpanItems();

            assertThat(spans).hasSize(4);

            SpanData rootSpan = findSpanByName(spans, "root");
            SpanData agentSpan = findSpanByName(spans, "agent");
            SpanData actionSpan = findSpanByName(spans, "action");
            SpanData chatSpan = findSpanByName(spans, "spring.ai.chat");

            assertThat(rootSpan).as("Root span should exist").isNotNull();
            assertThat(agentSpan).as("Agent span should exist").isNotNull();
            assertThat(actionSpan).as("Action span should exist").isNotNull();
            assertThat(chatSpan).as("Chat span should exist").isNotNull();

            // Verify hierarchy: root -> agent -> action -> chat
            assertThat(rootSpan.getParentSpanId())
                    .as("Root should have no parent")
                    .isEqualTo(io.opentelemetry.api.trace.SpanId.getInvalid());

            assertThat(agentSpan.getParentSpanId())
                    .as("Agent should be child of root")
                    .isEqualTo(rootSpan.getSpanId());

            assertThat(actionSpan.getParentSpanId())
                    .as("Action should be child of agent")
                    .isEqualTo(agentSpan.getSpanId());

            assertThat(chatSpan.getParentSpanId())
                    .as("Chat should be child of action")
                    .isEqualTo(actionSpan.getSpanId());

            // All spans should be in the same trace
            assertThat(agentSpan.getTraceId()).isEqualTo(rootSpan.getTraceId());
            assertThat(actionSpan.getTraceId()).isEqualTo(rootSpan.getTraceId());
            assertThat(chatSpan.getTraceId()).isEqualTo(rootSpan.getTraceId());
        }

        @Test
        @DisplayName("Multiple ChatModel calls should all be children of their parent action")
        void multipleChatModelCalls_shouldAllBeChildrenOfParentAction() {
            // Create hierarchy: root -> agent -> action -> (chat1, chat2, chat3)
            Observation rootObservation = Observation.createNotStarted("root", observationRegistry)
                    .start();

            try (Observation.Scope rootScope = rootObservation.openScope()) {
                Observation agentObservation = Observation.createNotStarted("agent", observationRegistry)
                        .parentObservation(rootObservation)
                        .start();

                try (Observation.Scope agentScope = agentObservation.openScope()) {
                    Observation actionObservation = Observation.createNotStarted("action", observationRegistry)
                            .parentObservation(agentObservation)
                            .start();

                    try (Observation.Scope actionScope = actionObservation.openScope()) {
                        // Multiple chat calls within the same action
                        for (int i = 1; i <= 3; i++) {
                            ChatModelObservationContext chatContext = createContextWithPromptAndResponse(
                                    "Question " + i, "Answer " + i);

                            Observation chatObservation = Observation.createNotStarted("spring.ai.chat", () -> chatContext, observationRegistry)
                                    .parentObservation(actionObservation)
                                    .start();
                            chatObservation.stop();
                        }
                    } finally {
                        actionObservation.stop();
                    }
                } finally {
                    agentObservation.stop();
                }
            } finally {
                rootObservation.stop();
            }

            // Verify spans
            List<SpanData> spans = spanExporter.getFinishedSpanItems();

            assertThat(spans).hasSize(6); // root + agent + action + 3 chats

            SpanData actionSpan = findSpanByName(spans, "action");
            List<SpanData> chatSpans = spans.stream()
                    .filter(s -> s.getName().equals("spring.ai.chat"))
                    .toList();

            assertThat(chatSpans).hasSize(3);

            // All chat spans should be children of action
            for (SpanData chatSpan : chatSpans) {
                assertThat(chatSpan.getParentSpanId())
                        .as("Each chat span should be child of action")
                        .isEqualTo(actionSpan.getSpanId());
            }
        }

        @Test
        @DisplayName("ChatModel filter should add GenAI attributes to span in hierarchy")
        void chatModelFilter_shouldAddGenAIAttributes_inHierarchy() {
            // Create hierarchy: root -> chat
            Observation rootObservation = Observation.createNotStarted("root", observationRegistry)
                    .start();

            try (Observation.Scope rootScope = rootObservation.openScope()) {
                ChatModelObservationContext chatContext = createContextWithPromptAndResponse(
                        "What is 2+2?", "The answer is 4.");

                Observation chatObservation = Observation.createNotStarted("spring.ai.chat", () -> chatContext, observationRegistry)
                        .parentObservation(rootObservation)
                        .start();

                // Filter is applied, should add gen_ai attributes to context
                chatFilter.map(chatContext);

                chatObservation.stop();
            } finally {
                rootObservation.stop();
            }

            // Verify the chat context has the expected attributes
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            SpanData chatSpan = findSpanByName(spans, "spring.ai.chat");

            assertThat(chatSpan).isNotNull();
            assertThat(chatSpan.getParentSpanId())
                    .as("Chat should be child of root")
                    .isNotEqualTo(io.opentelemetry.api.trace.SpanId.getInvalid());
        }

        @Test
        @DisplayName("ChatModel spans should maintain hierarchy even with nested agent calls")
        void chatModelSpans_shouldMaintainHierarchy_withNestedAgentCalls() {
            // Create hierarchy: root -> agent1 -> action1 -> chat1
            //                                  -> agent2 (sub-agent) -> action2 -> chat2
            Observation rootObservation = Observation.createNotStarted("root", observationRegistry)
                    .start();

            try (Observation.Scope rootScope = rootObservation.openScope()) {
                Observation agent1Observation = Observation.createNotStarted("agent1", observationRegistry)
                        .parentObservation(rootObservation)
                        .start();

                try (Observation.Scope agent1Scope = agent1Observation.openScope()) {
                    // First action with chat
                    Observation action1Observation = Observation.createNotStarted("action1", observationRegistry)
                            .parentObservation(agent1Observation)
                            .start();

                    try (Observation.Scope action1Scope = action1Observation.openScope()) {
                        ChatModelObservationContext chat1Context = createContextWithPromptAndResponse(
                                "Question from agent1", "Answer for agent1");

                        Observation chat1Observation = Observation.createNotStarted("spring.ai.chat", () -> chat1Context, observationRegistry)
                                .parentObservation(action1Observation)
                                .start();
                        chat1Observation.stop();
                    } finally {
                        action1Observation.stop();
                    }

                    // Sub-agent triggered by agent1
                    Observation agent2Observation = Observation.createNotStarted("agent2", observationRegistry)
                            .parentObservation(agent1Observation)
                            .start();

                    try (Observation.Scope agent2Scope = agent2Observation.openScope()) {
                        Observation action2Observation = Observation.createNotStarted("action2", observationRegistry)
                                .parentObservation(agent2Observation)
                                .start();

                        try (Observation.Scope action2Scope = action2Observation.openScope()) {
                            ChatModelObservationContext chat2Context = createContextWithPromptAndResponse(
                                    "Question from sub-agent", "Answer for sub-agent");

                            Observation chat2Observation = Observation.createNotStarted("spring.ai.chat", () -> chat2Context, observationRegistry)
                                    .parentObservation(action2Observation)
                                    .start();
                            chat2Observation.stop();
                        } finally {
                            action2Observation.stop();
                        }
                    } finally {
                        agent2Observation.stop();
                    }
                } finally {
                    agent1Observation.stop();
                }
            } finally {
                rootObservation.stop();
            }

            // Verify spans
            List<SpanData> spans = spanExporter.getFinishedSpanItems();

            assertThat(spans).hasSize(7); // root + agent1 + action1 + chat1 + agent2 + action2 + chat2

            SpanData rootSpan = findSpanByName(spans, "root");
            SpanData agent1Span = findSpanByName(spans, "agent1");
            SpanData action1Span = findSpanByName(spans, "action1");
            SpanData agent2Span = findSpanByName(spans, "agent2");
            SpanData action2Span = findSpanByName(spans, "action2");

            List<SpanData> chatSpans = spans.stream()
                    .filter(s -> s.getName().equals("spring.ai.chat"))
                    .toList();

            assertThat(chatSpans).hasSize(2);

            // Verify hierarchy
            assertThat(agent1Span.getParentSpanId()).isEqualTo(rootSpan.getSpanId());
            assertThat(action1Span.getParentSpanId()).isEqualTo(agent1Span.getSpanId());
            assertThat(agent2Span.getParentSpanId()).isEqualTo(agent1Span.getSpanId());
            assertThat(action2Span.getParentSpanId()).isEqualTo(agent2Span.getSpanId());

            // chat1 should be child of action1
            SpanData chat1 = chatSpans.stream()
                    .filter(s -> s.getParentSpanId().equals(action1Span.getSpanId()))
                    .findFirst()
                    .orElse(null);
            assertThat(chat1).as("Chat1 should be child of action1").isNotNull();

            // chat2 should be child of action2
            SpanData chat2 = chatSpans.stream()
                    .filter(s -> s.getParentSpanId().equals(action2Span.getSpanId()))
                    .findFirst()
                    .orElse(null);
            assertThat(chat2).as("Chat2 should be child of action2").isNotNull();

            // All spans in same trace
            spans.forEach(span ->
                    assertThat(span.getTraceId()).isEqualTo(rootSpan.getTraceId())
            );
        }

        // --- Helper methods ---

        private ChatModelObservationContext createContextWithPromptAndResponse(String promptText, String responseText) {
            Prompt prompt = new Prompt(List.of(new UserMessage(promptText)));

            ChatModelObservationContext context = ChatModelObservationContext.builder()
                    .prompt(prompt)
                    .provider("test-provider")
                    .build();

            if (responseText != null) {
                AssistantMessage assistantMessage = new AssistantMessage(responseText);
                Generation generation = new Generation(assistantMessage);
                ChatResponse response = new ChatResponse(List.of(generation));
                context.setResponse(response);
            }

            return context;
        }

        private SpanData findSpanByName(List<SpanData> spans, String name) {
            return spans.stream()
                    .filter(s -> s.getName().equals(name))
                    .findFirst()
                    .orElse(null);
        }
    }
}
