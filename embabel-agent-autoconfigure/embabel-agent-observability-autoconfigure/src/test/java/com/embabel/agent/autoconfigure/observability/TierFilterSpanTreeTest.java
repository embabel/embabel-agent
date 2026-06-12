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

import com.embabel.agent.observability.ObservabilityProperties;
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

    @Test
    @DisplayName("trace-tool-loop=false: no tool_loop span, embedding nests directly under the llm span")
    void suppressedToolLoopReParentsChildSpan() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceToolLoop(false);
        applyFilter(properties);

        observeLlmToolLoopEmbedding();

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
}
