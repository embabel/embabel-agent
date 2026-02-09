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
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.event.*;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.core.Goal;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.support.LlmInteraction;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.common.ai.model.LlmMetadata;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.plan.Plan;
import io.micrometer.observation.ObservationRegistry;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for EmbabelFullObservationEventListener.
 *
 * <p>Validates observation spans created for various agent events:
 * agent lifecycle, actions, goals, tools, planning, state transitions.
 */
class EmbabelFullObservationEventListenerTest {

    // Counter for generating unique interactionIds across test methods
    private static final AtomicInteger interactionCounter = new AtomicInteger(0);

    // OpenTelemetry components for capturing spans
    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;
    private Tracer tracer;
    private io.opentelemetry.api.trace.Tracer otelTracer;
    private ObservationRegistry observationRegistry;
    private EmbabelTracingObservationHandler handler;
    private ObservabilityProperties properties;
    private EmbabelFullObservationEventListener listener;

    @BeforeEach
    void setUp() {
        // Wire up real OTel SDK -> Micrometer bridge -> EmbabelTracingObservationHandler -> ObservationRegistry.
        // Spans are captured in InMemorySpanExporter for assertions.
        spanExporter = InMemorySpanExporter.create();

        // Configure OpenTelemetry SDK with simple span processor
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

        // Create observation handler and registry
        handler = new EmbabelTracingObservationHandler(tracer);
        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(handler);

        // Create listener under test with default properties
        properties = new ObservabilityProperties();
        listener = new EmbabelFullObservationEventListener(observationRegistry, properties);
    }

    @AfterEach
    void tearDown() {
        // Clear captured spans between tests
        spanExporter.reset();
    }

    // --- Constructor Tests ---

    @Test
    @DisplayName("Constructor should create listener with required dependencies")
    void constructor_shouldCreateListener() {
        // Verify listener is instantiated without errors
        EmbabelFullObservationEventListener listener =
                new EmbabelFullObservationEventListener(observationRegistry, properties);
        assertThat(listener).isNotNull();
    }

    // --- Agent Lifecycle Tests ---

    @Test
    @DisplayName("AgentProcessCreationEvent should start observation span")
    void agentProcessCreationEvent_shouldStartObservation() {
        // Setup: create mock agent process
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        AgentProcessCreationEvent event = new AgentProcessCreationEvent(process);

        // Execute: start and complete agent
        listener.onProcessEvent(event);
        AgentProcessCompletedEvent completedEvent = new AgentProcessCompletedEvent(process);
        listener.onProcessEvent(completedEvent);

        // Verify: one span created with agent name
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("TestAgent");
    }

    @Test
    @DisplayName("AgentProcessCreationEvent should set correct attributes for root agent")
    void agentProcessCreationEvent_shouldSetCorrectAttributes() {
        // Setup: root agent with GOAP planner
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        when(process.getProcessOptions().getPlannerType()).thenReturn(PlannerType.GOAP);

        // Execute: agent lifecycle
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: semantic attributes are set correctly
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData span = spans.get(0);

        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.name")))
                .isEqualTo("TestAgent");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.is_subagent")))
                .isEqualTo("false");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("agent");
    }

