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

import com.embabel.agent.observability.observation.EmbabelObservationContext;
import com.embabel.agent.observability.observation.EmbabelTracingObservationHandler;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EmbabelTracingObservationHandler.
 *
 * <p>This test validates:
 * <ul>
 *   <li>Root spans (agents) are created without parent using Context.root()</li>
 *   <li>Child spans (actions, goals, tools) have proper parent-child relationships</li>
 *   <li>Hierarchy resolution works across agents, actions, and tools</li>
 *   <li>Multiple requests create separate traces</li>
 * </ul>
 */
class EmbabelTracingObservationHandlerTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;
    private Tracer tracer;
    private io.opentelemetry.api.trace.Tracer otelTracer;
    private ObservationRegistry observationRegistry;
    private EmbabelTracingObservationHandler handler;

    @BeforeEach
    void setUp() {
        // Create in-memory span exporter for testing
        spanExporter = InMemorySpanExporter.create();

        // Build OpenTelemetry SDK with the exporter
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();

        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.noop())
                .build();

        // Create OpenTelemetry tracer
        otelTracer = openTelemetry.getTracer("test");

        // Create Micrometer Tracer that bridges to OpenTelemetry
        OtelCurrentTraceContext otelCurrentTraceContext = new OtelCurrentTraceContext();
        OtelBaggageManager baggageManager = new OtelBaggageManager(
                otelCurrentTraceContext,
                Collections.emptyList(),
                Collections.emptyList()
        );
        tracer = new OtelTracer(otelTracer, otelCurrentTraceContext, event -> {}, baggageManager);

        // Create the custom handler
        handler = new EmbabelTracingObservationHandler(tracer);

        // Create ObservationRegistry and register our handler
        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(handler);
    }

    @AfterEach
    void tearDown() {
        spanExporter.reset();
    }

    @Test
    @DisplayName("Handler should support EmbabelObservationContext")
    void handler_shouldSupportEmbabelObservationContext() {
        EmbabelObservationContext context = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
        assertThat(handler.supportsContext(context)).isTrue();
    }

    @Test
    @DisplayName("Handler should not support other contexts")
    void handler_shouldNotSupportOtherContexts() {
        Observation.Context otherContext = new Observation.Context();
        assertThat(handler.supportsContext(otherContext)).isFalse();
    }

    @Test
    @DisplayName("Root agent observation should create span without parent")
    void rootAgentObservation_shouldCreateSpanWithoutParent() {
        EmbabelObservationContext context = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
        Observation observation = Observation.createNotStarted("TestAgent", () -> context, observationRegistry);

        observation.start();
        Observation.Scope scope = observation.openScope();
        scope.close();
        observation.stop();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData agentSpan = spans.get(0);
        assertThat(agentSpan.getName()).isEqualTo("TestAgent");
        assertThat(agentSpan.getParentSpanId())
                .as("Root agent should have no parent")
                .isEqualTo(io.opentelemetry.api.trace.SpanId.getInvalid());
    }

    @Test
    @DisplayName("Root agent should have embabel.event.type attribute")
    void rootAgentObservation_shouldHaveEventTypeAttribute() {
        EmbabelObservationContext context = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
        Observation observation = Observation.createNotStarted("TestAgent", () -> context, observationRegistry);

        observation.start();
        observation.stop();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData agentSpan = spans.get(0);
        assertThat(agentSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.event.type")))
                .isEqualTo("agent_process");
        assertThat(agentSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.run_id")))
                .isEqualTo("run-1");
    }

    @Test
    @DisplayName("Action observation should be child of agent")
    void actionObservation_shouldBeChildOfAgent() {
        String runId = "run-1";

        // Start agent observation
        EmbabelObservationContext agentContext = EmbabelObservationContext.rootAgent(runId, "TestAgent");
        Observation agentObservation = Observation.createNotStarted("TestAgent", () -> agentContext, observationRegistry);
        agentObservation.start();
        Observation.Scope agentScope = agentObservation.openScope();

        // Start action observation under the agent
        EmbabelObservationContext actionContext = EmbabelObservationContext.action(runId, "TestAction");
        Observation actionObservation = Observation.createNotStarted("TestAction", () -> actionContext, observationRegistry);
        actionObservation.start();
        Observation.Scope actionScope = actionObservation.openScope();

        // Clean up
        actionScope.close();
        actionObservation.stop();
        agentScope.close();
        agentObservation.stop();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData agentSpan = spans.stream()
                .filter(s -> s.getName().equals("TestAgent"))
                .findFirst()
                .orElseThrow();
        SpanData actionSpan = spans.stream()
                .filter(s -> s.getName().equals("TestAction"))
                .findFirst()
                .orElseThrow();

        // Action should be child of agent
        assertThat(actionSpan.getParentSpanId())
                .as("Action should be child of agent")
                .isEqualTo(agentSpan.getSpanId());
        assertThat(actionSpan.getTraceId())
                .as("Action should be in same trace as agent")
                .isEqualTo(agentSpan.getTraceId());
    }

    @Test
    @DisplayName("Tool call observation should be child of action")
    void toolCallObservation_shouldBeChildOfAction() {
        String runId = "run-1";

        // Start agent
        EmbabelObservationContext agentContext = EmbabelObservationContext.rootAgent(runId, "TestAgent");
        Observation agentObservation = Observation.createNotStarted("TestAgent", () -> agentContext, observationRegistry);
        agentObservation.start();
        Observation.Scope agentScope = agentObservation.openScope();

        // Start action
        EmbabelObservationContext actionContext = EmbabelObservationContext.action(runId, "TestAction");
        Observation actionObservation = Observation.createNotStarted("TestAction", () -> actionContext, observationRegistry);
        actionObservation.start();
        Observation.Scope actionScope = actionObservation.openScope();

        // Start tool call
        EmbabelObservationContext toolContext = EmbabelObservationContext.toolCall(runId, "WebSearch");
        Observation toolObservation = Observation.createNotStarted("WebSearch", () -> toolContext, observationRegistry);
        toolObservation.start();
        Observation.Scope toolScope = toolObservation.openScope();

        // Clean up
        toolScope.close();
        toolObservation.stop();
        actionScope.close();
        actionObservation.stop();
        agentScope.close();
        agentObservation.stop();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(3);

        SpanData actionSpan = spans.stream()
                .filter(s -> s.getName().equals("TestAction"))
                .findFirst()
                .orElseThrow();
        SpanData toolSpan = spans.stream()
                .filter(s -> s.getName().equals("WebSearch"))
                .findFirst()
                .orElseThrow();

        // Tool should be child of action
        assertThat(toolSpan.getParentSpanId())
                .as("Tool should be child of action")
                .isEqualTo(actionSpan.getSpanId());
    }

    @Test
    @DisplayName("Multiple agent requests should create separate traces")
    void multipleAgentRequests_shouldCreateSeparateTraces() {
        // First agent request
        EmbabelObservationContext agent1Context = EmbabelObservationContext.rootAgent("run-1", "Agent1");
        Observation agent1 = Observation.createNotStarted("Agent1", () -> agent1Context, observationRegistry);
        agent1.start();
        Observation.Scope scope1 = agent1.openScope();
        scope1.close();
        agent1.stop();

        // Second agent request
        EmbabelObservationContext agent2Context = EmbabelObservationContext.rootAgent("run-2", "Agent2");
        Observation agent2 = Observation.createNotStarted("Agent2", () -> agent2Context, observationRegistry);
        agent2.start();
        Observation.Scope scope2 = agent2.openScope();
        scope2.close();
        agent2.stop();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData agent1Span = spans.stream()
                .filter(s -> s.getName().equals("Agent1"))
                .findFirst()
                .orElseThrow();
        SpanData agent2Span = spans.stream()
                .filter(s -> s.getName().equals("Agent2"))
                .findFirst()
                .orElseThrow();

        // Each agent should have no parent
        assertThat(agent1Span.getParentSpanId())
                .isEqualTo(io.opentelemetry.api.trace.SpanId.getInvalid());
        assertThat(agent2Span.getParentSpanId())
                .isEqualTo(io.opentelemetry.api.trace.SpanId.getInvalid());

        // Each agent should have different trace ID
        assertThat(agent1Span.getTraceId())
                .as("Each agent request should have different trace ID")
                .isNotEqualTo(agent2Span.getTraceId());
    }

    @Test
    @DisplayName("Subagent should be child of parent agent")
    void subAgentObservation_shouldBeChildOfParentAgent() {
        String parentRunId = "run-1";
        String childRunId = "run-2";

        // Start parent agent
        EmbabelObservationContext parentContext = EmbabelObservationContext.rootAgent(parentRunId, "ParentAgent");
        Observation parentObservation = Observation.createNotStarted("ParentAgent", () -> parentContext, observationRegistry);
        parentObservation.start();
        Observation.Scope parentScope = parentObservation.openScope();

        // Start subagent
        EmbabelObservationContext subAgentContext = EmbabelObservationContext.subAgent(childRunId, "SubAgent", parentRunId);
        Observation subAgentObservation = Observation.createNotStarted("SubAgent", () -> subAgentContext, observationRegistry);
        subAgentObservation.start();
        Observation.Scope subAgentScope = subAgentObservation.openScope();

        // Clean up
        subAgentScope.close();
        subAgentObservation.stop();
        parentScope.close();
        parentObservation.stop();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData parentSpan = spans.stream()
                .filter(s -> s.getName().equals("ParentAgent"))
                .findFirst()
                .orElseThrow();
        SpanData subAgentSpan = spans.stream()
                .filter(s -> s.getName().equals("SubAgent"))
                .findFirst()
                .orElseThrow();

        // Subagent should be child of parent agent
        assertThat(subAgentSpan.getParentSpanId())
                .as("Subagent should be child of parent agent")
                .isEqualTo(parentSpan.getSpanId());
        assertThat(subAgentSpan.getTraceId())
                .as("Subagent should be in same trace as parent")
                .isEqualTo(parentSpan.getTraceId());
    }

    @Test
    @DisplayName("Goal observation should be child of current action or agent")
    void goalObservation_shouldBeChildOfActionOrAgent() {
        String runId = "run-1";

        // Start agent
        EmbabelObservationContext agentContext = EmbabelObservationContext.rootAgent(runId, "TestAgent");
        Observation agentObservation = Observation.createNotStarted("TestAgent", () -> agentContext, observationRegistry);
        agentObservation.start();
        Observation.Scope agentScope = agentObservation.openScope();

        // Start action
        EmbabelObservationContext actionContext = EmbabelObservationContext.action(runId, "TestAction");
        Observation actionObservation = Observation.createNotStarted("TestAction", () -> actionContext, observationRegistry);
        actionObservation.start();
        Observation.Scope actionScope = actionObservation.openScope();

        // Goal achieved during action
        EmbabelObservationContext goalContext = EmbabelObservationContext.goal(runId, "GoalReached");
        Observation goalObservation = Observation.createNotStarted("GoalReached", () -> goalContext, observationRegistry);
        goalObservation.start();
        goalObservation.stop();

        // Clean up
        actionScope.close();
        actionObservation.stop();
        agentScope.close();
        agentObservation.stop();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(3);

        SpanData actionSpan = spans.stream()
                .filter(s -> s.getName().equals("TestAction"))
                .findFirst()
                .orElseThrow();
        SpanData goalSpan = spans.stream()
                .filter(s -> s.getName().equals("GoalReached"))
                .findFirst()
                .orElseThrow();

        // Goal should be child of action
        assertThat(goalSpan.getParentSpanId())
                .as("Goal should be child of action")
                .isEqualTo(actionSpan.getSpanId());
    }

    @Test
    @DisplayName("Low cardinality key-values should be added as span tags")
    void lowCardinalityKeyValues_shouldBeAddedAsSpanTags() {
        EmbabelObservationContext context = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
        Observation observation = Observation.createNotStarted("TestAgent", () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.agent.name", "TestAgent");
        observation.lowCardinalityKeyValue("embabel.agent.planner_type", "GOAP");

        observation.start();
        observation.stop();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.name")))
                .isEqualTo("TestAgent");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.planner_type")))
                .isEqualTo("GOAP");
    }

    @Test
    @DisplayName("High cardinality key-values should be added as span tags")
    void highCardinalityKeyValues_shouldBeAddedAsSpanTags() {
        EmbabelObservationContext context = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
        Observation observation = Observation.createNotStarted("TestAgent", () -> context, observationRegistry);

        observation.highCardinalityKeyValue("input.value", "Hello, World!");
        observation.highCardinalityKeyValue("output.value", "Response: OK");

        observation.start();
        observation.stop();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("input.value")))
                .isEqualTo("Hello, World!");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("output.value")))
                .isEqualTo("Response: OK");
    }

    @Test
    @DisplayName("Root agent should attach to existing active span (e.g., HTTP request)")
    void rootAgent_shouldAttachToActiveSpan() {
        // Create a background span simulating an HTTP request
        Span backgroundSpan = tracer.nextSpan().name("background").start();
        try (Tracer.SpanInScope bgScope = tracer.withSpan(backgroundSpan)) {

            // Create an agent observation - should become child of active span
            EmbabelObservationContext agentContext = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
            Observation agentObservation = Observation.createNotStarted("TestAgent", () -> agentContext, observationRegistry);
            agentObservation.start();
            Observation.Scope agentScope = agentObservation.openScope();

            // Create an action under the agent
            EmbabelObservationContext actionContext = EmbabelObservationContext.action("run-1", "TestAction");
            Observation actionObservation = Observation.createNotStarted("TestAction", () -> actionContext, observationRegistry);
            actionObservation.start();
            actionObservation.stop();

            agentScope.close();
            agentObservation.stop();
        }
        backgroundSpan.end();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(3);

        SpanData bgSpan = spans.stream()
                .filter(s -> s.getName().equals("background"))
                .findFirst()
                .orElseThrow();
        SpanData agentSpan = spans.stream()
                .filter(s -> s.getName().equals("TestAgent"))
                .findFirst()
                .orElseThrow();
        SpanData actionSpan = spans.stream()
                .filter(s -> s.getName().equals("TestAction"))
                .findFirst()
                .orElseThrow();

        // Agent should be child of background span (attached to existing trace)
        assertThat(agentSpan.getParentSpanId())
                .as("Agent should be child of active background span")
                .isEqualTo(bgSpan.getSpanId());

        // Agent and background should share the same trace ID
        assertThat(agentSpan.getTraceId())
                .as("Agent should share trace ID with background")
                .isEqualTo(bgSpan.getTraceId());

        // Action should still be child of agent
        assertThat(actionSpan.getParentSpanId())
                .as("Action should be child of agent")
                .isEqualTo(agentSpan.getSpanId());
    }
}
