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

import com.embabel.agent.observability.observation.EmbabelFullObservationEventListener;
import com.embabel.agent.observability.observation.EmbabelObservationContext;
import com.embabel.agent.observability.observation.EmbabelTracingObservationHandler;
import com.embabel.agent.observability.observation.NonEmbabelTracingObservationHandler;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.event.*;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.core.Goal;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.ToolGroupMetadata;
import com.embabel.agent.autoconfigure.observability.MicrometerTracingAutoConfiguration;
import com.embabel.agent.observability.ObservabilityProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Integration test to verify that ObservabilityToolCallback from embabel-agent
 * integrates correctly with EmbabelFullObservationEventListener when
 * trace-tool-calls is disabled.
 *
 * <p>This test validates the recommended configuration:
 * <ul>
 *   <li>trace-tool-calls: false (disable tool tracing in EmbabelFullObservationEventListener)</li>
 *   <li>ObservabilityToolCallback creates tool spans using standard Observation.Context</li>
 *   <li>Tool spans are correctly parented to Action spans via tracer context propagation</li>
 *   <li>No duplicate tool spans are created</li>
 * </ul>
 *
 * @author Quantpulsar 2025-2026
 */
class ObservabilityToolCallbackIntegrationTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;
    private Tracer tracer;
    private io.opentelemetry.api.trace.Tracer otelTracer;
    private ObservationRegistry observationRegistry;
    private EmbabelTracingObservationHandler embabelHandler;
    private TracingObservationHandler<?> nonEmbabelHandler;
    private ObservabilityProperties properties;
    private EmbabelFullObservationEventListener listener;

    @BeforeEach
    void setUp() {
        // In-memory exporter captures spans for assertions
        spanExporter = InMemorySpanExporter.create();

        // Configure OpenTelemetry SDK
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();

        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.noop())
                .build();

        otelTracer = openTelemetry.getTracer("test");

        // Bridge Micrometer Tracer to OpenTelemetry
        OtelCurrentTraceContext otelCurrentTraceContext = new OtelCurrentTraceContext();
        OtelBaggageManager baggageManager = new OtelBaggageManager(
                otelCurrentTraceContext,
                Collections.emptyList(),
                Collections.emptyList()
        );
        tracer = new OtelTracer(otelTracer, otelCurrentTraceContext, event -> {}, baggageManager);

        // Create handlers:
        // 1. EmbabelTracingObservationHandler for EmbabelObservationContext
        // 2. NonEmbabelTracingObservationHandler for standard contexts (like from ObservabilityToolCallback)
        //    This handler excludes EmbabelObservationContext to prevent conflicts
        embabelHandler = new EmbabelTracingObservationHandler(tracer);
        nonEmbabelHandler = new NonEmbabelTracingObservationHandler(tracer);

        // Register both handlers - order matters!
        // Embabel handler first (for EmbabelObservationContext), then non-Embabel handler (for standard contexts)
        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(embabelHandler)
                .observationHandler(nonEmbabelHandler);

        // Create listener with trace-tool-calls disabled (recommended config)
        properties = new ObservabilityProperties();
        properties.setTraceToolCalls(false);  // KEY: Disable to avoid double tracing
        listener = new EmbabelFullObservationEventListener(observationRegistry, properties);
    }

    @AfterEach
    void tearDown() {
        spanExporter.reset();
    }

    // --- Main Integration Test ---

    @Test
    @DisplayName("Tool call via ObservabilityToolCallback should be child of Action when trace-tool-calls is false")
    void toolCallViaObservabilityToolCallback_shouldBeChildOfAction_whenTraceToolCallsDisabled() {
        // Setup: Agent with action
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
        ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

        // Execute full lifecycle:
        // 1. Agent starts
        listener.onProcessEvent(new AgentProcessCreationEvent(process));

        // 2. Action starts (opens scope - makes action span current in tracer)
        listener.onProcessEvent(actionStart);

        // 3. Simulate ObservabilityToolCallback creating a tool observation
        //    This uses STANDARD Observation.Context, not EmbabelObservationContext
        simulateObservabilityToolCallbackCall("WebSearch", "{\"query\": \"test\"}");

        // 4. Action completes
        listener.onProcessEvent(actionResult);

        // 5. Agent completes
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify spans
        List<SpanData> spans = spanExporter.getFinishedSpanItems();

        // Should have exactly 3 spans: Agent, Action, Tool (NO duplicates)
        assertThat(spans).hasSize(3);

        // Find spans by their actual names
        SpanData agentSpan = findSpanByName(spans, "TestAgent");
        SpanData actionSpan = findSpanByName(spans, "MyAction");
        SpanData toolSpan = findSpanByName(spans, "tool call");

        assertThat(agentSpan).as("Agent span should exist").isNotNull();
        assertThat(actionSpan).as("Action span should exist").isNotNull();
        assertThat(toolSpan).as("Tool span should exist").isNotNull();

        // Verify hierarchy: Agent -> Action -> Tool
        assertThat(agentSpan.getParentSpanId())
                .as("Agent should be root (no parent)")
                .isEqualTo(io.opentelemetry.api.trace.SpanId.getInvalid());

        assertThat(actionSpan.getParentSpanId())
                .as("Action should be child of Agent")
                .isEqualTo(agentSpan.getSpanId());

        assertThat(toolSpan.getParentSpanId())
                .as("Tool should be child of Action")
                .isEqualTo(actionSpan.getSpanId());

        // All spans should be in the same trace
        assertThat(actionSpan.getTraceId()).isEqualTo(agentSpan.getTraceId());
        assertThat(toolSpan.getTraceId()).isEqualTo(agentSpan.getTraceId());
    }

    @Test
    @DisplayName("No duplicate tool spans when trace-tool-calls is false")
    void noDuplicateToolSpans_whenTraceToolCallsDisabled() {
        // Setup
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
        ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(actionStart);

        // Both would normally create tool spans:
        // 1. EmbabelFullObservationEventListener via onToolCallRequest (disabled)
        // 2. ObservabilityToolCallback (active)

        // Simulate EmbabelFullObservationEventListener receiving the events
        // (Should be ignored because trace-tool-calls is false)
        ToolCallRequestEvent toolRequest = createToolCallRequestEvent(process, "WebSearch", "query");
        ToolCallResponseEvent toolResponse = createToolCallResponseEvent(process, "WebSearch");
        listener.onProcessEvent(toolRequest);

        // Simulate ObservabilityToolCallback (should create span)
        simulateObservabilityToolCallbackCall("WebSearch", "{\"query\": \"test\"}");

        listener.onProcessEvent(toolResponse);
        listener.onProcessEvent(actionResult);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: Only ONE tool span
        List<SpanData> spans = spanExporter.getFinishedSpanItems();

        long toolSpanCount = spans.stream()
                .filter(s -> s.getName().contains("tool"))
                .count();

        assertThat(toolSpanCount)
                .as("Should have exactly 1 tool span (no duplicates)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Multiple tool calls should all be children of Action")
    void multipleToolCalls_shouldAllBeChildrenOfAction() {
        // Setup
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
        ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(actionStart);

        // Multiple tool calls within the same action
        simulateObservabilityToolCallbackCall("WebSearch", "{\"query\": \"first\"}");
        simulateObservabilityToolCallbackCall("Calculator", "{\"expression\": \"2+2\"}");
        simulateObservabilityToolCallbackCall("WebSearch", "{\"query\": \"second\"}");

        listener.onProcessEvent(actionResult);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(5); // 1 Agent + 1 Action + 3 Tools

        // Find action span by its actual name
        SpanData actionSpan = findSpanByName(spans, "MyAction");
        List<SpanData> toolSpans = spans.stream()
                .filter(s -> s.getName().equals("tool call"))
                .toList();

        assertThat(actionSpan).as("Action span should exist").isNotNull();
        assertThat(toolSpans).hasSize(3);

        // All tool spans should be children of the action
        for (SpanData toolSpan : toolSpans) {
            assertThat(toolSpan.getParentSpanId())
                    .as("Each tool span should be child of action")
                    .isEqualTo(actionSpan.getSpanId());
        }
    }

    @Test
    @DisplayName("Tool span should have correct attributes from ObservabilityToolCallback")
    void toolSpan_shouldHaveCorrectAttributes() {
        // Setup
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
        ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(actionStart);
        simulateObservabilityToolCallbackCall("WebSearch", "{\"query\": \"test query\"}");
        listener.onProcessEvent(actionResult);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify tool span attributes
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData toolSpan = findSpanByName(spans, "tool call");

        assertThat(toolSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("toolName")))
                .isEqualTo("WebSearch");

        assertThat(toolSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("payload")))
                .isEqualTo("{\"query\": \"test query\"}");

        assertThat(toolSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("status")))
                .isEqualTo("success");
    }

    @Test
    @DisplayName("Tool span with error should have error attributes")
    void toolSpanWithError_shouldHaveErrorAttributes() {
        // Setup
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
        ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "FAILED");

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(actionStart);
        simulateObservabilityToolCallbackCallWithError("WebSearch", "{\"query\": \"test\"}",
                new RuntimeException("Network error"));
        listener.onProcessEvent(actionResult);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData toolSpan = findSpanByName(spans, "tool call");

        assertThat(toolSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("status")))
                .isEqualTo("error");

        assertThat(toolSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("error_type")))
                .isEqualTo("RuntimeException");
    }

    @Test
    @DisplayName("Double tracing occurs when trace-tool-calls is true (demonstrates the problem)")
    void doubleTracing_occursWhenTraceToolCallsEnabled() {
        // Enable trace-tool-calls to demonstrate the duplication problem
        properties.setTraceToolCalls(true);

        AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
        ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(actionStart);

        // Both create spans now:
        // 1. EmbabelFullObservationEventListener (because trace-tool-calls=true)
        ToolCallRequestEvent toolRequest = createToolCallRequestEvent(process, "WebSearch", "query");
        ToolCallResponseEvent toolResponse = createToolCallResponseEvent(process, "WebSearch");
        listener.onProcessEvent(toolRequest);

        // 2. ObservabilityToolCallback
        simulateObservabilityToolCallbackCall("WebSearch", "{\"query\": \"test\"}");

        listener.onProcessEvent(toolResponse);
        listener.onProcessEvent(actionResult);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: TWO tool spans (duplication!)
        List<SpanData> spans = spanExporter.getFinishedSpanItems();

        long toolSpanCount = spans.stream()
                .filter(s -> s.getName().contains("tool") || s.getName().contains("WebSearch"))
                .count();

        assertThat(toolSpanCount)
                .as("With trace-tool-calls=true, there should be 2 tool spans (duplication problem)")
                .isEqualTo(2);
    }

    // --- Tests for Tool Call Parent Resolution with Current Span ---

    @Test
    @DisplayName("Embabel tool call should be child of simulated ChatClient span when trace-tool-calls is enabled")
    void embabelToolCall_shouldBeChildOfChatClientSpan_whenTraceToolCallsEnabled() {
        // Enable trace-tool-calls to test Embabel's own tool tracing
        properties.setTraceToolCalls(true);

        AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
        ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");
        ToolCallRequestEvent toolRequest = createToolCallRequestEvent(process, "WebSearch", "{\"query\": \"test\"}");
        ToolCallResponseEvent toolResponse = createToolCallResponseEvent(process, "WebSearch");

        // 1. Start agent and action
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(actionStart);

        // 2. Simulate Spring AI ChatClient creating a span (using standard observation)
        //    This simulates what happens when ChatClient.call() is invoked
        Observation chatClientObservation = Observation.createNotStarted("ChatClient", observationRegistry)
                .lowCardinalityKeyValue("gen_ai.operation.name", "chat")
                .start();
        Observation.Scope chatScope = chatClientObservation.openScope();

        try {
            // 3. Now Embabel tool event arrives - should become child of ChatClient span
            listener.onProcessEvent(toolRequest);
            listener.onProcessEvent(toolResponse);
        } finally {
            chatScope.close();
            chatClientObservation.stop();
        }

        // 4. Complete action and agent
        listener.onProcessEvent(actionResult);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify span hierarchy
        List<SpanData> spans = spanExporter.getFinishedSpanItems();

        SpanData agentSpan = findSpanByName(spans, "TestAgent");
        SpanData actionSpan = findSpanByName(spans, "MyAction");
        // Micrometer converts "ChatClient" to "chat-client" (lowercase with dashes)
        SpanData chatClientSpan = findSpanByName(spans, "chat-client");
        SpanData toolSpan = findSpanByName(spans, "tool:WebSearch");

        assertThat(agentSpan).as("Agent span should exist").isNotNull();
        assertThat(actionSpan).as("Action span should exist").isNotNull();
        assertThat(chatClientSpan).as("ChatClient span should exist").isNotNull();
        assertThat(toolSpan).as("Tool span should exist").isNotNull();

        // Verify hierarchy: Agent -> Action -> ChatClient -> Tool
        assertThat(actionSpan.getParentSpanId())
                .as("Action should be child of Agent")
                .isEqualTo(agentSpan.getSpanId());

        assertThat(chatClientSpan.getParentSpanId())
                .as("ChatClient should be child of Action")
                .isEqualTo(actionSpan.getSpanId());

        assertThat(toolSpan.getParentSpanId())
                .as("Tool should be child of ChatClient (not Action directly)")
                .isEqualTo(chatClientSpan.getSpanId());

        // All in same trace
        assertThat(toolSpan.getTraceId()).isEqualTo(agentSpan.getTraceId());
    }

    @Test
    @DisplayName("Embabel tool call should fallback to Action when no ChatClient span is active")
    void embabelToolCall_shouldFallbackToAction_whenNoChatClientSpan() {
        // Enable trace-tool-calls
        properties.setTraceToolCalls(true);

        AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
        ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");
        ToolCallRequestEvent toolRequest = createToolCallRequestEvent(process, "WebSearch", "{\"query\": \"test\"}");
        ToolCallResponseEvent toolResponse = createToolCallResponseEvent(process, "WebSearch");

        // Execute without any ChatClient span
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(actionStart);

        // Tool call with no intermediate ChatClient span
        listener.onProcessEvent(toolRequest);
        listener.onProcessEvent(toolResponse);

        listener.onProcessEvent(actionResult);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify span hierarchy
        List<SpanData> spans = spanExporter.getFinishedSpanItems();

        SpanData agentSpan = findSpanByName(spans, "TestAgent");
        SpanData actionSpan = findSpanByName(spans, "MyAction");
        SpanData toolSpan = findSpanByName(spans, "tool:WebSearch");

        assertThat(agentSpan).as("Agent span should exist").isNotNull();
        assertThat(actionSpan).as("Action span should exist").isNotNull();
        assertThat(toolSpan).as("Tool span should exist").isNotNull();

        // Verify fallback hierarchy: Agent -> Action -> Tool
        assertThat(actionSpan.getParentSpanId())
                .as("Action should be child of Agent")
                .isEqualTo(agentSpan.getSpanId());

        assertThat(toolSpan.getParentSpanId())
                .as("Tool should fallback to Action when no ChatClient span")
                .isEqualTo(actionSpan.getSpanId());
    }

    @Test
    @DisplayName("Tool span should capture input and have correct attributes")
    void toolSpan_shouldCaptureInputAndAttributes() {
        // Enable trace-tool-calls
        properties.setTraceToolCalls(true);

        AgentProcess process = createMockAgentProcess("run-1", "TestAgent");

        ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");
        ToolCallRequestEvent toolRequest = createToolCallRequestEvent(process, "WebSearch", "{\"query\": \"test\"}");
        ToolCallResponseEvent toolResponse = createToolCallResponseEvent(
                process, "WebSearch", "Search result: Found 5 items", null);

        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(actionStart);
        listener.onProcessEvent(toolRequest);
        listener.onProcessEvent(toolResponse);
        listener.onProcessEvent(actionResult);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData toolSpan = findSpanByName(spans, "tool:WebSearch");

        assertThat(toolSpan).as("Tool span should exist").isNotNull();

        // Verify input is captured
        assertThat(toolSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("input.value")))
                .isEqualTo("{\"query\": \"test\"}");

        assertThat(toolSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.tool.call.arguments")))
                .isEqualTo("{\"query\": \"test\"}");

        // Note: Tool output (output.value, gen_ai.tool.call.result) is captured via event.getResult()
        // using reflection to call Kotlin's mangled method names. This cannot be tested with mocks
        // because the mock doesn't have the mangled methods. In production, the real event will
        // have these methods and the output will be captured correctly.

        // Verify GenAI semantic conventions
        assertThat(toolSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("execute_tool");

        assertThat(toolSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.tool.name")))
                .isEqualTo("WebSearch");

        // Verify success status
        assertThat(toolSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("embabel.tool.status")))
                .isEqualTo("success");
    }

    // --- Tests for ObservationPredicate (skip ObservabilityToolCallback when trace-tool-calls=true) ---

    @Nested
    @DisplayName("ObservationPredicate Tests")
    class ObservationPredicateTests {

        @Test
        @DisplayName("skipObservabilityToolCallbackCustomizer should register predicate that blocks 'tool call' observations")
        void customizer_shouldBlockToolCallObservations() {
            // Create the customizer (simulating what MicrometerTracingAutoConfiguration does)
            MicrometerTracingAutoConfiguration config = new MicrometerTracingAutoConfiguration();
            var customizer = config.skipObservabilityToolCallbackCustomizer();

            // Apply customizer to registry
            customizer.customize(observationRegistry);

            // Verify predicate is registered by testing observation creation
            // "tool call" observations should be blocked
            Observation toolCallObs = Observation.createNotStarted("tool call", observationRegistry);
            assertThat(toolCallObs.isNoop())
                    .as("'tool call' observation should be blocked (noop)")
                    .isTrue();

            // Other observations should work
            Observation chatObs = Observation.createNotStarted("spring.ai.chat", observationRegistry);
            assertThat(chatObs.isNoop())
                    .as("Other observations should be allowed")
                    .isFalse();
        }

        @Test
        @DisplayName("When trace-tool-calls=true with predicate, only Embabel tool spans should be created")
        void withPredicate_onlyEmbabelToolSpans_shouldBeCreated() {
            // Setup with predicate registered
            properties.setTraceToolCalls(true);

            // Register the predicate via customizer
            MicrometerTracingAutoConfiguration config = new MicrometerTracingAutoConfiguration();
            var customizer = config.skipObservabilityToolCallbackCustomizer();
            customizer.customize(observationRegistry);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");
            ToolCallRequestEvent toolRequest = createToolCallRequestEvent(process, "WebSearch", "{\"query\": \"test\"}");
            ToolCallResponseEvent toolResponse = createToolCallResponseEvent(process, "WebSearch");

            // Execute flow
            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);

            // Simulate ObservabilityToolCallback creating observation (should be blocked by predicate)
            simulateObservabilityToolCallbackCall("WebSearch", "{\"query\": \"test\"}");

            // Embabel tool events (should create spans)
            listener.onProcessEvent(toolRequest);
            listener.onProcessEvent(toolResponse);

            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            // Verify spans
            List<SpanData> spans = spanExporter.getFinishedSpanItems();

            // Should NOT have "tool call" span (blocked by predicate)
            SpanData toolCallSpan = findSpanByName(spans, "tool call");
            assertThat(toolCallSpan)
                    .as("'tool call' span from ObservabilityToolCallback should be blocked")
                    .isNull();

            // Should have "tool:WebSearch" span (from Embabel)
            SpanData embabelToolSpan = findSpanByName(spans, "tool:WebSearch");
            assertThat(embabelToolSpan)
                    .as("Embabel tool span should exist")
                    .isNotNull();

            // Only one tool span should exist (no duplication)
            long toolSpanCount = spans.stream()
                    .filter(s -> s.getName().contains("tool"))
                    .count();
            assertThat(toolSpanCount)
                    .as("Should have exactly 1 tool span (Embabel only, no duplication)")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("When trace-tool-calls=false without predicate, ObservabilityToolCallback spans should be created")
        void withoutPredicate_observabilityToolCallbackSpans_shouldBeCreated() {
            // Setup WITHOUT predicate (simulating trace-tool-calls=false)
            properties.setTraceToolCalls(false);
            // No predicate registered

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

            // Execute flow
            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);

            // Simulate ObservabilityToolCallback creating observation (should NOT be blocked)
            simulateObservabilityToolCallbackCall("WebSearch", "{\"query\": \"test\"}");

            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            // Verify spans
            List<SpanData> spans = spanExporter.getFinishedSpanItems();

            // Should have "tool call" span (not blocked, no predicate)
            SpanData toolCallSpan = findSpanByName(spans, "tool call");
            assertThat(toolCallSpan)
                    .as("'tool call' span from ObservabilityToolCallback should exist when predicate not registered")
                    .isNotNull();

            // Should NOT have Embabel tool span (trace-tool-calls=false)
            SpanData embabelToolSpan = findSpanByName(spans, "tool:WebSearch");
            assertThat(embabelToolSpan)
                    .as("Embabel tool span should not exist when trace-tool-calls=false")
                    .isNull();
        }
    }

    // --- Tests for Tool Description and Result Capture ---

    @Nested
    @DisplayName("Tool Description and Result Capture Tests")
    class ToolDescriptionAndResultTests {

        @Test
        @DisplayName("Tool span should capture description from ToolGroupMetadata")
        void toolSpan_shouldCaptureDescription_fromToolGroupMetadata() {
            // Enable trace-tool-calls
            properties.setTraceToolCalls(true);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            // Set up blackboard to return the expected result
            when(process.getBlackboard().lastResult()).thenReturn("Search results: 5 items found");

            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

            // Create ToolGroupMetadata with description
            ToolGroupMetadata metadata = createMockToolGroupMetadata(
                    "search-tools",
                    "Tools for searching the web and databases",
                    "search"
            );

            ToolCallRequestEvent toolRequest = createToolCallRequestEvent(
                    process, "WebSearch", "{\"query\": \"test\"}",
                    metadata, "corr-456"
            );
            ToolCallResponseEvent toolResponse = createToolCallResponseEvent(process, "WebSearch", "Search results: 5 items found", null);

            // Execute
            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);
            listener.onProcessEvent(toolRequest);
            listener.onProcessEvent(toolResponse);
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            // Verify
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            SpanData toolSpan = findSpanByName(spans, "tool:WebSearch");

            assertThat(toolSpan).as("Tool span should exist").isNotNull();

            // Verify description is captured
            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.tool.description")))
                    .as("Tool description should be captured")
                    .isEqualTo("Tools for searching the web and databases");

            // Verify tool group name and role
            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("embabel.tool.group.name")))
                    .as("Tool group name should be captured")
                    .isEqualTo("search-tools");

            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("embabel.tool.group.role")))
                    .as("Tool group role should be captured")
                    .isEqualTo("search");

            // Note: Tool output (output.value, gen_ai.tool.call.result) is captured via event.getResult()
            // using reflection to call Kotlin's mangled method names. This cannot be tested with mocks
            // because the mock doesn't have the mangled methods. In production, the real event will
            // have these methods and the output will be captured correctly.
        }

        @Test
        @DisplayName("Tool span should capture output when Result is unwrapped (Kotlin inline class behavior)")
        void toolSpan_shouldCaptureOutput_whenResultIsUnwrapped() {
            // This test simulates the REAL production behavior where Kotlin's Result<T>
            // inline class is unwrapped at runtime, so getResult() returns the value directly
            // (e.g., String "74") instead of a Result wrapper object.
            properties.setTraceToolCalls(true);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

            ToolCallRequestEvent toolRequest = createToolCallRequestEvent(
                    process, "CountCharacters", "{\"text\": \"hello\"}",
                    null, "count-001"
            );
            // Use the direct value helper - simulates inline class unwrapping
            ToolCallResponseEvent toolResponse = createToolCallResponseEventWithDirectValue(
                    process, "CountCharacters", "74"
            );

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);
            listener.onProcessEvent(toolRequest);
            listener.onProcessEvent(toolResponse);
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            SpanData toolSpan = findSpanByName(spans, "tool:CountCharacters");

            assertThat(toolSpan).as("Tool span should exist").isNotNull();

            // Verify tool result is captured correctly when Result is unwrapped (inline class)
            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("output.value")))
                    .as("Tool output should be captured from unwrapped Result (inline class behavior)")
                    .isEqualTo("74");

            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.tool.call.result")))
                    .as("GenAI tool call result should be captured")
                    .isEqualTo("74");
        }

        @Test
        @DisplayName("Tool span should capture correlation ID")
        void toolSpan_shouldCaptureCorrelationId() {
            properties.setTraceToolCalls(true);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

            ToolCallRequestEvent toolRequest = createToolCallRequestEvent(
                    process, "WebSearch", "{\"query\": \"test\"}",
                    null, "unique-correlation-id-789"
            );
            ToolCallResponseEvent toolResponse = createToolCallResponseEvent(process, "WebSearch", "Result", null);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);
            listener.onProcessEvent(toolRequest);
            listener.onProcessEvent(toolResponse);
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            SpanData toolSpan = findSpanByName(spans, "tool:WebSearch");

            assertThat(toolSpan).as("Tool span should exist").isNotNull();

            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("embabel.tool.correlation_id")))
                    .as("Correlation ID should be captured")
                    .isEqualTo("unique-correlation-id-789");
        }

        @Test
        @DisplayName("Tool span should have success status on successful tool call")
        void toolSpan_shouldHaveSuccessStatus_onSuccess() {
            properties.setTraceToolCalls(true);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");

            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

            ToolCallRequestEvent toolRequest = createToolCallRequestEvent(
                    process, "Calculator", "{\"expression\": \"2+2\"}",
                    null, "calc-001"
            );
            ToolCallResponseEvent toolResponse = createToolCallResponseEvent(
                    process, "Calculator",
                    "The result of 2+2 is 4",
                    null
            );

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);
            listener.onProcessEvent(toolRequest);
            listener.onProcessEvent(toolResponse);
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            SpanData toolSpan = findSpanByName(spans, "tool:Calculator");

            assertThat(toolSpan).as("Tool span should exist").isNotNull();

            // Note: Tool output (output.value, gen_ai.tool.call.result) is captured via event.getResult()
            // using reflection. This cannot be tested with mocks because the mock doesn't have
            // the Kotlin mangled methods. In production, the output will be captured correctly.

            // Verify success status
            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("embabel.tool.status")))
                    .as("Tool status should be success")
                    .isEqualTo("success");
        }

        // Note: Error capture from event.getResult() is not possible from Java
        // due to Kotlin Result inline class method name mangling (e.g., isFailure-impl()).
        // Tool errors would need to be captured via a different mechanism in the Embabel API.

        @Test
        @DisplayName("Tool span should work without ToolGroupMetadata")
        void toolSpan_shouldWork_withoutToolGroupMetadata() {
            properties.setTraceToolCalls(true);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");

            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

            // No metadata provided
            ToolCallRequestEvent toolRequest = createToolCallRequestEvent(
                    process, "SimpleTool", "{\"param\": \"value\"}",
                    null,  // No metadata
                    "simple-001"
            );
            ToolCallResponseEvent toolResponse = createToolCallResponseEvent(process, "SimpleTool", "Done", null);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);
            listener.onProcessEvent(toolRequest);
            listener.onProcessEvent(toolResponse);
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            SpanData toolSpan = findSpanByName(spans, "tool:SimpleTool");

            assertThat(toolSpan).as("Tool span should exist even without metadata").isNotNull();

            // Description should not be present
            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.tool.description")))
                    .as("Description should be null when no metadata")
                    .isNull();

            // Verify tool span has correct attributes
            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("embabel.tool.status")))
                    .as("Tool status should be success")
                    .isEqualTo("success");
        }

        @Test
        @DisplayName("Tool span should have all GenAI semantic convention attributes")
        void toolSpan_shouldHaveAllGenAISemanticConventionAttributes() {
            properties.setTraceToolCalls(true);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");

            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

            ToolGroupMetadata metadata = createMockToolGroupMetadata(
                    "utils", "Utility functions", "helper"
            );
            ToolCallRequestEvent toolRequest = createToolCallRequestEvent(
                    process, "FormatDate", "{\"date\": \"2025-01-17\", \"format\": \"ISO\"}",
                    metadata, "fmt-001"
            );
            ToolCallResponseEvent toolResponse = createToolCallResponseEvent(
                    process, "FormatDate", "2025-01-17T00:00:00Z", null
            );

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);
            listener.onProcessEvent(toolRequest);
            listener.onProcessEvent(toolResponse);
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            SpanData toolSpan = findSpanByName(spans, "tool:FormatDate");

            assertThat(toolSpan).as("Tool span should exist").isNotNull();

            // Verify GenAI semantic conventions
            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.operation.name")))
                    .isEqualTo("execute_tool");

            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.tool.name")))
                    .isEqualTo("FormatDate");

            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.tool.type")))
                    .isEqualTo("function");

            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.tool.description")))
                    .isEqualTo("Utility functions");

            assertThat(toolSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.tool.call.arguments")))
                    .isEqualTo("{\"date\": \"2025-01-17\", \"format\": \"ISO\"}");

            // Note: gen_ai.tool.call.result is captured via event.getResult() using reflection.
            // This cannot be tested with mocks. In production, the result will be captured correctly.
        }
    }

    // --- Helper Methods ---

    /**
     * Simulates ObservabilityToolCallback.call() from embabel-agent.
     * Creates an observation with STANDARD context (not EmbabelObservationContext).
     */
    private void simulateObservabilityToolCallbackCall(String toolName, String toolInput) {
        // This mimics ObservabilityToolCallback.kt lines 38-65
        Observation currentObservation = observationRegistry.getCurrentObservation();

        Observation observation = Observation.createNotStarted("tool call", observationRegistry)
                .lowCardinalityKeyValue("toolName", toolName)
                .highCardinalityKeyValue("payload", toolInput)
                .parentObservation(currentObservation)
                .start();

        try {
            // Simulate tool execution
            String result = "Tool result for " + toolName;
            observation.lowCardinalityKeyValue("status", "success");
            observation.highCardinalityKeyValue("result", result);
        } finally {
            observation.stop();
        }
    }

    /**
     * Simulates ObservabilityToolCallback.call() with an error.
     */
    private void simulateObservabilityToolCallbackCallWithError(String toolName, String toolInput, Exception error) {
        Observation currentObservation = observationRegistry.getCurrentObservation();

        Observation observation = Observation.createNotStarted("tool call", observationRegistry)
                .lowCardinalityKeyValue("toolName", toolName)
                .highCardinalityKeyValue("payload", toolInput)
                .parentObservation(currentObservation)
                .start();

        try {
            observation.lowCardinalityKeyValue("status", "error");
            observation.highCardinalityKeyValue("error_type", error.getClass().getSimpleName());
            observation.highCardinalityKeyValue("error_message", error.getMessage());
            observation.error(error);
        } finally {
            observation.stop();
        }
    }

    private SpanData findSpanByName(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private AgentProcess createMockAgentProcess(String runId, String agentName) {
        AgentProcess process = mock(AgentProcess.class);
        Agent agent = mock(Agent.class);
        Blackboard blackboard = mock(Blackboard.class);
        ProcessOptions processOptions = mock(ProcessOptions.class);
        Goal goal = mock(Goal.class);

        when(process.getId()).thenReturn(runId);
        when(process.getAgent()).thenReturn(agent);
        when(process.getBlackboard()).thenReturn(blackboard);
        when(process.getProcessOptions()).thenReturn(processOptions);
        when(process.getParentId()).thenReturn(null);
        when(process.getGoal()).thenReturn(goal);

        when(agent.getName()).thenReturn(agentName);
        when(agent.getGoals()).thenReturn(Set.of(goal));
        when(goal.getName()).thenReturn("TestGoal");

        when(blackboard.getObjects()).thenReturn(Collections.emptyList());
        when(blackboard.lastResult()).thenReturn(null);
        when(processOptions.getPlannerType()).thenReturn(PlannerType.GOAP);

        return process;
    }

    /**
     * Creates a mock ActionExecutionStartEvent with action details.
     */
    private ActionExecutionStartEvent createMockActionStartEvent(AgentProcess process, String fullName, String shortName) {
        ActionExecutionStartEvent event = mock(ActionExecutionStartEvent.class);
        when(event.getAgentProcess()).thenReturn(process);

        // Mock action using actual library class
        com.embabel.agent.core.Action action = mock(com.embabel.agent.core.Action.class);
        when(action.getName()).thenReturn(fullName);
        when(action.shortName()).thenReturn(shortName);
        when(action.getDescription()).thenReturn("Test action");
        lenient().doReturn(action).when(event).getAction();

        return event;
    }

    /**
     * Creates a mock ActionExecutionResultEvent with status.
     */
    private ActionExecutionResultEvent createMockActionResultEvent(AgentProcess process, String actionName, String status) {
        ActionExecutionResultEvent event = mock(ActionExecutionResultEvent.class);
        when(event.getAgentProcess()).thenReturn(process);
        when(event.getRunningTime()).thenReturn(Duration.ofMillis(100));

        // Mock action using actual library class
        com.embabel.agent.core.Action action = mock(com.embabel.agent.core.Action.class);
        when(action.getName()).thenReturn(actionName);
        lenient().doReturn(action).when(event).getAction();

        // Mock ActionStatus using actual library classes
        com.embabel.agent.core.ActionStatus actionStatus = mock(com.embabel.agent.core.ActionStatus.class);
        com.embabel.agent.core.ActionStatusCode statusCode = mock(com.embabel.agent.core.ActionStatusCode.class);
        when(statusCode.name()).thenReturn(status);
        when(actionStatus.getStatus()).thenReturn(statusCode);
        lenient().doReturn(actionStatus).when(event).getActionStatus();

        return event;
    }

    private ToolCallRequestEvent createToolCallRequestEvent(AgentProcess process, String toolName, String input) {
        return createToolCallRequestEvent(process, toolName, input, null, "correlation-123");
    }

    private ToolCallRequestEvent createToolCallRequestEvent(AgentProcess process, String toolName, String input,
                                                            ToolGroupMetadata metadata, String correlationId) {
        ToolCallRequestEvent event = mock(ToolCallRequestEvent.class);
        when(event.getAgentProcess()).thenReturn(process);
        when(event.getTool()).thenReturn(toolName);
        when(event.getToolInput()).thenReturn(input);
        when(event.getToolGroupMetadata()).thenReturn(metadata);
        when(event.getCorrelationId()).thenReturn(correlationId);
        return event;
    }

    private ToolCallResponseEvent createToolCallResponseEvent(AgentProcess process, String toolName) {
        return createToolCallResponseEvent(process, toolName, "Tool result", null);
    }

    private ToolCallResponseEvent createToolCallResponseEvent(AgentProcess process, String toolName,
                                                               String successResult, Throwable error) {
        ToolCallRequestEvent request = mock(ToolCallRequestEvent.class);
        when(request.getTool()).thenReturn(toolName);

        // Create a mock Result object for the reflection-based extraction
        MockResult mockResult = new MockResult(successResult, error);

        // Use Mockito's Answer to intercept all method calls
        ToolCallResponseEvent event = mock(ToolCallResponseEvent.class, invocation -> {
            String methodName = invocation.getMethod().getName();
            // Return the mock Result for getResult()
            if (methodName.equals("getResult") || methodName.startsWith("getResult-")) {
                return mockResult;
            }
            // Return configured values for standard methods
            if (methodName.equals("getAgentProcess")) {
                return process;
            }
            if (methodName.equals("getRequest")) {
                return request;
            }
            if (methodName.equals("getRunningTime")) {
                return Duration.ofMillis(50);
            }
            // Default: return null for other methods
            return null;
        });

        return event;
    }

    /**
     * Creates a ToolCallResponseEvent that returns the value directly (simulating Kotlin inline class behavior).
     * In production, Kotlin's Result<T> is an inline/value class, so getResult() returns the unwrapped value
     * (e.g., String "74") directly, NOT a Result wrapper object.
     */
    private ToolCallResponseEvent createToolCallResponseEventWithDirectValue(AgentProcess process, String toolName,
                                                                              String directValue) {
        ToolCallRequestEvent request = mock(ToolCallRequestEvent.class);
        when(request.getTool()).thenReturn(toolName);

        // Use Mockito's Answer to intercept all method calls
        ToolCallResponseEvent event = mock(ToolCallResponseEvent.class, invocation -> {
            String methodName = invocation.getMethod().getName();
            // Return the value DIRECTLY (simulating inline class unwrapping)
            if (methodName.equals("getResult") || methodName.startsWith("getResult-")) {
                return directValue;  // Direct String, no wrapper!
            }
            if (methodName.equals("getAgentProcess")) {
                return process;
            }
            if (methodName.equals("getRequest")) {
                return request;
            }
            if (methodName.equals("getRunningTime")) {
                return Duration.ofMillis(50);
            }
            return null;
        });

        return event;
    }

    /**
     * A mock Result class that mimics Kotlin's Result<T>.
     * Provides getOrNull() and exceptionOrNull() methods that the listeners call via reflection.
     */
    static class MockResult {
        private final Object successValue;
        private final Throwable error;

        MockResult(Object successValue, Throwable error) {
            this.successValue = successValue;
            this.error = error;
        }

        /**
         * Called via reflection by the listeners.
         * Returns the success value, or null if this is a failure.
         */
        public Object getOrNull() {
            return error == null ? successValue : null;
        }

        /**
         * Called via reflection by the listeners.
         * Returns the exception, or null if this is a success.
         */
        public Throwable exceptionOrNull() {
            return error;
        }
    }

    private ToolGroupMetadata createMockToolGroupMetadata(String name, String description, String role) {
        ToolGroupMetadata metadata = mock(ToolGroupMetadata.class);
        when(metadata.getName()).thenReturn(name);
        when(metadata.getDescription()).thenReturn(description);
        when(metadata.getRole()).thenReturn(role);
        return metadata;
    }

}