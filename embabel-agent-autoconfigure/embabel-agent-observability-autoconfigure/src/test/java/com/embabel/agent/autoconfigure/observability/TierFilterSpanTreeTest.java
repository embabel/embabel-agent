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
package com.embabel.agent.autoconfigure.observability;

import com.embabel.agent.api.event.LlmRequestEvent;
import com.embabel.agent.api.event.ToolLoopStartEvent;
import com.embabel.agent.api.event.observation.ActionObservationContext;
import com.embabel.agent.api.event.observation.AgentObservationContext;
import com.embabel.agent.api.event.observation.LlmObservationContext;
import com.embabel.agent.api.event.observation.Observations;
import com.embabel.agent.api.event.observation.ToolLoopObservationContext;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.plan.Action;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.SpanId;
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

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves, against a real OpenTelemetry bridge, the actual trace-level semantics of suppressing a
 * core span tier with the tier-filter predicate: the suppressed {@code embabel.tool_loop} produces
 * NO span, and its child re-parents to the next live ancestor span ({@code embabel.llm}). (At the raw
 * Micrometer-observation layer the child still references the no-op, but no span is exported for it,
 * so the exported trace nests the child directly under the live ancestor.) A live grandchild
 * ({@code embabel.embedding}) stands in for the real child tier, since Spring AI's {@code tool call}
 * span is always dropped by the same predicate.
 */
class TierFilterSpanTreeTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;
    private ObservationRegistry registry;
    private Scope otelRootScope;

    @BeforeEach
    void setUp() {
        otelRootScope = Context.root().makeCurrent();
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.noop())
                .build();
        OtelCurrentTraceContext otelCurrentTraceContext = new OtelCurrentTraceContext();
        io.opentelemetry.api.trace.Tracer otelTracer = openTelemetry.getTracer("test");
        OtelBaggageManager baggageManager = new OtelBaggageManager(
                otelCurrentTraceContext, Collections.emptyList(), Collections.emptyList());
        Tracer tracer = new OtelTracer(otelTracer, otelCurrentTraceContext, event -> {
        }, baggageManager);

        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));
    }

    @AfterEach
    void tearDown() {
        spanExporter.reset();
        if (otelRootScope != null) {
            otelRootScope.close();
        }
    }

    private void applyFilter(ObservabilityProperties properties) {
        new ObservabilityAutoConfiguration().embabelTierFilterCustomizer(properties).customize(registry);
    }

    private static SpanData span(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(s -> name.equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + name));
    }

    /** llm (live) > tool_loop > embedding (live), so the chain is observable whether or not tool_loop is dropped. */
    private void observeLlmToolLoopEmbedding() {
        Observation.createNotStarted("embabel.llm", registry).observe((Supplier<Object>) () ->
                Observation.createNotStarted("embabel.tool_loop", registry).observe((Supplier<Object>) () ->
                        Observation.createNotStarted("embabel.embedding", registry).observe((Supplier<Object>) () -> null)));
    }

    /** Nests observations in the given order (outermost first) and runs an empty innermost body. */
    private void observeNested(String... names) {
        observeFrom(names, 0);
    }

    private Object observeFrom(String[] names, int index) {
        if (index == names.length) {
            return null;
        }
        return Observation.createNotStarted(names[index], registry)
                .observe((Supplier<Object>) () -> observeFrom(names, index + 1));
    }

    /**
     * A scoped {@code tool_loop} observation created the way the core does it: the placeholder name
     * plus a real {@link ToolLoopObservationContext}. The tier filter matches it by context type
     * (the semantic name is not yet set when the predicate runs), so {@code trace-tool-loop=false}
     * actually drops it — exactly like in production.
     */
    private Observation toolLoopObservation() {
        return Observation.createNotStarted(Observations.PLACEHOLDER_NAME,
                () -> new ToolLoopObservationContext(mock(ToolLoopStartEvent.class), List.of()), registry);
    }

    /** Observe with a real scoped context the way the core does (placeholder name + typed context). */
    private Object observeScoped(Observation.Context ctx, Supplier<Object> inner) {
        return Observation.createNotStarted(Observations.PLACEHOLDER_NAME, () -> ctx, registry).observe(inner);
    }

    /**
     * Reproduces the user's scenario end-to-end against the real OpenTelemetry bridge, on a single
     * thread (sub-agents run synchronously): agent (live) > action (dropped by trace-action=false) >
     * llm (live). Verifies the RAW OTel parentSpanId — not how a backend such as Langfuse later
     * re-labels the node — so it answers definitively whether the llm re-parents to the agent span.
     */
    @Test
    @DisplayName("trace-action=false: the embabel.llm (chat) span re-parents directly to the agent span")
    void scopedLlmReParentsToAgentWhenActionDropped() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceAction(false);
        applyFilter(properties);

        observeScoped(new AgentObservationContext(mock(AgentProcess.class)), () ->
                observeScoped(new ActionObservationContext(mock(AgentProcess.class), mock(Action.class)), () ->
                        observeScoped(new LlmObservationContext(mock(LlmRequestEvent.class)), () -> null)));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        // action is dropped → exactly two spans (agent + llm), both placeholder-named (no conventions here).
        assertThat(spans).as("only agent + llm are exported; action is dropped").hasSize(2);

        List<SpanData> roots = spans.stream()
                .filter(s -> SpanId.getInvalid().equals(s.getParentSpanId()))
                .toList();
        assertThat(roots).as("exactly one root span (the agent); the llm must NOT be a second root").hasSize(1);

        SpanData agent = roots.get(0);
        SpanData llm = spans.stream().filter(s -> s != agent).findFirst().orElseThrow();
        assertThat(llm.getParentSpanId())
                .as("the llm (chat) re-parents directly to the agent span across the dropped action")
                .isEqualTo(agent.getSpanId());
        assertThat(llm.getTraceId()).isEqualTo(agent.getTraceId());
    }

    @Test
    @DisplayName("trace-tool-loop=false: no tool_loop span, embedding nests directly under the llm span")
    void suppressedToolLoopReParentsChildSpan() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceToolLoop(false);
        applyFilter(properties);

        // llm (live) > tool_loop (real context → dropped by the flag) > embedding (live)
        Observation.createNotStarted("embabel.llm", registry).observe((Supplier<Object>) () ->
                toolLoopObservation().observe((Supplier<Object>) () ->
                        Observation.createNotStarted("embabel.embedding", registry)
                                .observe((Supplier<Object>) () -> null)));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("embabel.llm", "embabel.embedding");

        SpanData llm = span(spans, "embabel.llm");
        SpanData embedding = span(spans, "embabel.embedding");
        assertThat(llm.getParentSpanId()).as("llm is the root span").isEqualTo(SpanId.getInvalid());
        assertThat(embedding.getParentSpanId())
                .as("embedding re-parents to the live llm span, skipping the suppressed tool_loop")
                .isEqualTo(llm.getSpanId());
        assertThat(embedding.getTraceId()).isEqualTo(llm.getTraceId());
    }

    @Test
    @DisplayName("defaults: full chain llm > tool_loop > embedding in one trace")
    void defaultsKeepFullChain() {
        applyFilter(new ObservabilityProperties());

        observeLlmToolLoopEmbedding();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("embabel.llm", "embabel.tool_loop", "embabel.embedding");

        SpanData llm = span(spans, "embabel.llm");
        SpanData toolLoop = span(spans, "embabel.tool_loop");
        SpanData embedding = span(spans, "embabel.embedding");
        assertThat(llm.getParentSpanId()).as("llm is the root span").isEqualTo(SpanId.getInvalid());
        assertThat(toolLoop.getParentSpanId()).as("tool_loop nests under llm").isEqualTo(llm.getSpanId());
        assertThat(embedding.getParentSpanId()).as("embedding nests under tool_loop").isEqualTo(toolLoop.getSpanId());
        assertThat(spans).extracting(SpanData::getTraceId).containsOnly(llm.getTraceId());
    }

    @Test
    @DisplayName("suppressed tier at the root: its child becomes the new root span")
    void suppressedRootTierLeavesChildAsRoot() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceToolLoop(false);
        applyFilter(properties);

        // tool_loop (real context) is the outermost observation; dropping it leaves embedding with no live ancestor.
        toolLoopObservation().observe((Supplier<Object>) () ->
                Observation.createNotStarted("embabel.embedding", registry)
                        .observe((Supplier<Object>) () -> null));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName).containsExactly("embabel.embedding");
        assertThat(span(spans, "embabel.embedding").getParentSpanId())
                .as("with its only ancestor suppressed, embedding becomes the root span")
                .isEqualTo(SpanId.getInvalid());
    }

    @Test
    @DisplayName("two consecutive suppressed tiers: child re-parents across both to the live ancestor")
    void twoConsecutiveSuppressedTiersReParentTransitively() {
        ObservabilityProperties properties = new ObservabilityProperties();
        // action (via disabled-traces, by name) and tool_loop (via the flag, by context type) are both
        // dropped, back to back. agent (live) > action (dropped) > tool_loop (dropped) > embedding (live).
        properties.setDisabledTraces(List.of("embabel.action"));
        properties.setTraceToolLoop(false);
        applyFilter(properties);

        Observation.createNotStarted("embabel.agent", registry).observe((Supplier<Object>) () ->
                Observation.createNotStarted("embabel.action", registry).observe((Supplier<Object>) () ->
                        toolLoopObservation().observe((Supplier<Object>) () ->
                                Observation.createNotStarted("embabel.embedding", registry)
                                        .observe((Supplier<Object>) () -> null))));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("embabel.agent", "embabel.embedding");

        SpanData agent = span(spans, "embabel.agent");
        SpanData embedding = span(spans, "embabel.embedding");
        assertThat(agent.getParentSpanId()).as("agent is the root span").isEqualTo(SpanId.getInvalid());
        assertThat(embedding.getParentSpanId())
                .as("embedding re-parents across both suppressed tiers to the agent span")
                .isEqualTo(agent.getSpanId());
        assertThat(embedding.getTraceId()).isEqualTo(agent.getTraceId());
    }

    @Test
    @DisplayName("disabled-traces drops the named span; its child re-parents to the live ancestor")
    void disabledTracesDropsNamedSpan() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setDisabledTraces(List.of("embabel.tool_loop"));
        applyFilter(properties);

        observeNested("embabel.llm", "embabel.tool_loop", "embabel.embedding");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("embabel.llm", "embabel.embedding");
        assertThat(span(spans, "embabel.embedding").getParentSpanId())
                .as("embedding re-parents to the live llm span, skipping the disabled tool_loop")
                .isEqualTo(span(spans, "embabel.llm").getSpanId());
    }

    @Test
    @DisplayName("Spring AI 'tool call' span is always dropped; its child re-parents to the live ancestor")
    void springAiToolCallSpanAlwaysDropped() {
        // No flag needed: the predicate drops "tool call" unconditionally, even at defaults.
        applyFilter(new ObservabilityProperties());

        observeNested("embabel.llm", "tool call", "embabel.embedding");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("embabel.llm", "embabel.embedding");
        assertThat(span(spans, "embabel.embedding").getParentSpanId())
                .as("embedding re-parents to llm, skipping the always-dropped Spring AI tool call span")
                .isEqualTo(span(spans, "embabel.llm").getSpanId());
    }

    @Test
    @DisplayName("suppressing a leaf tier leaves the parent span intact and exported")
    void suppressedLeafTierLeavesParentIntact() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setDisabledTraces(List.of("embabel.embedding"));
        applyFilter(properties);

        observeNested("embabel.llm", "embabel.embedding");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName).containsExactly("embabel.llm");
        assertThat(span(spans, "embabel.llm").getParentSpanId())
                .as("llm remains the root span when its only child is suppressed")
                .isEqualTo(SpanId.getInvalid());
    }

    @Test
    @DisplayName("a suppressed tier with TWO children: both re-parent to the live ancestor, neither cross-links to the other")
    void suppressedTierWithMultipleChildrenReParentsEachToAncestor() {
        // The existing re-parent tests are all single-child chains, so they cannot detect a sibling
        // being mis-attached to another sibling. Here the dropped action holds two live children
        // (concurrent-style: an llm and an embedding); each must nest directly under the agent.
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setDisabledTraces(List.of("embabel.action"));
        applyFilter(properties);

        // agent (live) > action (dropped) > { llm (live), embedding (live) }
        Observation.createNotStarted("embabel.agent", registry).observe((Supplier<Object>) () ->
                Observation.createNotStarted("embabel.action", registry).observe((Supplier<Object>) () -> {
                    Observation.createNotStarted("embabel.llm", registry).observe((Supplier<Object>) () -> null);
                    Observation.createNotStarted("embabel.embedding", registry).observe((Supplier<Object>) () -> null);
                    return null;
                }));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName)
                .as("action is dropped; agent + both children are exported")
                .containsExactlyInAnyOrder("embabel.agent", "embabel.llm", "embabel.embedding");

        SpanData agent = span(spans, "embabel.agent");
        SpanData llm = span(spans, "embabel.llm");
        SpanData embedding = span(spans, "embabel.embedding");

        assertThat(agent.getParentSpanId()).as("agent is the only root").isEqualTo(SpanId.getInvalid());
        assertThat(llm.getParentSpanId())
                .as("llm re-parents to the agent across the dropped action")
                .isEqualTo(agent.getSpanId());
        assertThat(embedding.getParentSpanId())
                .as("embedding ALSO re-parents to the agent, not to its sibling llm")
                .isEqualTo(agent.getSpanId());
        assertThat(embedding.getParentSpanId())
                .as("no cross-linking between concurrent siblings")
                .isNotEqualTo(llm.getSpanId());
        assertThat(spans).extracting(SpanData::getTraceId).containsOnly(agent.getTraceId());
    }
}