    @Test
    @DisplayName("AgentProcessCreationEvent should identify subagent correctly")
    void agentProcessCreationEvent_shouldIdentifySubagent() {
        // Setup: parent agent and child subagent
        AgentProcess parentProcess = createMockAgentProcess("parent-run", "ParentAgent", null);
        AgentProcess subProcess = createMockAgentProcess("sub-run", "SubAgent", "parent-run");

        // Execute: start parent, then subagent, complete in reverse order
        listener.onProcessEvent(new AgentProcessCreationEvent(parentProcess));
        listener.onProcessEvent(new AgentProcessCreationEvent(subProcess));
        listener.onProcessEvent(new AgentProcessCompletedEvent(subProcess));
        listener.onProcessEvent(new AgentProcessCompletedEvent(parentProcess));

        // Verify: subagent has correct parent reference
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData subAgentSpan = spans.stream()
                .filter(s -> s.getName().equals("SubAgent"))
                .findFirst()
                .orElseThrow();

        assertThat(subAgentSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.is_subagent")))
                .isEqualTo("true");
        assertThat(subAgentSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.parent_id")))
                .isEqualTo("parent-run");
    }

    @Test
    @DisplayName("AgentProcessCompletedEvent should set completed status")
    void agentProcessCompletedEvent_shouldSetCompletedStatus() {
        // Setup and execute: simple agent lifecycle
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: status attribute is "completed"
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.status")))
                .isEqualTo("completed");
    }

    @Test
    @DisplayName("AgentProcessFailedEvent should set failed status and error")
    void agentProcessFailedEvent_shouldSetFailedStatus() {
        // Setup: agent with failure info
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        when(process.getFailureInfo()).thenReturn("Something went wrong");

        // Execute: start then fail
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(new AgentProcessFailedEvent(process));

        // Verify: status attribute is "failed"
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.status")))
                .isEqualTo("failed");
    }

    // --- Action Tests ---

    @Test
    @DisplayName("ActionExecutionStartEvent should start action observation")
    void actionExecutionStartEvent_shouldStartObservation() {
        // Setup: agent process and mock action event
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        ActionExecutionStartEvent startEvent = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent resultEvent = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

        // Execute: full action lifecycle within agent
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(startEvent);
        listener.onProcessEvent(resultEvent);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: action span with correct operation name
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData actionSpan = spans.stream()
                .filter(s -> s.getName().equals("MyAction"))
                .findFirst()
                .orElseThrow();

        assertThat(actionSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("execute_action");
    }

    @Test
    @DisplayName("ActionExecutionResultEvent should complete action observation with status")
    void actionExecutionResultEvent_shouldCompleteObservation() {
        // Setup: agent and action events
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        ActionExecutionStartEvent startEvent = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent resultEvent = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(startEvent);
        listener.onProcessEvent(resultEvent);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: action status recorded
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData actionSpan = spans.stream()
                .filter(s -> s.getName().equals("MyAction"))
                .findFirst()
                .orElseThrow();

        assertThat(actionSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.action.status")))
                .isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("Failed action should set ERROR status on span")
    void actionExecutionResultEvent_shouldSetErrorStatus_whenFailed() {
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        ActionExecutionStartEvent startEvent = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent resultEvent = createMockActionResultEvent(process, "com.example.MyAction", "FAILED");

        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(startEvent);
        listener.onProcessEvent(resultEvent);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData actionSpan = spans.stream()
                .filter(s -> s.getName().equals("MyAction"))
                .findFirst()
                .orElseThrow();

        assertThat(actionSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.action.status")))
                .isEqualTo("FAILED");
        assertThat(actionSpan.getStatus().getStatusCode())
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    }

    // --- Tool Call Tests ---

    @Test
    @DisplayName("ToolCallRequestEvent should start tool observation when enabled")
    void toolCallRequestEvent_shouldStartObservation_whenEnabled() {
        // Setup: enable tool call tracing
        properties.setTraceToolCalls(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        ToolCallRequestEvent toolEvent = createToolCallRequestEvent(process, "WebSearch", "query input");
        ToolCallResponseEvent responseEvent = createToolCallResponseEvent(process, "WebSearch");

        // Execute: tool call within agent
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(toolEvent);
        listener.onProcessEvent(responseEvent);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: tool span created with correct attributes
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData toolSpan = spans.stream()
                .filter(s -> s.getName().equals("tool:WebSearch"))
                .findFirst()
                .orElseThrow();

        assertThat(toolSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("execute_tool");
        assertThat(toolSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.tool.name")))
                .isEqualTo("WebSearch");
    }

    @Test
    @DisplayName("ToolCallRequestEvent should not create observation when disabled")
    void toolCallRequestEvent_shouldNotCreateObservation_whenDisabled() {
        // Setup: disable tool call tracing (default)
        properties.setTraceToolCalls(false);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        // Execute: tool call should be ignored
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(createToolCallRequestEvent(process, "WebSearch", "query"));
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: no tool spans created
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.stream().noneMatch(s -> s.getName().contains("tool:"))).isTrue();
    }

    // --- Goal Tests ---

    @Test
    @DisplayName("GoalAchievedEvent should create instant observation")
    void goalAchievedEvent_shouldCreateInstantObservation() {
        // Setup: agent and goal (GoalAchievedEvent needs plan.Goal and WorldState)
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        com.embabel.plan.Goal planGoal = mock(com.embabel.plan.Goal.class);
        com.embabel.plan.WorldState worldState = mock(com.embabel.plan.WorldState.class);
        when(planGoal.getName()).thenReturn("com.example.MyGoal");
        GoalAchievedEvent goalEvent = new GoalAchievedEvent(process, worldState, planGoal);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(goalEvent);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: goal span with short name extracted
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData goalSpan = spans.stream()
                .filter(s -> s.getName().equals("goal:MyGoal"))
                .findFirst()
                .orElseThrow();

        assertThat(goalSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.goal.short_name")))
                .isEqualTo("MyGoal");
    }

    // --- Planning Tests ---

    @Test
    @DisplayName("AgentProcessReadyToPlanEvent should create observation when enabled")
    void readyToPlanEvent_shouldCreateObservation_whenEnabled() {
        // Setup: enable planning tracing
        properties.setTracePlanning(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        AgentProcessReadyToPlanEvent event = mock(AgentProcessReadyToPlanEvent.class);
        when(event.getAgentProcess()).thenReturn(process);
        when(event.getWorldState()).thenReturn(null);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: planning:ready span created
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData planningSpan = spans.stream()
                .filter(s -> s.getName().equals("planning:ready"))
                .findFirst()
                .orElseThrow();

        assertThat(planningSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("planning");
    }

    @Test
    @DisplayName("AgentProcessPlanFormulatedEvent should track plan iterations")
    void planFormulatedEvent_shouldTrackIterations() {
        // Setup: enable planning tracing with mock plan
        properties.setTracePlanning(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        Plan plan = createMockPlan();

        AgentProcessPlanFormulatedEvent event1 = mock(AgentProcessPlanFormulatedEvent.class);
        when(event1.getAgentProcess()).thenReturn(process);
        when(event1.getPlan()).thenReturn(plan);
        when(event1.getWorldState()).thenReturn(null);

        AgentProcessPlanFormulatedEvent event2 = mock(AgentProcessPlanFormulatedEvent.class);
        when(event2.getAgentProcess()).thenReturn(process);
        when(event2.getPlan()).thenReturn(plan);
        when(event2.getWorldState()).thenReturn(null);

        // Execute: two plan formulations = replanning
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event1);
        listener.onProcessEvent(event2);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: second plan marked as replanning with iteration 2
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData replanningSpan = spans.stream()
                .filter(s -> s.getName().equals("planning:replanning"))
                .findFirst()
                .orElseThrow();

        assertThat(replanningSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.plan.is_replanning")))
                .isEqualTo("true");
        assertThat(replanningSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.plan.iteration")))
                .isEqualTo("2");
    }

    @Test
    @DisplayName("ReplanRequestedEvent should create span with reason when planning tracing enabled")
    void replanRequested_shouldCreateSpan_whenEnabled() {
        properties.setTracePlanning(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(new ReplanRequestedEvent(process, "Tool loop detected issue"));
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData replanSpan = spans.stream()
                .filter(s -> s.getName().equals("planning:replan_requested"))
                .findFirst()
                .orElseThrow();

        assertThat(replanSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.replan.reason")))
                .isEqualTo("Tool loop detected issue");
    }

    @Test
    @DisplayName("Planning events should not be traced when disabled")
    void planningEvents_shouldNotBeTraced_whenDisabled() {
        // Setup: disable planning tracing
        properties.setTracePlanning(false);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        AgentProcessReadyToPlanEvent event = mock(AgentProcessReadyToPlanEvent.class);
        when(event.getAgentProcess()).thenReturn(process);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: no planning spans
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.stream().noneMatch(s -> s.getName().contains("planning:"))).isTrue();
    }

    // --- State Transition Tests ---

    @Test
    @DisplayName("StateTransitionEvent should create observation when enabled")
    void stateTransitionEvent_shouldCreateObservation_whenEnabled() {
        // Setup: enable state transition tracing
        properties.setTraceStateTransitions(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        StateTransitionEvent event = mock(StateTransitionEvent.class);
        when(event.getAgentProcess()).thenReturn(process);
        when(event.getNewState()).thenReturn(new TestState());

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: state transition span created
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData stateSpan = spans.stream()
                .filter(s -> s.getName().equals("state:TestState"))
                .findFirst()
                .orElseThrow();

        assertThat(stateSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.state.to")))
                .isEqualTo("TestState");
    }

    @Test
    @DisplayName("StateTransitionEvent should not be traced when disabled")
    void stateTransitionEvent_shouldNotBeTraced_whenDisabled() {
        // Setup: disable state transition tracing
        properties.setTraceStateTransitions(false);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        StateTransitionEvent event = mock(StateTransitionEvent.class);
        when(event.getAgentProcess()).thenReturn(process);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: no state spans
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.stream().noneMatch(s -> s.getName().contains("state:"))).isTrue();
    }

    // --- Lifecycle State Tests ---

    @Test
    @DisplayName("AgentProcessWaitingEvent should create lifecycle observation when enabled")
    void waitingEvent_shouldCreateObservation_whenEnabled() {
        // Setup: enable lifecycle state tracing
        properties.setTraceLifecycleStates(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        AgentProcessWaitingEvent event = new AgentProcessWaitingEvent(process);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: lifecycle:waiting span with WAITING state
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData lifecycleSpan = spans.stream()
                .filter(s -> s.getName().equals("lifecycle:waiting"))
                .findFirst()
                .orElseThrow();

        assertThat(lifecycleSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.lifecycle.state")))
                .isEqualTo("WAITING");
    }

    @Test
    @DisplayName("AgentProcessPausedEvent should create lifecycle observation")
    void pausedEvent_shouldCreateObservation() {
        // Setup
        properties.setTraceLifecycleStates(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        AgentProcessPausedEvent event = new AgentProcessPausedEvent(process);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: paused lifecycle span exists
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.stream().anyMatch(s -> s.getName().equals("lifecycle:paused"))).isTrue();
    }

    @Test
    @DisplayName("AgentProcessStuckEvent should create lifecycle observation")
    void stuckEvent_shouldCreateObservation() {
        // Setup
        properties.setTraceLifecycleStates(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        AgentProcessStuckEvent event = new AgentProcessStuckEvent(process);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: stuck lifecycle span exists
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.stream().anyMatch(s -> s.getName().equals("lifecycle:stuck"))).isTrue();
    }

    @Test
    @DisplayName("AgentProcessStuckEvent should create span with ERROR status")
    void stuckEvent_shouldCreateSpan_withErrorStatus() {
        properties.setTraceLifecycleStates(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        AgentProcessStuckEvent event = new AgentProcessStuckEvent(process);

        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData stuckSpan = spans.stream()
                .filter(s -> s.getName().equals("lifecycle:stuck"))
                .findFirst()
                .orElseThrow();

        assertThat(stuckSpan.getStatus().getStatusCode())
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    }

    @Test
    @DisplayName("Lifecycle events should not be traced when disabled")
    void lifecycleEvents_shouldNotBeTraced_whenDisabled() {
        // Setup: disable lifecycle tracing
        properties.setTraceLifecycleStates(false);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        // Execute: all lifecycle events
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(new AgentProcessWaitingEvent(process));
        listener.onProcessEvent(new AgentProcessPausedEvent(process));
        listener.onProcessEvent(new AgentProcessStuckEvent(process));
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: no lifecycle spans
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.stream().noneMatch(s -> s.getName().contains("lifecycle:"))).isTrue();
    }

    // --- Truncation Tests ---

    @Test
    @DisplayName("Long attribute values should be truncated")
    void longAttributeValues_shouldBeTruncated() {
        // Setup: set max attribute length to 50
        properties.setMaxAttributeLength(50);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        // Create long blackboard content (100 chars)
        String longContent = "A".repeat(100);
        Blackboard blackboard = process.getBlackboard();
        when(blackboard.getObjects()).thenReturn(List.of(new TestObject(longContent)));

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: input.value is truncated with "..."
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData span = spans.get(0);

        String inputValue = span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("input.value"));
        assertThat(inputValue).isNotNull();
        assertThat(inputValue.length()).isLessThanOrEqualTo(53); // 50 + "..."
        assertThat(inputValue).endsWith("...");
    }

    // --- LLM Call Tests ---

    @Test
    @DisplayName("LLM span should have GenAI hyperparameter attributes")
    void llmSpan_shouldHaveGenAiHyperparameterAttributes() {
        properties.setTraceLlmCalls(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        LlmRequestEvent<?> llmRequest = createMockLlmRequestEvent(process, "com.example.MyAction", "gpt-4", String.class);
        LlmResponseEvent<?> llmResponse = createMockLlmResponseEvent(llmRequest, "result");
        ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(actionStart);
        listener.onProcessEvent(llmRequest);
        listener.onProcessEvent(llmResponse);
        listener.onProcessEvent(actionResult);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData llmSpan = spans.stream()
                .filter(s -> s.getName().equals("llm:gpt-4"))
                .findFirst()
                .orElseThrow();

        assertThat(llmSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.request.model")))
                .isEqualTo("gpt-4");
        assertThat(llmSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.request.temperature")))
                .isEqualTo("0.7");
        assertThat(llmSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.request.max_tokens")))
                .isEqualTo("1000");
        assertThat(llmSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.request.top_p")))
                .isEqualTo("0.9");
        assertThat(llmSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.provider.name")))
                .isEqualTo("openai");
    }

    @Test
    @DisplayName("LLM span should set ERROR when response is a Throwable")
    void llmSpan_shouldSetErrorStatus_whenResponseIsThrowable() {
        properties.setTraceLlmCalls(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        LlmRequestEvent<?> llmRequest = createMockLlmRequestEvent(process, "com.example.MyAction", "gpt-4", Object.class);
        LlmResponseEvent<?> llmResponse = createMockLlmResponseEvent(llmRequest, new RuntimeException("LLM call failed"));
        ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "FAILED");

        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(actionStart);
        listener.onProcessEvent(llmRequest);
        listener.onProcessEvent(llmResponse);
        listener.onProcessEvent(actionResult);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData llmSpan = spans.stream()
                .filter(s -> s.getName().equals("llm:gpt-4"))
                .findFirst()
                .orElseThrow();

        assertThat(llmSpan.getStatus().getStatusCode())
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    }

    // --- Helper Methods ---

    /**
     * Creates a mock AgentProcess with standard configuration.
     */
    private AgentProcess createMockAgentProcess(String runId, String agentName, String parentId) {
        AgentProcess process = mock(AgentProcess.class);
        Agent agent = mock(Agent.class);
        Blackboard blackboard = mock(Blackboard.class);
        ProcessOptions processOptions = mock(ProcessOptions.class);
        Goal goal = mock(Goal.class);

        when(process.getId()).thenReturn(runId);
        when(process.getAgent()).thenReturn(agent);
        when(process.getBlackboard()).thenReturn(blackboard);
        when(process.getProcessOptions()).thenReturn(processOptions);
        when(process.getParentId()).thenReturn(parentId);
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

        // Mock action with required methods
        com.embabel.agent.core.Action action = mock(com.embabel.agent.core.Action.class);
        when(action.getName()).thenReturn(fullName);
        when(action.shortName()).thenReturn(shortName);
        when(action.getDescription()).thenReturn("Test action description");

        lenient().doReturn(action).when(event).getAction();
        return event;
    }

    /**
     * Creates a mock ActionExecutionResultEvent with status.
     */
    private ActionExecutionResultEvent createMockActionResultEvent(AgentProcess process, String actionName, String statusName) {
        ActionExecutionResultEvent event = mock(ActionExecutionResultEvent.class);
        when(event.getAgentProcess()).thenReturn(process);
        when(event.getRunningTime()).thenReturn(Duration.ofMillis(100));

        // Mock action
        com.embabel.agent.core.Action action = mock(com.embabel.agent.core.Action.class);
        when(action.getName()).thenReturn(actionName);
        lenient().doReturn(action).when(event).getAction();

        // Mock ActionStatus with nested status
        com.embabel.agent.core.ActionStatus actionStatus = mock(com.embabel.agent.core.ActionStatus.class);
        com.embabel.agent.core.ActionStatusCode statusCode = mock(com.embabel.agent.core.ActionStatusCode.class);
        when(statusCode.name()).thenReturn(statusName);
        when(actionStatus.getStatus()).thenReturn(statusCode);
        lenient().doReturn(actionStatus).when(event).getActionStatus();

        return event;
    }

    /**
     * Creates a mock ToolCallRequestEvent.
     */
    private ToolCallRequestEvent createToolCallRequestEvent(AgentProcess process, String toolName, String input) {
        ToolCallRequestEvent event = mock(ToolCallRequestEvent.class);
        when(event.getAgentProcess()).thenReturn(process);
        when(event.getTool()).thenReturn(toolName);
        when(event.getToolInput()).thenReturn(input);
        return event;
    }

    /**
     * Creates a mock ToolCallResponseEvent.
     */
    private ToolCallResponseEvent createToolCallResponseEvent(AgentProcess process, String toolName) {
        return createToolCallResponseEvent(process, toolName, "Tool result: " + toolName, null);
    }

    /**
     * Creates a mock ToolCallResponseEvent with specific result.
     */
    private ToolCallResponseEvent createToolCallResponseEvent(AgentProcess process, String toolName,
                                                               String successResult, Throwable error) {
        ToolCallRequestEvent request = mock(ToolCallRequestEvent.class);
        when(request.getTool()).thenReturn(toolName);

        // Create a mock Result object for the reflection-based extraction
        MockResult mockResult = new MockResult(successResult, error);

        // Use Mockito's Answer to intercept all method calls
        ToolCallResponseEvent event = mock(ToolCallResponseEvent.class, invocation -> {
            String methodName = invocation.getMethod().getName();
            if (methodName.equals("getResult") || methodName.startsWith("getResult-")) {
                return mockResult;
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
    // Mimics Kotlin's Result<T> which is an inline class. The listener extracts
    // getOrNull()/exceptionOrNull() via reflection, so we provide a compatible structure.
    static class MockResult {
        private final Object successValue;
        private final Throwable error;

        MockResult(Object successValue, Throwable error) {
            this.successValue = successValue;
            this.error = error;
        }

        public Object getOrNull() {
            return error == null ? successValue : null;
        }

        public Throwable exceptionOrNull() {
            return error;
        }
    }

    /**
     * Creates a mock Plan with one action.
     */
    private Plan createMockPlan() {
        Plan plan = mock(Plan.class);

        // Mock plan action (from com.embabel.plan.Action)
        com.embabel.plan.Action planAction = mock(com.embabel.plan.Action.class);
        when(planAction.getName()).thenReturn("PlanStep1");

        // Mock plan goal
        com.embabel.plan.Goal planGoal = mock(com.embabel.plan.Goal.class);
        when(planGoal.getName()).thenReturn("PlanGoal");

        when(plan.getActions()).thenReturn(List.of(planAction));
        when(plan.getGoal()).thenReturn(planGoal);
        return plan;
    }

    /**
     * Creates a real LlmRequestEvent (Kotlin final class — cannot be reliably mocked).
     * Each call generates a unique interactionId to support parallel LLM call tests.
     * Uses reflection to call LlmInteraction.from() since the method name is mangled
     * due to the InteractionId inline value class parameter.
     */
    private <O> LlmRequestEvent<O> createMockLlmRequestEvent(
            AgentProcess process, String actionName, String modelName, Class<O> outputClass) {
        com.embabel.agent.core.Action action = null;
        if (actionName != null) {
            action = mock(com.embabel.agent.core.Action.class);
            lenient().when(action.getName()).thenReturn(actionName);
        }

        LlmMetadata llmMetadata = mock(LlmMetadata.class);
        lenient().when(llmMetadata.getName()).thenReturn(modelName);
        lenient().when(llmMetadata.getProvider()).thenReturn("openai");

        LlmOptions llmOptions = new LlmOptions();
        llmOptions.setTemperature(0.7);
        llmOptions.setMaxTokens(1000);
        llmOptions.setTopP(0.9);

        LlmInteraction interaction = createInteractionWithUniqueId(llmOptions);

        return new LlmRequestEvent<>(process, action, outputClass, interaction, llmMetadata, Collections.emptyList());
    }

    /**
     * Creates a real LlmRequestEvent with specific messages.
     * Each call generates a unique interactionId to support parallel LLM call tests.
     */
    private <O> LlmRequestEvent<O> createMockLlmRequestEventWithMessages(
            AgentProcess process, String actionName, String modelName, Class<O> outputClass,
            List<com.embabel.chat.Message> messages) {
        com.embabel.agent.core.Action action = null;
        if (actionName != null) {
            action = mock(com.embabel.agent.core.Action.class);
            lenient().when(action.getName()).thenReturn(actionName);
        }

        LlmMetadata llmMetadata = mock(LlmMetadata.class);
        lenient().when(llmMetadata.getName()).thenReturn(modelName);
        lenient().when(llmMetadata.getProvider()).thenReturn("openai");

        LlmOptions llmOptions = new LlmOptions();
        llmOptions.setTemperature(0.7);
        llmOptions.setMaxTokens(1000);
        llmOptions.setTopP(0.9);

        LlmInteraction interaction = createInteractionWithUniqueId(llmOptions);

        return new LlmRequestEvent<>(process, action, outputClass, interaction, llmMetadata, messages);
    }

    /**
     * Creates a real LlmResponseEvent via the request's factory method.
     */
    @SuppressWarnings("unchecked")
    private <O> LlmResponseEvent<O> createMockLlmResponseEvent(
            LlmRequestEvent<?> request, O response) {
        LlmRequestEvent<O> typedRequest = (LlmRequestEvent<O>) request;
        return typedRequest.responseEvent(response, Duration.ofMillis(150));
    }

    // --- Parallel LLM/Tool-loop Tests ---

    // Integration tests for concurrent LLM calls. Verifies that parallel LLM request/response
    // events on different threads produce distinct spans, and that tool-loops are correctly
    // parented under their corresponding LLM span via interactionId.
    @Nested
    @DisplayName("Parallel LLM Call Tests")
    class ParallelLlmCallTests {

        @Test
        @DisplayName("Parallel LLM calls on different threads should produce distinct spans")
        void parallelLlmCalls_shouldProduceDistinctSpans() throws Exception {
            properties.setTraceLlmCalls(true);
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");

            // Start agent + action on main thread
            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);

            // Fire 3 LLM request/response pairs on separate threads (simulates parallelMap)
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(3);
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                new Thread(() -> {
                    LlmRequestEvent<?> req = createMockLlmRequestEvent(
                            process, "com.example.MyAction", "gemini-2.5-pro", String.class);
                    listener.onProcessEvent(req);
                    listener.onProcessEvent(createMockLlmResponseEvent(req, "result" + idx));
                    latch.countDown();
                }).start();
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);

            // Complete action + agent
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            // Verify: 3 distinct LLM spans + 1 action span + 1 agent span = 5 spans
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            long llmSpanCount = spans.stream()
                    .filter(s -> s.getName().equals("llm:gemini-2.5-pro"))
                    .count();
            assertThat(llmSpanCount)
                    .as("Expected 3 distinct LLM spans for 3 parallel calls on different threads")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("Parallel tool loops should produce distinct spans")
        void parallelToolLoops_shouldProduceDistinctSpans() {
            properties.setTraceToolLoop(true);
            properties.setTraceLlmCalls(true);
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");

            // Start agent + action
            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);

            // Fire 2 tool loop starts with same runId but different interactionIds
            ToolLoopStartEvent tls1 = new ToolLoopStartEvent(
                    process, actionStart.getAction(),
                    List.of("tool1"), 10, "interaction-1", String.class);
            ToolLoopStartEvent tls2 = new ToolLoopStartEvent(
                    process, actionStart.getAction(),
                    List.of("tool1"), 10, "interaction-2", String.class);

            listener.onProcessEvent(tls1);
            listener.onProcessEvent(tls2);

            // Complete both tool loops
            listener.onProcessEvent(tls1.completedEvent(3, false));
            listener.onProcessEvent(tls2.completedEvent(2, false));

            // Complete action + agent
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            // Verify: 2 distinct tool-loop spans
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            long toolLoopSpanCount = spans.stream()
                    .filter(s -> s.getName().startsWith("tool-loop:"))
                    .count();
            assertThat(toolLoopSpanCount)
                    .as("Expected 2 distinct tool-loop spans")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("Tool loop should be parented under LLM span via interactionId-based map lookup")
        void toolLoopParent_shouldBeUnderLlmSpan() {
            properties.setTraceToolLoop(true);
            properties.setTraceLlmCalls(true);
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");

            // Start agent + action
            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);

            // Start LLM (opens scope, making it the current observation)
            LlmRequestEvent<?> llmReq = createMockLlmRequestEvent(process, "com.example.MyAction", "gemini-2.5-pro", String.class);
            listener.onProcessEvent(llmReq);

            // Use the LLM request's actual interactionId (matches production behavior)
            String interactionId = llmReq.getInteraction().getId();

            // Start tool loop — should be parented under the LLM observation via interactionId-based map lookup
            ToolLoopStartEvent tls = new ToolLoopStartEvent(
                    process, actionStart.getAction(),
                    List.of("tool1"), 10, interactionId, String.class);
            listener.onProcessEvent(tls);

            // Complete tool loop, LLM, action, agent
            listener.onProcessEvent(tls.completedEvent(2, false));
            listener.onProcessEvent(createMockLlmResponseEvent(llmReq, "result"));
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            // Verify: tool-loop span is parented under the LLM span
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            SpanData toolLoopSpan = spans.stream()
                    .filter(s -> s.getName().startsWith("tool-loop:"))
                    .findFirst()
                    .orElseThrow();
            SpanData llmSpan = spans.stream()
                    .filter(s -> s.getName().equals("llm:gemini-2.5-pro"))
                    .findFirst()
                    .orElseThrow();

            assertThat(toolLoopSpan.getParentSpanId())
                    .as("Tool loop should be parented under the LLM span")
                    .isEqualTo(llmSpan.getSpanId());
        }

        @Test
        @DisplayName("LLM span should have input.value from messages and output.value from response")
        void llmSpan_shouldHaveInputOutput() {
            properties.setTraceLlmCalls(true);
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");

            // Create LLM request with messages
            List<com.embabel.chat.Message> messages = List.of(
                    new com.embabel.chat.SystemMessage("You are a helpful assistant."),
                    new com.embabel.chat.UserMessage("What is 2+2?")
            );
            LlmRequestEvent<?> llmRequest = createMockLlmRequestEventWithMessages(
                    process, "com.example.MyAction", "gpt-4", String.class, messages);
            LlmResponseEvent<?> llmResponse = createMockLlmResponseEvent(llmRequest, "The answer is 4.");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);
            listener.onProcessEvent(llmRequest);
            listener.onProcessEvent(llmResponse);
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            SpanData llmSpan = spans.stream()
                    .filter(s -> s.getName().equals("llm:gpt-4"))
                    .findFirst()
                    .orElseThrow();

            // Verify input.value contains messages
            String inputValue = llmSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("input.value"));
            assertThat(inputValue).isNotNull();
            assertThat(inputValue).contains("SYSTEM");
            assertThat(inputValue).contains("You are a helpful assistant.");
            assertThat(inputValue).contains("USER");
            assertThat(inputValue).contains("What is 2+2?");

            // Verify output.value contains response
            String outputValue = llmSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("output.value"));
            assertThat(outputValue).isNotNull();
            assertThat(outputValue).contains("The answer is 4.");
        }

        @Test
        @DisplayName("LLM span should not have output.value when response is a Throwable")
        void llmSpan_shouldNotHaveOutputValue_whenResponseIsThrowable() {
            properties.setTraceLlmCalls(true);
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");

            List<com.embabel.chat.Message> messages = List.of(
                    new com.embabel.chat.UserMessage("Hello")
            );
            LlmRequestEvent<?> llmRequest = createMockLlmRequestEventWithMessages(
                    process, "com.example.MyAction", "gpt-4", Object.class, messages);
            LlmResponseEvent<?> llmResponse = createMockLlmResponseEvent(llmRequest, new RuntimeException("LLM failed"));

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);
            listener.onProcessEvent(llmRequest);
            listener.onProcessEvent(llmResponse);
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "FAILED");
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            SpanData llmSpan = spans.stream()
                    .filter(s -> s.getName().equals("llm:gpt-4"))
                    .findFirst()
                    .orElseThrow();

            // input.value should still be set
            String inputValue = llmSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("input.value"));
            assertThat(inputValue).isNotNull();
            assertThat(inputValue).contains("USER");

            // output.value should NOT be set for error responses
            String outputValue = llmSpan.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("output.value"));
            assertThat(outputValue).isNull();
        }
    }

    /**
     * Creates a LlmInteraction with a unique interactionId.
     * Uses reflection to call the mangled LlmInteraction.from(LlmCall, String)
     * since InteractionId is a Kotlin inline value class, which mangles the method name in bytecode.
     */
    private static LlmInteraction createInteractionWithUniqueId(LlmOptions llmOptions) {
        String uniqueId = "interaction-" + interactionCounter.incrementAndGet();
        try {
            var llmCall = com.embabel.agent.core.support.LlmCall.Companion.using(llmOptions);
            var fromMethod = LlmInteraction.class.getDeclaredMethod("from-5V0vsfg",
                    com.embabel.agent.core.support.LlmCall.class, String.class);
            fromMethod.setAccessible(true);
            return (LlmInteraction) fromMethod.invoke(null, llmCall, uniqueId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create LlmInteraction with unique ID: " + uniqueId, e);
        }
    }

    // --- Test Helper Classes ---

    private static class TestState {
        @Override
        public String toString() {
            return "TestState";
        }
    }

    private record TestObject(String content) {
        @Override
        public String toString() {
            return content;
        }
    }
}
